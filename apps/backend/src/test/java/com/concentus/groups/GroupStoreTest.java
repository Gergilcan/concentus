package com.concentus.groups;

import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The group tables against the real migration: the roster, the memberships, the counts, and the
 * one write that reaches the other tables — deleting a group returns what it held to the
 * organization and leaves the runs that already happened alone.
 */
class GroupStoreTest {

    private JdbcTemplate jdbc;
    private GroupStore store;
    private AccountStore accounts;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);
        for (String table : List.of("group_memberships", "groups", "credentials", "marketplace_items", "runs",
                "memberships", "users", "organizations")) {
            jdbc.update("delete from " + table);
        }
        accounts = new AccountStore(jdbc);
        accounts.createOrganization("org_a", "A");
        accounts.createOrganization("org_b", "B");
        store = new GroupStore(jdbc);
        store.init();
        assertThat(store.isAvailable()).isTrue();
    }

    private String user(String email, String org, String role) {
        return accounts.createUser(org, email, "hash", role).id();
    }

    @Test
    void a_group_is_created_listed_by_name_found_in_its_organization_only_and_renamed() {
        Group platform = store.create("org_a", "Platform", "The platform team", "admin@a.test");
        Group support = store.create("org_a", "support", null, "admin@a.test");
        store.create("org_b", "Platform", null, "admin@b.test");   // the same name elsewhere is free

        assertThat(platform.id()).startsWith(Group.ID_PREFIX).hasSize(15);
        assertThat(store.list("org_a")).extracting(Group::name).containsExactly("Platform", "support");
        assertThat(store.find("org_a", platform.id())).isPresent();
        assertThat(store.find("org_b", platform.id())).isEmpty();
        assertThat(store.nameOf(support.id())).contains("support");

        assertThat(store.update("org_a", support.id(), "Support crew", "Answers tickets"))
                .get().extracting(Group::name, Group::description).containsExactly("Support crew", "Answers tickets");
        assertThat(store.update("org_b", support.id(), "Hijacked", null)).isEmpty();
    }

    @Test
    void a_name_is_unique_per_organization_case_blind() {
        store.create("org_a", "Platform", null, null);

        assertThatThrownBy(() -> store.create("org_a", "platform", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        Group other = store.create("org_a", "Other", null, null);
        assertThatThrownBy(() -> store.update("org_a", other.id(), "PLATFORM", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memberships_carry_the_manager_flag_the_organization_role_and_are_upserted() {
        String alice = user("alice@a.test", "org_a", Accounts.ROLE_MEMBER);
        String bob = user("bob@a.test", "org_a", Accounts.ROLE_ADMIN);
        Group g = store.create("org_a", "Platform", null, null);

        store.addMember(g.id(), alice, false);
        store.addMember(g.id(), bob, true);
        assertThat(store.members("org_a", g.id())).extracting(GroupMember::email, GroupMember::role, GroupMember::manager)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("alice@a.test", "MEMBER", false),
                        org.assertj.core.groups.Tuple.tuple("bob@a.test", "ADMIN", true));
        assertThat(store.membership(g.id(), alice)).contains(false);
        assertThat(store.membership(g.id(), "usr_nobody")).isEmpty();

        // Adding again is the manager toggle — one row, a new flag.
        store.addMember(g.id(), alice, true);
        assertThat(store.membership(g.id(), alice)).contains(true);
        assertThat(store.find("org_a", g.id()).orElseThrow().members()).isEqualTo(2);

        assertThat(store.membershipsOf(alice, "org_a")).containsExactly(Map.entry(g.id(), true));
        // The same account in another organization is in none of that organization's groups.
        assertThat(store.membershipsOf(alice, "org_b")).isEmpty();

        assertThat(store.removeMember(g.id(), alice)).isTrue();
        assertThat(store.removeMember(g.id(), alice)).isFalse();
        assertThat(store.membershipsOf(alice, "org_a")).isEmpty();
    }

    @Test
    void deleting_a_group_unscopes_its_resources_credentials_and_marketplace_items_and_keeps_its_runs() {
        Group g = store.create("org_a", "Platform", null, null);
        store.addMember(g.id(), user("alice@a.test", "org_a", Accounts.ROLE_MEMBER), true);
        jdbc.update("insert into resources (kind, id, json, updated_at, organization_id, group_id) values "
                + "('flow', 'flow_1', '{}', 1, 'org_a', ?)", g.id());
        jdbc.update("insert into resources (kind, id, json, updated_at, organization_id, group_id) values "
                + "('mcp', 'mcp_1', '{}', 1, 'org_a', ?)", g.id());
        jdbc.update("insert into resources (kind, id, json, updated_at, organization_id) values "
                + "('flow', 'flow_2', '{}', 1, 'org_a')");
        jdbc.update("insert into credentials (id, organization_id, label, kind, secret, created_at, updated_at, group_id) "
                + "values ('cred_1', 'org_a', 'Token', 'api-token', 'x', 1, 1, ?)", g.id());
        jdbc.update("insert into marketplace_items (id, kind, name, scope, organization_id, group_id, status, "
                + "author_user_id, payload, created_at, updated_at) values ('mkt_1', 'mcp', 'X', 'group', 'org_a', ?, "
                + "'published', 'usr', '{}'::jsonb, 1, 1)", g.id());
        jdbc.update("insert into runs (id, flow_id, flow_name, status, created_at, updated_at, organization_id, group_id) "
                + "values ('run_1', 'flow_1', 'F', 'COMPLETED', 1, 1, 'org_a', ?)", g.id());
        assertThat(store.find("org_a", g.id()).orElseThrow().resources()).isEqualTo(3);

        assertThat(store.delete("org_b", g.id())).isEqualTo(new GroupStore.Deleted(false, 0));
        GroupStore.Deleted deleted = store.delete("org_a", g.id());

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.unscoped()).isEqualTo(4);
        assertThat(store.find("org_a", g.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from group_memberships", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from resources where group_id is not null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from resources", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select group_id from credentials where id = 'cred_1'", String.class)).isNull();
        assertThat(jdbc.queryForObject("select scope from marketplace_items where id = 'mkt_1'", String.class))
                .isEqualTo("organization");
        // History is not rewritten: the run still says which group's settings it resolved against.
        assertThat(jdbc.queryForObject("select group_id from runs where id = 'run_1'", String.class)).isEqualTo(g.id());
        jdbc.update("delete from runs");
    }

    @Test
    void an_unavailable_table_reads_empty_and_refuses_writes() {
        GroupStore dead = new GroupStore(new JdbcTemplate());
        dead.init();

        assertThat(dead.isAvailable()).isFalse();
        assertThat(dead.list("org_a")).isEmpty();
        assertThat(dead.membershipsOf("usr", "org_a")).isEmpty();
        assertThatThrownBy(() -> dead.create("org_a", "X", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
}
