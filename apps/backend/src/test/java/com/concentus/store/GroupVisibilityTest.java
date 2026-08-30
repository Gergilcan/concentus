package com.concentus.store;

import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.groups.GroupContext;
import com.concentus.groups.GroupStore;
import com.concentus.model.FlowGraph;
import com.concentus.model.McpDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who sees a group-scoped row: its members and the organization's admins, and nobody else — with
 * the answer decided by the store's SQL and the caller read from the security context, the way
 * production reads it.
 *
 * <p>And who is NOT filtered: a thread with no principal (a cron, a run's own threads), and the
 * two cross-organization escapes. A group's flow has to run whether or not anybody is signed in.
 */
class GroupVisibilityTest {

    private static final String ORG = "org_a";

    @TempDir
    Path dataDir;

    private JdbcTemplate jdbc;
    private GroupStore groups;
    private FlowStore flows;
    private McpDefStore mcps;
    private String group;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);
        for (String table : List.of("group_memberships", "groups")) jdbc.update("delete from " + table);
        OrgContext orgContext = new OrgContext(ORG);
        groups = new GroupStore(jdbc);
        groups.init();
        GroupContext context = new GroupContext(orgContext, groups);
        flows = new FlowStore(jdbc, dataDir.toString(), new ObjectMapper(), orgContext);
        flows.init();
        flows.setGroupContext(context);
        mcps = new McpDefStore(jdbc, dataDir.toString(), new ObjectMapper(), orgContext);
        mcps.init();
        mcps.setGroupContext(context);
        group = groups.create(ORG, "Platform", null, null).id();
        groups.addMember(group, "usr_alice", false);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String userId, String role) {
        ConcentusUserDetails user = new ConcentusUserDetails(userId, ORG, userId + "@x.test", "hash", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static FlowGraph flow(String id, String name) {
        return new FlowGraph(id, name, List.of(), List.of(), null, List.of(), null, null);
    }

    /** An unscoped flow and a group-scoped one, written by an admin. */
    private FlowGraph[] seed() {
        signIn("usr_admin", Accounts.ROLE_ADMIN);
        FlowGraph open = flows.save(flow(null, "Everyone"));
        FlowGraph scoped = flows.save(flow(null, "Platform only"));
        assertThat(flows.assignGroup(ORG, scoped.id(), group)).isTrue();
        return new FlowGraph[] {open, scoped};
    }

    @Test
    void a_member_sees_the_groups_rows_a_colleague_outside_it_does_not_and_an_admin_sees_everything() {
        FlowGraph[] seeded = seed();
        FlowGraph open = seeded[0];
        FlowGraph scoped = seeded[1];

        signIn("usr_alice", Accounts.ROLE_MEMBER);
        assertThat(flows.list()).extracting(FlowGraph::name).containsExactly("Everyone", "Platform only");
        assertThat(flows.get(scoped.id())).isPresent();
        // The group travels on the wire, from the column: the JSON in the row does not carry it.
        assertThat(flows.get(scoped.id()).orElseThrow().groupId()).isEqualTo(group);
        assertThat(flows.get(open.id()).orElseThrow().groupId()).isNull();
        assertThat(jdbc.queryForObject("select json from resources where id = ?", String.class, scoped.id()))
                .doesNotContain("groupId");

        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(flows.list()).extracting(FlowGraph::name).containsExactly("Everyone");
        // By id, from outside the group: the same answer as for an id that does not exist.
        assertThat(flows.get(scoped.id())).isEmpty();
        assertThat(flows.get(open.id())).isPresent();

        signIn("usr_admin", Accounts.ROLE_ADMIN);
        assertThat(flows.list()).hasSize(2);
        assertThat(flows.get(scoped.id())).isPresent();
    }

    @Test
    void outside_the_group_a_row_can_be_neither_deleted_nor_overwritten_by_id() {
        FlowGraph scoped = seed()[1];

        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(flows.delete(scoped.id())).isFalse();
        assertThatThrownBy(() -> flows.save(flow(scoped.id(), "Hijacked")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a group you are not in");

        signIn("usr_alice", Accounts.ROLE_MEMBER);
        assertThat(flows.get(scoped.id()).orElseThrow().name()).isEqualTo("Platform only");
    }

    @Test
    void a_save_keeps_the_rows_group_whatever_the_record_says() {
        FlowGraph scoped = seed()[1];

        signIn("usr_alice", Accounts.ROLE_MEMBER);
        // The record arrives without a group (the pre-groups shape) — the row keeps its own.
        flows.save(flow(scoped.id(), "Renamed"));
        assertThat(flows.groupOf(scoped.id())).contains(group);
        // And a record that names a group it does not belong to changes nothing either.
        FlowGraph forged = new FlowGraph(scoped.id(), "Forged", List.of(), List.of(), null, List.of(), null,
                null, null, null, null, null, null, null, null, "gr_somewhere_else");
        flows.save(forged);
        assertThat(flows.groupOf(scoped.id())).contains(group);
        assertThat(flows.get(scoped.id()).orElseThrow().groupId()).isEqualTo(group);
    }

    @Test
    void with_no_principal_and_through_the_cross_organization_escapes_nothing_is_filtered() {
        FlowGraph scoped = seed()[1];

        // A cron tick, a run's own thread: no principal, every row — a group's flow must fire.
        SecurityContextHolder.clearContext();
        assertThat(flows.list()).hasSize(2);
        assertThat(flows.get(scoped.id())).isPresent();
        assertThat(flows.getIn(ORG, scoped.id())).isPresent();

        // And the escapes a webhook or a published-flow token uses, whoever is signed in.
        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(flows.listAcrossOrganizations()).hasSize(2);
        assertThat(flows.getAcrossOrganizations(scoped.id())).isPresent();
        assertThat(flows.getAcrossOrganizations(scoped.id()).orElseThrow().groupId()).isEqualTo(group);
    }

    @Test
    void every_kind_of_resource_is_filtered_the_same_way() {
        signIn("usr_admin", Accounts.ROLE_ADMIN);
        McpDef server = mcps.save(McpDef.http(null, "Linear", "https://linear.test/mcp", null, null));
        mcps.assignGroup(ORG, server.id(), group);

        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(mcps.list()).isEmpty();
        assertThat(mcps.get(server.id())).isEmpty();

        signIn("usr_alice", Accounts.ROLE_MEMBER);
        assertThat(mcps.list()).extracting(McpDef::name).containsExactly("Linear");
        assertThat(mcps.get(server.id()).orElseThrow().groupId()).isEqualTo(group);

        // Back to the organization: everybody again.
        signIn("usr_admin", Accounts.ROLE_ADMIN);
        mcps.assignGroup(ORG, server.id(), null);
        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(mcps.get(server.id())).isPresent();
        assertThat(mcps.get(server.id()).orElseThrow().groupId()).isNull();
    }
}
