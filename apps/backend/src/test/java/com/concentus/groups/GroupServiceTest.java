package com.concentus.groups;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.config.Settings;
import com.concentus.config.SettingsStore;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.model.FlowGraph;
import com.concentus.model.McpDef;
import com.concentus.policy.OrgPolicy;
import com.concentus.policy.OrgPolicyService;
import com.concentus.secrets.CredentialStore;
import com.concentus.secrets.SecretCipher;
import com.concentus.store.FlowStore;
import com.concentus.store.McpDefStore;
import com.concentus.store.OrgPolicyStore;
import com.concentus.store.PublishApprovalStore;
import com.concentus.store.TestDatabase;
import com.concentus.store.TestStores;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of groups, against the real stores on the test database, with the caller read from
 * the security context the way production reads it. The license is a fixture; the audit trail is
 * the one mock, so the rows can be asserted.
 */
class GroupServiceTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrgContext orgContext = new OrgContext("default");
    private final AuditService audit = mock(AuditService.class);
    private final OrgPolicyStore orgPolicyStore = mock(OrgPolicyStore.class);
    private final MockEnvironment env = new MockEnvironment();

    private JdbcTemplate jdbc;
    private AccountStore accounts;
    private GroupStore store;
    private GroupPolicyStore policies;
    private GroupContext context;
    private SettingsStore settingsStore;
    private Settings settings;
    private FlowStore flows;
    private McpDefStore mcps;
    private CredentialStore credentials;
    private LicenseService license;
    private GroupService service;
    private GroupController controller;

    private String admin;
    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);
        for (String table : List.of("group_memberships", "groups", "group_settings", "group_policies", "credentials",
                "settings", "memberships", "users", "organizations")) {
            jdbc.update("delete from " + table);
        }
        accounts = new AccountStore(jdbc);
        accounts.createOrganization("org_a", "A");
        accounts.createOrganization("org_b", "B");
        admin = accounts.createUser("org_a", "admin@a.test", "hash", Accounts.ROLE_ADMIN).id();
        alice = accounts.createUser("org_a", "alice@a.test", "hash", Accounts.ROLE_MEMBER).id();
        bob = accounts.createUser("org_a", "bob@a.test", "hash", Accounts.ROLE_MEMBER).id();
        carol = accounts.createUser("org_b", "carol@b.test", "hash", Accounts.ROLE_ADMIN).id();

        store = new GroupStore(jdbc);
        store.init();
        policies = new GroupPolicyStore(jdbc, mapper);
        policies.init();
        context = new GroupContext(orgContext, store);
        settingsStore = new SettingsStore(jdbc, new SecretCipher(""));
        env.setProperty("workers.retries", "1");
        env.setProperty("workers.timeout-seconds", "900");
        settings = new Settings(settingsStore, env, orgContext);
        flows = TestStores.flows(jdbc, dir, mapper, orgContext);
        flows.setGroupContext(context);
        mcps = TestStores.mcpDefs(jdbc, dir, mapper, orgContext);
        mcps.setGroupContext(context);
        credentials = new CredentialStore(jdbc, new SecretCipher(""));
        credentials.init();
        credentials.setGroupContext(context);
        on("enterprise-test.license");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** The service on a license: the fixture, or none for an unlicensed installation. */
    private GroupService on(String fixture) throws Exception {
        if (fixture != null) TestLicenses.installFixture(dir, fixture);
        license = TestLicenses.serviceOn(dir);
        OrgPolicyService orgPolicies = new OrgPolicyService(orgPolicyStore, mock(PublishApprovalStore.class),
                license, orgContext, policies, flows);
        service = new GroupService(orgContext, context, store, policies, settingsStore, settings, license, accounts,
                audit, orgPolicies, credentials, List.of(flows, mcps), mapper);
        controller = new GroupController(service);
        return service;
    }

    private void signIn(String userId) {
        Accounts.UserAccount account = accounts.findById(userId).orElseThrow();
        ConcentusUserDetails user = ConcentusUserDetails.of(account);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private Group platform() {
        signIn(admin);
        Group group = service.create("Platform", "The platform team");
        service.addMember(group.id(), alice, true);
        service.addMember(group.id(), bob, false);
        return group;
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    // ------------------------------------------------------------------ the roster

    @Test
    void an_admin_creates_a_group_members_join_and_each_caller_sees_their_own() throws Exception {
        Group group = platform();
        assertThat(group.id()).startsWith("gr_");
        verify(audit).record(eq(AuditKinds.GROUP_CREATED), eq("group"), eq(group.id()), eq("Platform"), isNull());
        verify(audit).record(eq(AuditKinds.GROUP_MEMBER_ADDED), eq("group"), eq(group.id()), eq("Platform"),
                eq(Map.of("userId", alice, "email", "alice@a.test", "manager", true)));

        // The admin: every group, and not a manager of any — administering is the other thing.
        GroupController.Listing asAdmin = controller.list();
        System.out.println("GET /api/groups (admin) -> " + json(asAdmin));
        assertThat(asAdmin.allowed()).isTrue();
        assertThat(asAdmin.refusal()).isNull();
        assertThat(asAdmin.groups()).singleElement().satisfies(g -> {
            assertThat(g.members()).isEqualTo(2);
            assertThat(g.resources()).isZero();
            assertThat(g.manager()).isFalse();
            assertThat(g.createdBy()).isEqualTo("admin@a.test");
        });
        assertThat(controller.members(group.id())).extracting(GroupMember::email, GroupMember::role, GroupMember::manager)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("alice@a.test", "MEMBER", true),
                        org.assertj.core.groups.Tuple.tuple("bob@a.test", "MEMBER", false));

        signIn(alice);
        GroupController.Listing asAlice = controller.list();
        System.out.println("GET /api/groups (manager) -> " + json(asAlice));
        assertThat(asAlice.groups()).singleElement().satisfies(g -> assertThat(g.manager()).isTrue());
        GroupService.Status status = controller.status();
        System.out.println("GET /api/groups/status (manager) -> " + json(status));
        assertThat(status.allowed()).isTrue();
        assertThat(status.groups()).isEqualTo(1);
        assertThat(status.mine()).containsExactly(new Group.Ref(group.id(), "Platform", true));

        signIn(bob);
        assertThat(controller.list().groups()).singleElement().satisfies(g -> assertThat(g.manager()).isFalse());

        // Another organization's admin: nothing, and nothing by id — the same as an id nothing has.
        signIn(carol);
        assertThat(controller.list().groups()).isEmpty();
        assertThat(controller.status().groups()).isZero();
        assertStatus(catchThrowable(() -> controller.members(group.id())), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> controller.members("gr_000000000000")), HttpStatus.NOT_FOUND);
    }

    // What the session carries — the same list — is `mine()`; the endpoint's wiring is
    // SessionGroupsTest's, beside the controller it lives in.
    @Test
    void mine_is_the_callers_groups_with_the_manager_flag_in_membership_order() {
        Group platform = platform();
        signIn(admin);
        Group support = service.create("Support", null);
        service.addMember(support.id(), alice, false);

        signIn(alice);
        assertThat(service.mine()).containsExactly(new Group.Ref(platform.id(), "Platform", true),
                new Group.Ref(support.id(), "Support", false));
        signIn(admin);
        assertThat(service.mine()).isEmpty();
        signIn(carol);
        assertThat(service.mine()).isEmpty();
    }

    @Test
    void only_an_admin_creates_or_deletes_a_manager_or_admin_changes_and_an_outsider_gets_404() {
        Group group = platform();

        signIn(alice);   // manager
        assertThat(service.update(group.id(), "Platform team", null).name()).isEqualTo("Platform team");
        verify(audit).record(eq(AuditKinds.GROUP_UPDATED), eq("group"), eq(group.id()), eq("Platform team"),
                eq(Map.of("renamedFrom", "Platform")));
        assertThatThrownBy(() -> service.create("Another", null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class).hasMessageContaining("administrator");
        assertThatThrownBy(() -> service.delete(group.id()))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class).hasMessageContaining("administrator");
        // The manager toggle is a second POST of the same member with the other flag.
        assertThat(service.addMember(group.id(), bob, true).manager()).isTrue();
        assertThat(store.membership(group.id(), bob)).contains(true);
        assertThat(controller.members(group.id())).hasSize(2);

        signIn(bob);
        service.addMember(group.id(), bob, false);   // a manager now, demoting himself is fine
        assertThatThrownBy(() -> service.update(group.id(), "Bob's", null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class).hasMessageContaining("manager");
        assertThatThrownBy(() -> service.addMember(group.id(), carol, false))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> service.removeMember(group.id(), alice))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThat(controller.settings(group.id())).isNotNull();   // reading is every member's

        signIn(carol);
        assertStatus(catchThrowable(() -> service.update(group.id(), "Carol's", null)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> service.policy(group.id())), HttpStatus.NOT_FOUND);

        // An account of another organization cannot be put into the group.
        signIn(admin);
        assertThatThrownBy(() -> service.addMember(group.id(), carol, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a member of this organization");
        service.removeMember(group.id(), bob);
        verify(audit).record(eq(AuditKinds.GROUP_MEMBER_REMOVED), eq("group"), eq(group.id()), eq("Platform team"),
                eq(Map.of("userId", bob, "email", "bob@a.test")));
        assertStatus(catchThrowable(() -> service.removeMember(group.id(), bob)), HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.create("platform TEAM", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    // ------------------------------------------------------------------ the gate

    @Test
    void on_a_team_license_reads_keep_working_and_every_write_is_refused_with_the_features_sentence() throws Exception {
        Group group = platform();
        signIn(admin);
        FlowGraph flow = flows.save(new FlowGraph(null, "Ads", List.of(), List.of(), null, List.of(), null, null));
        service.assign("flow", flow.id(), group.id());
        on("team-test.license");
        String sentence = "Groups inside an organization is an Enterprise feature";

        GroupController.Listing listing = controller.list();
        assertThat(listing.allowed()).isFalse();
        assertThat(listing.refusal()).contains(sentence).contains("Team license");
        assertThat(listing.groups()).singleElement().satisfies(g -> assertThat(g.resources()).isEqualTo(1));
        assertThat(controller.members(group.id())).hasSize(2);
        assertThat(controller.settings(group.id()).keys()).isNotEmpty();
        assertThat(controller.policy(group.id()).refusal()).contains(sentence);
        assertThat(controller.status().allowed()).isFalse();
        // What the group scopes stays scoped: a downgrade never widens who sees what.
        signIn(carol);
        signIn(bob);
        assertThat(flows.get(flow.id())).isPresent();   // bob is in the group
        store.removeMember(group.id(), bob);
        assertThat(flows.get(flow.id())).isEmpty();

        signIn(admin);
        for (Runnable write : List.<Runnable>of(
                () -> service.create("Support", null),
                () -> service.update(group.id(), "Renamed", null),
                () -> service.delete(group.id()),
                () -> service.addMember(group.id(), bob, false),
                () -> service.removeMember(group.id(), alice),
                () -> service.replaceSettings(group.id(), Map.of("workers.retries", "3")),
                () -> service.savePolicy(group.id(), new GroupPolicy(null, null, "plan", null, null)),
                () -> service.assign("flow", flow.id(), null))) {
            assertThatThrownBy(write::run)
                    .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                    .hasMessageContaining(sentence);
        }
        assertThat(flows.groupOf(flow.id())).contains(group.id());
        verify(audit, never()).record(eq(AuditKinds.GROUP_DELETED), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settings_are_replaced_whole_only_group_scoped_keys_are_accepted_and_inherited_values_are_shown() throws Exception {
        Group group = platform();
        settingsStore.put("org_a", "workers.retries", "3", false, "admin@a.test");

        signIn(alice);
        GroupService.SettingsView saved = controller.saveSettings(group.id(), new GroupController.SettingsRequest(
                Map.of("workers.retries", "5", "local.permission-mode", "plan", "workers.timeout-seconds", "")));
        System.out.println("GET /api/groups/{id}/settings -> " + json(saved));
        assertThat(saved.values()).containsExactlyInAnyOrderEntriesOf(
                Map.of("workers.retries", "5", "local.permission-mode", "plan"));
        // Inherited: the organization's override, else the deployment's, else nothing.
        assertThat(saved.inherited()).containsEntry("workers.retries", "3")
                .containsEntry("workers.timeout-seconds", "900")
                .containsEntry("local.permission-mode", "")
                .containsEntry("usage.weekly-allowance-usd", "");
        assertThat(saved.keys()).extracting(k -> k.get("key")).containsExactlyInAnyOrder(
                "usage.weekly-allowance-usd", "workers.timeout-seconds", "workers.retries", "local.permission-mode");
        assertThat(saved.keys()).allSatisfy(k -> assertThat(k).containsKeys("key", "label", "group", "description",
                "type", "options", "restartRequired", "groupScoped"));
        verify(audit).record(eq(AuditKinds.GROUP_SETTINGS_CHANGED), eq("group"), eq(group.id()), eq("Platform"),
                argThatHasKeys("workers.retries", "local.permission-mode"));

        // What a run of the group reads.
        assertThat(settings.forGroup("org_a", group.id(), "workers.retries")).contains("5");
        assertThat(settings.forRun("org_a", group.id()).get("local.permission-mode", "bypassPermissions")).isEqualTo("plan");
        assertThat(settings.forRun("org_a", group.id()).number("workers.timeout-seconds", 1)).isEqualTo(900);
        assertThat(settings.forRun("org_a", null).number("workers.retries", 1)).isEqualTo(3);

        // A replacement: a key absent from the body goes back to inherited.
        GroupService.SettingsView replaced = service.replaceSettings(group.id(), Map.of("workers.timeout-seconds", "60"));
        assertThat(replaced.values()).containsExactly(Map.entry("workers.timeout-seconds", "60"));
        assertThat(settings.forGroup("org_a", group.id(), "workers.retries")).contains("3");
        assertThat(settings.forRun("org_a", group.id()).number("workers.timeout-seconds", 1)).isEqualTo(60);

        assertThatThrownBy(() -> service.replaceSettings(group.id(), Map.of("runs.max-concurrent", "9")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be set per group");
        assertThatThrownBy(() -> service.replaceSettings(group.id(), Map.of("no.such.key", "9")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown setting");
        assertThatThrownBy(() -> service.replaceSettings(group.id(), Map.of("local.permission-mode", "yolo")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yolo");
        assertThatThrownBy(() -> service.replaceSettings(group.id(), Map.of("workers.retries", "many")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number");
        // Refused writes change nothing.
        assertThat(settingsStore.groupSettings(group.id())).containsOnlyKeys("workers.timeout-seconds");
    }

    // ------------------------------------------------------------------ policy

    @Test
    void a_groups_policy_lays_over_the_organizations_and_a_null_field_inherits() throws Exception {
        Group group = platform();
        when(orgPolicyStore.getAcrossOrganizations("org_a")).thenReturn(Optional.of(
                new OrgPolicy("org_a", "fprof_reader", true, "acceptEdits", 100.0, false)));

        signIn(alice);
        GroupService.PolicyView untouched = controller.policy(group.id());
        assertThat(untouched.policy()).isEqualTo(GroupPolicy.NONE);
        assertThat(untouched.effective().maxPermissionMode()).isEqualTo("acceptEdits");
        assertThat(untouched.enforced()).isTrue();

        GroupService.PolicyView view = controller.savePolicy(group.id(), new GroupPolicy(" ", null, " plan ", 40.0, null));
        System.out.println("GET /api/groups/{id}/policy -> " + json(view));
        assertThat(view.policy()).isEqualTo(new GroupPolicy(null, null, "plan", 40.0, null));
        assertThat(view.effective().defaultFacadeProfileId()).isEqualTo("fprof_reader");   // inherited
        assertThat(view.effective().requireFacade()).isTrue();                             // inherited
        assertThat(view.effective().maxPermissionMode()).isEqualTo("plan");                 // the group's
        assertThat(view.effective().monthlyBudgetUsd()).isEqualTo(100.0);                   // the organization's, always
        assertThat(view.effective().publishRequiresApproval()).isFalse();
        // The JSON is the group policy flattened, beside the effective one.
        assertThat(json(view)).contains("\"maxPermissionMode\":\"plan\"").contains("\"effective\":{");
        verify(audit).record(eq(AuditKinds.GROUP_POLICY_CHANGED), eq("group"), eq(group.id()), eq("Platform"),
                eq(Map.of("maxPermissionMode", "plan", "monthlyBudgetUsd", 40.0)));

        // The service every enforcement point asks answers with the layering, by flow and by group.
        signIn(admin);
        FlowGraph flow = flows.save(new FlowGraph(null, "Ads", List.of(), List.of(), null, List.of(), null, null));
        service.assign("flow", flow.id(), group.id());
        OrgPolicyService orgPolicies = new OrgPolicyService(orgPolicyStore, mock(PublishApprovalStore.class),
                license, orgContext, policies, flows);
        assertThat(orgPolicies.maxPermissionMode(flow)).isEqualTo("plan");
        assertThat(orgPolicies.groupBudgetUsd(group.id())).isEqualTo(40.0);
        assertThat(orgPolicies.effective(new FlowGraph(null, "Unsaved", List.of(), List.of(), null, List.of(), null, null))
                .maxPermissionMode()).isEqualTo("acceptEdits");

        assertThatThrownBy(() -> service.savePolicy(group.id(), new GroupPolicy(null, null, "bypass", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bypass");
        assertThatThrownBy(() -> service.savePolicy(group.id(), new GroupPolicy(null, null, null, -1.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        // Everything inherited again is no policy at all.
        service.savePolicy(group.id(), GroupPolicy.NONE);
        assertThat(policies.get(group.id())).isEmpty();
        assertThat(orgPolicies.maxPermissionMode(flow)).isEqualTo("acceptEdits");
    }

    // ------------------------------------------------------------------ assigning resources

    @Test
    void assign_moves_a_resource_for_a_member_refuses_outsiders_and_is_audited() {
        Group group = platform();
        signIn(admin);
        FlowGraph flow = flows.save(new FlowGraph(null, "Ads", List.of(), List.of(), null, List.of(), null, null));
        McpDef server = mcps.save(McpDef.http(null, "Linear", "https://linear.test/mcp", null, null));
        CredentialStore.Credential credential = credentials.create("org_a", "Token", CredentialStore.Kind.API_TOKEN, "s3cret-value");

        signIn(bob);   // a member, not a manager: moving a resource into the group is membership's right
        assertThat(service.assign("flow", flow.id(), group.id()))
                .isEqualTo(new GroupService.Assignment("flow", flow.id(), group.id()));
        assertThat(flows.groupOf(flow.id())).contains(group.id());
        assertThat(flows.get(flow.id()).orElseThrow().groupId()).isEqualTo(group.id());
        verify(audit).record(eq(AuditKinds.RESOURCE_GROUP_CHANGED), eq("flow"), eq(flow.id()), eq("Ads"),
                eq(Map.of("kind", "flow", "to", group.id())));
        assertThat(controller.list().groups()).singleElement().satisfies(g -> assertThat(g.resources()).isEqualTo(1));

        signIn(carol);
        assertStatus(catchThrowable(() -> service.assign("mcp", server.id(), group.id())), HttpStatus.NOT_FOUND);
        store.removeMember(group.id(), bob);
        signIn(bob);   // no longer in the group: neither the group nor the flow exists for him
        assertStatus(catchThrowable(() -> service.assign("mcp", server.id(), group.id())), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> service.assign("flow", flow.id(), null)), HttpStatus.NOT_FOUND);
        assertThat(flows.groupOf(flow.id())).contains(group.id());

        signIn(admin);
        assertThat(service.assign("credential", credential.id(), group.id()).groupId()).isEqualTo(group.id());
        assertThat(credentials.groupOf("org_a", credential.id())).contains(group.id());
        verify(audit).record(eq(AuditKinds.RESOURCE_GROUP_CHANGED), eq("credential"), eq(credential.id()), eq("Token"),
                eq(Map.of("kind", "credential", "to", group.id())));
        assertThatThrownBy(() -> service.assign("org-policy", "org_a", group.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a kind of resource");
        assertStatus(catchThrowable(() -> service.assign("flow", "flow_nothing", group.id())), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> service.assign("flow", flow.id(), "gr_000000000000")), HttpStatus.NOT_FOUND);

        // Back to the organization, by a manager.
        signIn(alice);
        assertThat(service.assign("flow", flow.id(), null).groupId()).isNull();
        verify(audit).record(eq(AuditKinds.RESOURCE_GROUP_CHANGED), eq("flow"), eq(flow.id()), eq("Ads"),
                eq(Map.of("kind", "flow", "from", group.id())));
        signIn(bob);
        assertThat(flows.get(flow.id())).isPresent();
    }

    @Test
    void deleting_a_group_unscopes_what_it_held_and_clears_its_settings_and_policy() {
        Group group = platform();
        signIn(admin);
        FlowGraph flow = flows.save(new FlowGraph(null, "Ads", List.of(), List.of(), null, List.of(), null, null));
        service.assign("flow", flow.id(), group.id());
        service.replaceSettings(group.id(), Map.of("workers.retries", "5"));
        service.savePolicy(group.id(), new GroupPolicy(null, null, "plan", null, null));

        Map<String, Object> deleted = controller.delete(group.id());

        assertThat(deleted).isEqualTo(Map.of("deleted", true, "unscoped", 1));
        assertThat(store.find("org_a", group.id())).isEmpty();
        assertThat(settingsStore.groupSettings(group.id())).isEmpty();
        assertThat(policies.get(group.id())).isEmpty();
        assertThat(flows.groupOf(flow.id())).isEmpty();
        verify(audit).record(eq(AuditKinds.GROUP_DELETED), eq("group"), eq(group.id()), eq("Platform"),
                eq(Map.of("members", 2, "unscoped", 1)));
        signIn(carol);
        signIn(bob);
        assertThat(flows.get(flow.id())).isPresent();
        signIn(admin);
        assertStatus(catchThrowable(() -> controller.delete(group.id())), HttpStatus.NOT_FOUND);
        assertThat(controller.list().groups()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, ?> argThatHasKeys(String... keys) {
        return org.mockito.ArgumentMatchers.argThat(detail -> {
            Object list = ((Map<String, ?>) detail).get("keys");
            return list instanceof List<?> l && l.containsAll(List.of(keys)) && l.size() == keys.length;
        });
    }
}
