package com.concentus.auth;

import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Several organizations on one deployment: the gate on making a second one, who may put people
 * into which, and what switching does to the session.
 */
class OrganizationControllerTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PASSWORD = "a-long-enough-password";

    private record Fixture(OrganizationController controller, AccountStore accounts, JdbcTemplate jdbc) {
    }

    /** An unlicensed installation: one seat, no Enterprise features. */
    private static Fixture on(String databaseName) throws Exception {
        return on(databaseName, Files.createTempDirectory("organization-controller-test"));
    }

    private static Fixture on(String databaseName, Path licenseDir) throws Exception {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AccountStore accounts = new AccountStore(jdbc);
        accounts.init();
        accounts.createOrganization("default", "Concentus");
        LicenseService licenseService = TestLicenses.serviceOn(licenseDir);
        OrganizationController controller = new OrganizationController(
                accounts, new OrgContext("default"), licenseService, ENCODER);
        return new Fixture(controller, accounts, jdbc);
    }

    private static Path licensed(String fixture) throws Exception {
        Path dir = Files.createTempDirectory("organization-controller-test-" + fixture);
        TestLicenses.installFixture(dir, fixture);
        return dir;
    }

    /** Puts an account into the security context, as the filter chain would after sign-in. */
    private static void signIn(Accounts.UserAccount account) {
        ConcentusUserDetails principal = ConcentusUserDetails.of(account);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static Accounts.UserAccount admin(Fixture f) {
        Accounts.UserAccount admin = f.accounts().createUser("default", "admin@tecnovent.com",
                ENCODER.encode(PASSWORD), Accounts.ROLE_ADMIN);
        signIn(admin);
        return admin;
    }

    // ---- the gate ----

    @Test
    void a_second_organization_is_refused_without_a_license_with_the_feature_sentence() throws Exception {
        Fixture f = on("orgs_gate_unlicensed");
        admin(f);

        assertThatThrownBy(() -> f.controller().create(new OrganizationController.NameRequest("Filial")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(Feature.MULTI_ORG.label + " is an Enterprise feature");
        assertThat(f.accounts().countOrganizations()).isEqualTo(1);
    }

    @Test
    void a_second_organization_is_refused_on_the_team_tier() throws Exception {
        Fixture f = on("orgs_gate_team", licensed("team-test.license"));
        admin(f);

        assertThatThrownBy(() -> f.controller().create(new OrganizationController.NameRequest("Filial")))
                .hasMessageContaining("is an Enterprise feature")
                .hasMessageContaining("Team license");
        assertThat(f.accounts().countOrganizations()).isEqualTo(1);
    }

    @Test
    void an_enterprise_license_allows_a_second_organization_and_its_creator_administers_it() throws Exception {
        Fixture f = on("orgs_gate_enterprise", licensed("enterprise-test.license"));
        Accounts.UserAccount admin = admin(f);

        OrganizationController.OrganizationView created =
                f.controller().create(new OrganizationController.NameRequest("  Filial Norte "));

        assertThat(created.name()).isEqualTo("Filial Norte");
        assertThat(created.role()).isEqualTo(Accounts.ROLE_ADMIN);
        assertThat(created.current()).isFalse();
        assertThat(f.accounts().membership(admin.id(), created.id()).orElseThrow().role())
                .isEqualTo(Accounts.ROLE_ADMIN);
        // Still working in the first one: creating is not switching.
        assertThat(f.accounts().findById(admin.id()).orElseThrow().organizationId()).isEqualTo("default");
        assertThat(f.controller().mine()).extracting(OrganizationController.OrganizationView::name)
                .containsExactly("Concentus", "Filial Norte");
    }

    @Test
    void creating_takes_an_admin_even_with_the_license() throws Exception {
        Fixture f = on("orgs_gate_member", licensed("enterprise-test.license"));
        signIn(f.accounts().createUser("default", "member@tecnovent.com", ENCODER.encode(PASSWORD),
                Accounts.ROLE_MEMBER));

        assertThatThrownBy(() -> f.controller().create(new OrganizationController.NameRequest("Filial")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    // ---- renaming: every tier ----

    @Test
    void renaming_the_organization_you_have_needs_no_license() throws Exception {
        Fixture f = on("orgs_rename");
        admin(f);

        OrganizationController.OrganizationView renamed =
                f.controller().rename("default", new OrganizationController.NameRequest("Tecnovent"));

        assertThat(renamed.name()).isEqualTo("Tecnovent");
        assertThat(f.accounts().findOrganization("default").orElseThrow().name()).isEqualTo("Tecnovent");
    }

    @Test
    void a_blank_name_is_refused() throws Exception {
        Fixture f = on("orgs_rename_blank");
        admin(f);

        assertThatThrownBy(() -> f.controller().rename("default", new OrganizationController.NameRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- membership: by membership in THAT organization ----

    @Test
    void an_existing_account_joins_another_organization_without_taking_a_seat() throws Exception {
        Fixture f = on("orgs_invite_existing", licensed("enterprise-test.license"));
        Accounts.UserAccount admin = admin(f);
        f.accounts().createUser("default", "viewer@tecnovent.com", ENCODER.encode(PASSWORD), Accounts.ROLE_VIEWER);
        String other = f.controller().create(new OrganizationController.NameRequest("Filial")).id();
        long seatsBefore = f.accounts().countUsers();

        Accounts.UserAccount joined = f.controller().invite(other,
                new OrganizationController.InviteRequest("viewer@tecnovent.com", "", "MEMBER"));

        assertThat(joined.organizationId()).isEqualTo(other);
        assertThat(joined.role()).isEqualTo(Accounts.ROLE_MEMBER);
        assertThat(f.accounts().countUsers()).isEqualTo(seatsBefore);
        // Still a Viewer at home: roles are per organization.
        assertThat(f.accounts().membership(joined.id(), "default").orElseThrow().role())
                .isEqualTo(Accounts.ROLE_VIEWER);
        assertThat(f.controller().members(other)).extracting(Accounts.UserAccount::email)
                .containsExactly(admin.email(), "viewer@tecnovent.com");
    }

    @Test
    void a_new_address_needs_a_password_and_is_created_in_that_organization() throws Exception {
        Fixture f = on("orgs_invite_new", licensed("enterprise-test.license"));
        admin(f);
        String other = f.controller().create(new OrganizationController.NameRequest("Filial")).id();

        assertThatThrownBy(() -> f.controller().invite(other,
                new OrganizationController.InviteRequest("new@tecnovent.com", "", "VIEWER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No account has that address yet");

        Accounts.UserAccount created = f.controller().invite(other,
                new OrganizationController.InviteRequest("new@tecnovent.com", PASSWORD, "VIEWER"));

        assertThat(created.organizationId()).isEqualTo(other);
        assertThat(f.accounts().membershipsOf(created.id())).hasSize(1);
        assertThat(f.accounts().membership(created.id(), "default")).isEmpty();
    }

    @Test
    void inviting_into_an_organization_takes_an_admin_of_that_organization() throws Exception {
        Fixture f = on("orgs_invite_wrong_admin", licensed("enterprise-test.license"));
        admin(f);
        String other = f.controller().create(new OrganizationController.NameRequest("Filial")).id();
        // An admin at home, a Viewer in the other one.
        Accounts.UserAccount second = f.accounts().createUser("default", "second@tecnovent.com",
                ENCODER.encode(PASSWORD), Accounts.ROLE_ADMIN);
        f.accounts().addMembership(second.id(), other, Accounts.ROLE_VIEWER);
        signIn(second);

        assertThatThrownBy(() -> f.controller().invite(other,
                new OrganizationController.InviteRequest("x@tecnovent.com", PASSWORD, "VIEWER")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> f.controller().members(other))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        // And with no membership at all it is the same answer, not a different one.
        Accounts.UserAccount outsider = f.accounts().createUser("default", "out@tecnovent.com",
                ENCODER.encode(PASSWORD), Accounts.ROLE_ADMIN);
        signIn(outsider);
        assertThatThrownBy(() -> f.controller().rename(other, new OrganizationController.NameRequest("Mine")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    // ---- switching ----

    @Test
    void switching_rewrites_the_current_organization_and_the_session_principal() throws Exception {
        Fixture f = on("orgs_switch", licensed("enterprise-test.license"));
        Accounts.UserAccount admin = admin(f);
        String other = f.controller().create(new OrganizationController.NameRequest("Filial")).id();
        f.accounts().addMembership(admin.id(), other, Accounts.ROLE_VIEWER);

        Map<String, Object> switched = f.controller().switchTo(other,
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(switched).containsEntry("organizationId", other)
                .containsEntry("organizationName", "Filial")
                .containsEntry("role", Accounts.ROLE_VIEWER);
        Accounts.UserAccount row = f.accounts().findById(admin.id()).orElseThrow();
        assertThat(row.organizationId()).isEqualTo(other);
        assertThat(row.role()).isEqualTo(Accounts.ROLE_VIEWER);
        // What every store call after this request reads.
        assertThat(new OrgContext("default").requireOrganizationId()).isEqualTo(other);
        assertThat(new OrgContext("default").isAdmin()).isFalse();
        List<OrganizationController.OrganizationView> mine = f.controller().mine();
        assertThat(mine).filteredOn(OrganizationController.OrganizationView::current)
                .extracting(OrganizationController.OrganizationView::id).containsExactly(other);
        // Membership at home is untouched; switching back restores the admin role there.
        f.controller().switchTo("default", new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(f.accounts().findById(admin.id()).orElseThrow().role()).isEqualTo(Accounts.ROLE_ADMIN);
    }

    @Test
    void switching_into_an_organization_you_are_not_in_answers_not_found_and_changes_nothing() throws Exception {
        Fixture f = on("orgs_switch_outsider", licensed("enterprise-test.license"));
        Accounts.UserAccount admin = admin(f);
        String other = f.accounts().createOrganization(null, "Somebody else's").id();

        assertThatThrownBy(() -> f.controller().switchTo(other,
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a member");
        assertThat(f.accounts().findById(admin.id()).orElseThrow().organizationId()).isEqualTo("default");
    }

    /** The Members screen counts seats across the deployment, not per organization. */
    @Test
    void seats_are_distinct_accounts_on_the_deployment() throws Exception {
        Fixture f = on("orgs_seats", licensed("enterprise-test.license"));  // five seats
        admin(f);
        String other = f.controller().create(new OrganizationController.NameRequest("Filial")).id();
        for (int i = 2; i <= 5; i++) {
            f.controller().invite(other, new OrganizationController.InviteRequest(
                    "m" + i + "@tecnovent.com", PASSWORD, "VIEWER"));
        }

        assertThatThrownBy(() -> f.controller().invite(other, new OrganizationController.InviteRequest(
                "m6@tecnovent.com", PASSWORD, "VIEWER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to 5 members");
        // An existing account still joins: it is already one of the five.
        f.controller().invite("default", new OrganizationController.InviteRequest("m5@tecnovent.com", "", "VIEWER"));
        assertThat(f.accounts().countUsers()).isEqualTo(5);
    }
}
