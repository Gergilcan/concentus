package com.concentus.runners;

import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.groups.Group;
import com.concentus.groups.GroupContext;
import com.concentus.groups.GroupStore;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may see, register, manage and use a runner, per scope and role — and which one a launch
 * gets. The store and the registry are mocked; the group memberships go through the real
 * {@link GroupContext} over a mocked group store, because the membership rule IS the test.
 */
class RunnerServiceTest {

    @TempDir
    Path dir;

    private static final String ORG = "org_acme";
    private static final ConcentusUserDetails ADMIN = user("usr_admin", "admin@acme.test", Accounts.ROLE_ADMIN);
    private static final ConcentusUserDetails ALICE = user("usr_alice", "alice@acme.test", Accounts.ROLE_MEMBER);
    private static final ConcentusUserDetails BOB = user("usr_bob", "bob@acme.test", Accounts.ROLE_MEMBER);

    private final RunnerStore store = mock(RunnerStore.class);
    private final RunnerRegistry registry = mock(RunnerRegistry.class);
    private final GroupStore groupStore = mock(GroupStore.class);
    private final AccountStore accounts = mock(AccountStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final OrgContext orgContext = new OrgContext("default");
    private final GroupContext groups = new GroupContext(orgContext, groupStore);
    private final List<Runner> rows = new ArrayList<>();

    private static ConcentusUserDetails user(String id, String email, String role) {
        return new ConcentusUserDetails(id, ORG, email, "hash", role, true);
    }

    private static Runner runner(String id, String name, String scope, String groupId, String userId) {
        return new Runner(id, ORG, name, scope, groupId, userId, "hash-" + id, "admin@acme.test", 1L, null, null);
    }

    private final Runner orgWide = runner("rn_org", "nas", Runner.SCOPE_ORGANIZATION, null, null);
    private final Runner platform = runner("rn_grp", "platform-box", Runner.SCOPE_GROUP, "gr_platform", null);
    private final Runner alicesLaptop = runner("rn_alice", "alice-laptop", Runner.SCOPE_USER, null, "usr_alice");
    private final Runner bobsLaptop = runner("rn_bob", "bob-laptop", Runner.SCOPE_USER, null, "usr_bob");

    @BeforeEach
    void rowsAndMemberships() {
        rows.addAll(List.of(orgWide, platform, alicesLaptop, bobsLaptop));
        when(store.list(ORG)).thenAnswer(inv -> List.copyOf(rows));
        when(store.find(eq(ORG), anyString())).thenAnswer(inv ->
                rows.stream().filter(r -> r.id().equals(inv.getArgument(1))).findFirst());
        when(store.create(anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenAnswer(inv ->
                new Runner("rn_new", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4), inv.getArgument(5), inv.getArgument(6), 2L, null, null));
        // Alice manages platform; Bob is in nothing.
        when(groupStore.membershipsOf("usr_alice", ORG)).thenReturn(Map.of("gr_platform", true));
        when(groupStore.membershipsOf("usr_bob", ORG)).thenReturn(Map.of());
        when(groupStore.membershipsOf("usr_admin", ORG)).thenReturn(Map.of());
        when(groupStore.nameOf("gr_platform")).thenReturn(Optional.of("Platform"));
        when(groupStore.find(ORG, "gr_platform")).thenReturn(Optional.of(new Group("gr_platform", ORG, "Platform",
                null, 1L, null, 1, 0, false)));
        when(groupStore.list(ORG)).thenReturn(List.of(new Group("gr_platform", ORG, "Platform", null, 1L, null, 1, 0, false)));
        when(accounts.findById("usr_alice")).thenReturn(Optional.of(new Accounts.UserAccount("usr_alice", ORG,
                "alice@acme.test", "h", Accounts.ROLE_MEMBER, true, 1L)));
        when(registry.live(anyString())).thenReturn(RunnerRegistry.Live.OFFLINE);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(ConcentusUserDetails who) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(who, null, who.getAuthorities()));
    }

    private RunnerService service(String licenseFixture) throws Exception {
        Path licensed = Files.createDirectories(dir.resolve(licenseFixture == null ? "free" : licenseFixture));
        if (licenseFixture != null) TestLicenses.installFixture(licensed, licenseFixture);
        LicenseService license = TestLicenses.serviceOn(licensed);
        return new RunnerService(store, registry, orgContext, groups, groupStore, accounts, license, audit, "");
    }

    // ------------------------------------------------------------------ seeing

    @Test
    void an_administrator_sees_every_runner_and_a_member_the_ones_they_could_use() throws Exception {
        RunnerService service = service(null);

        signIn(ADMIN);
        assertThat(service.list().runners()).extracting(RunnerView::id)
                .containsExactly("rn_org", "rn_grp", "rn_alice", "rn_bob");

        signIn(ALICE);
        assertThat(service.list().runners()).extracting(RunnerView::id).containsExactly("rn_org", "rn_grp", "rn_alice");
        assertThat(service.list().runners()).filteredOn(v -> v.id().equals("rn_alice"))
                .singleElement().satisfies(v -> {
                    assertThat(v.mine()).isTrue();
                    assertThat(v.ownerEmail()).isEqualTo("alice@acme.test");
                });
        assertThat(service.list().runners()).filteredOn(v -> v.id().equals("rn_grp"))
                .singleElement().extracting(RunnerView::groupName).isEqualTo("Platform");

        signIn(BOB);
        assertThat(service.list().runners()).extracting(RunnerView::id).containsExactly("rn_org", "rn_bob");
    }

    @Test
    void the_listing_says_what_the_caller_may_register() throws Exception {
        RunnerService enterprise = service("enterprise-test.license");

        signIn(ADMIN);
        RunnerService.MayCreate admin = enterprise.list().mayCreate();
        assertThat(admin.organization()).isTrue();
        assertThat(admin.user()).isTrue();
        assertThat(admin.groups()).containsExactly("gr_platform");

        signIn(ALICE);
        RunnerService.MayCreate alice = enterprise.list().mayCreate();
        assertThat(alice.organization()).isFalse();
        assertThat(alice.user()).isTrue();
        assertThat(alice.groups()).containsExactly("gr_platform");

        signIn(BOB);
        assertThat(enterprise.list().mayCreate().groups()).isEmpty();

        // On a Team license groups are withheld, so nobody is offered a group scope.
        signIn(ADMIN);
        assertThat(service("team-test.license").list().mayCreate().groups()).isEmpty();
    }

    // ------------------------------------------------------------------ registering

    @Test
    void registering_answers_the_token_once_and_stores_only_its_hash() throws Exception {
        signIn(ADMIN);
        RunnerService.Created created = service(null).register("  nas  ", "organization", null);

        assertThat(created.token()).startsWith("crn_").hasSize(44);
        assertThat(created.runner().name()).isEqualTo("nas");
        assertThat(created.runner().scope()).isEqualTo("organization");
        verify(store).create(eq(ORG), eq("nas"), eq("organization"), eq(null), eq(null),
                eq(RunnerTokens.hash(created.token())), eq("admin@acme.test"));
        verify(audit).record(eq("runner.registered"), eq("runner"), eq("rn_new"), eq("nas"), any());
    }

    @Test
    void a_member_registers_for_themselves_or_for_a_group_they_manage_and_nothing_wider() throws Exception {
        RunnerService service = service("enterprise-test.license");
        signIn(ALICE);

        assertThat(service.register("laptop", "user", null).runner().userId()).isEqualTo("usr_alice");
        assertThat(service.register("box", "group", "gr_platform").runner().groupId()).isEqualTo("gr_platform");
        assertThatThrownBy(() -> service.register("nas", "organization", null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);

        signIn(BOB);
        assertThatThrownBy(() -> service.register("box", "group", "gr_platform"))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("manager");
    }

    @Test
    void a_group_scope_needs_the_groups_feature_and_a_real_group() throws Exception {
        signIn(ADMIN);
        assertThatThrownBy(() -> service("team-test.license").register("box", "group", "gr_platform"))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("Groups inside an organization");
        assertThatThrownBy(() -> service("enterprise-test.license").register("box", "group", "gr_nope"))
                .hasMessageContaining("No such group");
        assertThatThrownBy(() -> service(null).register("", "user", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(null).register("x", "planet", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ managing

    @Test
    void rename_revoke_and_delete_take_an_admin_the_owner_or_a_manager() throws Exception {
        RunnerService service = service(null);
        when(store.revoke(eq(ORG), anyString(), any(Long.class))).thenReturn(true);

        signIn(BOB);
        assertThatThrownBy(() -> service.rename("rn_alice", "x")).hasMessageContaining("No such runner");
        assertThatThrownBy(() -> service.revoke("rn_org"))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        service.revoke("rn_bob");
        verify(registry).revoke("rn_bob");

        signIn(ALICE);
        service.rename("rn_grp", "platform-nas");
        verify(store).rename(ORG, "rn_grp", "platform-nas");
        assertThatThrownBy(() -> service.delete("rn_org")).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);

        signIn(ADMIN);
        service.delete("rn_bob");
        verify(store).delete(ORG, "rn_bob");
        verify(audit).record(eq("runner.deleted"), eq("runner"), eq("rn_bob"), eq("bob-laptop"), any());
    }

    // ------------------------------------------------------------------ using

    @Test
    void who_may_run_on_what() throws Exception {
        RunnerService service = service(null);

        // Organization-wide: everybody, signed in or not.
        assertThat(service.mayUse(orgWide, Optional.of(BOB), ORG, null)).isTrue();
        assertThat(service.mayUse(orgWide, Optional.empty(), ORG, null)).isTrue();
        assertThat(service.mayUse(orgWide, Optional.empty(), "org_other", null)).isFalse();

        // A group's: its members and administrators; a launch with nobody signed in only when
        // the flow itself belongs to that group.
        signIn(ALICE);
        assertThat(service.mayUse(platform, Optional.of(ALICE), ORG, null)).isTrue();
        signIn(BOB);
        assertThat(service.mayUse(platform, Optional.of(BOB), ORG, null)).isFalse();
        signIn(ADMIN);
        assertThat(service.mayUse(platform, Optional.of(ADMIN), ORG, null)).isTrue();
        SecurityContextHolder.clearContext();
        assertThat(service.mayUse(platform, Optional.empty(), ORG, "gr_platform")).isTrue();
        assertThat(service.mayUse(platform, Optional.empty(), ORG, "gr_other")).isFalse();
        assertThat(service.mayUse(platform, Optional.empty(), ORG, null)).isFalse();

        // Somebody's own: that somebody, and nobody else — not an administrator, not a schedule.
        assertThat(service.mayUse(alicesLaptop, Optional.of(ALICE), ORG, null)).isTrue();
        assertThat(service.mayUse(alicesLaptop, Optional.of(ADMIN), ORG, null)).isFalse();
        assertThat(service.mayUse(alicesLaptop, Optional.empty(), ORG, null)).isFalse();

        // Revoked: nobody.
        Runner revoked = new Runner("rn_x", ORG, "old", Runner.SCOPE_ORGANIZATION, null, null, "h", null, 1L, null, 9L);
        assertThat(service.mayUse(revoked, Optional.of(ADMIN), ORG, null)).isFalse();
    }

    @Test
    void choosing_automatically_stays_here_while_the_cli_is_available_and_goes_to_a_runner_when_it_is_not() throws Exception {
        RunnerService service = service(null);
        when(registry.online("rn_org")).thenReturn(true);
        when(registry.live("rn_org")).thenReturn(new RunnerRegistry.Live(true, 1, 4, "nas", "Linux", "amd64", null,
                null, "subscription", 1L, "https://hub", "nas"));

        assertThat(service.choose(null, ORG, null, true, true)).isNull();
        assertThat(service.choose("", ORG, null, true, true)).isNull();
        // A self-hosted model never goes to a runner, whether or not the CLI is here.
        assertThat(service.choose(null, ORG, null, false, false)).isNull();

        RunnerService.Selection picked = service.choose(null, ORG, null, true, false);
        assertThat(picked.runnerId()).isEqualTo("rn_org");
        assertThat(picked.note()).contains("No Claude login on this server").contains("'nas'");

        when(registry.online("rn_org")).thenReturn(false);
        assertThat(service.choose(null, ORG, null, true, false)).isNull();
    }

    @Test
    void choosing_any_takes_the_least_busy_usable_runner_online_and_says_so_when_there_is_none() throws Exception {
        RunnerService service = service(null);
        signIn(ALICE);
        when(registry.online("rn_org")).thenReturn(true);
        when(registry.online("rn_grp")).thenReturn(true);
        when(registry.online("rn_bob")).thenReturn(true);
        when(registry.live("rn_org")).thenReturn(new RunnerRegistry.Live(true, 3, 4, null, null, null, null, null, null, 1L, null, "nas"));
        when(registry.live("rn_grp")).thenReturn(new RunnerRegistry.Live(true, 0, 4, null, null, null, null, null, null, 1L, null, "platform-box"));
        when(registry.live("rn_bob")).thenReturn(new RunnerRegistry.Live(true, 0, 4, null, null, null, null, null, null, 1L, null, "bob-laptop"));

        // Bob's laptop is idle and online, and still not Alice's to use.
        assertThat(service.choose("any", ORG, null, true, true).runnerId()).isEqualTo("rn_grp");

        when(registry.online("rn_grp")).thenReturn(false);
        when(registry.online("rn_org")).thenReturn(false);
        assertThatThrownBy(() -> service.choose("any", ORG, null, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No runner is online");
        assertThatThrownBy(() -> service.choose("any", ORG, null, false, true))
                .hasMessageContaining("self-hosted model");
    }

    @Test
    void choosing_a_runner_by_id_checks_that_it_exists_is_usable_and_is_online() throws Exception {
        RunnerService service = service(null);
        signIn(BOB);

        assertThatThrownBy(() -> service.choose("rn_gone", ORG, null, true, true)).hasMessageContaining("no longer exists");
        assertThatThrownBy(() -> service.choose("rn_alice", ORG, null, true, true))
                .hasMessageContaining("not yours to use").hasMessageContaining("one person only");
        assertThatThrownBy(() -> service.choose("rn_grp", ORG, null, true, true))
                .hasMessageContaining("the group 'Platform'");
        assertThatThrownBy(() -> service.choose("rn_bob", ORG, null, true, true)).hasMessageContaining("is offline");

        when(registry.online("rn_bob")).thenReturn(true);
        RunnerService.Selection picked = service.choose("rn_bob", ORG, null, true, true);
        assertThat(picked.runnerId()).isEqualTo("rn_bob");
        assertThat(picked.runnerName()).isEqualTo("bob-laptop");
        assertThat(picked.note()).isNull();

        rows.set(3, new Runner("rn_bob", ORG, "bob-laptop", Runner.SCOPE_USER, null, "usr_bob", "h", null, 1L, null, 9L));
        assertThatThrownBy(() -> service.choose("rn_bob", ORG, null, true, true)).hasMessageContaining("was revoked");
    }
}
