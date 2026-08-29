package com.concentus.auth;

import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Creating the first account on an installation that has none.
 *
 * <p>The one endpoint reachable without a session, so what matters is the window it exists in: it
 * has to work exactly once, on an installation nobody has claimed, and refuse for good afterwards.
 * Everything else about it — the role it grants, the session it opens — follows from that.
 */
class FirstAccountSetupTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Fixture(AccountController controller, AccountStore accounts) {
    }

    /** A fixture on an unlicensed installation — the seat limit of one that gives every other test
     * here exactly the room it needs (one admin, created by {@link #setup}) and no more. */
    private static Fixture on(String databaseName) throws Exception {
        return on(databaseName, Files.createTempDirectory("first-account-setup-test"));
    }

    /** As {@link #on(String)}, but licensed from whatever is (or isn't) installed in {@code licenseDir}
     * — so a test that needs more than one seat can drop the enterprise fixture there first. */
    private static Fixture on(String databaseName, Path licenseDir) throws Exception {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AccountStore accounts = new AccountStore(jdbc);
        // @PostConstruct outside a container: the store probes its tables to decide whether it is
        // usable at all, and without that probe it correctly reports itself unavailable.
        accounts.init();
        OrgContext orgContext = new OrgContext("default");

        JdbcTokenRepositoryImpl tokens = new JdbcTokenRepositoryImpl();
        tokens.setDataSource(ds);
        PersistentTokenBasedRememberMeServices rememberMe =
                new PersistentTokenBasedRememberMeServices("test-key", username -> null, tokens);

        LicenseService licenseService = TestLicenses.serviceOn(licenseDir);
        AccountController controller = new AccountController(
                mock(org.springframework.security.authentication.AuthenticationManager.class),
                accounts, ENCODER, orgContext, mock(OidcSignIn.class), rememberMe,
                new DeviceAccountStore(jdbc, 30), licenseService,
                mock(com.concentus.audit.AuditService.class), 30, "Concentus");
        return new Fixture(controller, accounts);
    }

    private static Map<String, Object> setup(AccountController controller, String email,
                                             String password) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            return controller.setup(new AccountController.SetupRequest(email, password),
                    request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Signs the first admin in and, unlike {@link #setup}, leaves the security context populated
     * — for tests below that need an authenticated admin in place across a {@link
     * AccountController#createMember} call. The caller owns clearing it afterwards.
     */
    private static void signInFirstAdmin(AccountController controller) {
        controller.setup(new AccountController.SetupRequest("admin@tecnovent.com",
                "a-long-enough-password"), new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void the_first_account_administers_the_installation() throws Exception {
        Fixture f = on("setup_1");

        Map<String, Object> created = setup(f.controller(), "gerard@tecnovent.com",
                "a-long-enough-password");

        assertThat(created).containsEntry("email", "gerard@tecnovent.com")
                .containsEntry("role", Accounts.ROLE_ADMIN);
        // An installation with nobody in it has nobody to promote whoever arrives, so the account
        // that claims it is the one that can.
        assertThat(f.accounts().findByEmail("gerard@tecnovent.com").orElseThrow().isAdmin()).isTrue();
    }

    // The whole reason this endpoint can be left open: it stops doing anything the moment there is
    // something to protect.
    @Test
    void it_refuses_once_an_account_exists() throws Exception {
        Fixture f = on("setup_2");
        setup(f.controller(), "first@tecnovent.com", "a-long-enough-password");

        assertThatThrownBy(() -> setup(f.controller(), "second@tecnovent.com", "another-long-one"))
                .hasMessageContaining("already has an account");
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // Somebody who chose a password seconds ago has already proved what a login form would ask
    // them to prove, so the setup screen hands them the app rather than a sign-in screen.
    @Test
    void whoever_sets_it_up_is_signed_in_and_this_browser_can_return_to_them() throws Exception {
        Fixture f = on("setup_3");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            f.controller().setup(new AccountController.SetupRequest("gerard@tecnovent.com",
                    "a-long-enough-password"), request, response);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(request.getSession(false)).isNotNull();
            Cookie device = response.getCookie(DeviceCookie.NAME);
            assertThat(device).isNotNull();
            // Not readable by script: the accounts a browser may switch back to are kept on the
            // server, and this names them.
            assertThat(device.isHttpOnly()).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void a_password_too_short_to_be_re_set_later_is_refused_now() throws Exception {
        Fixture f = on("setup_4");

        assertThatThrownBy(() -> setup(f.controller(), "gerard@tecnovent.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.accounts().countUsers()).isZero();
    }

    @Test
    void something_that_is_not_an_address_is_refused() throws Exception {
        Fixture f = on("setup_5");

        assertThatThrownBy(() -> setup(f.controller(), "gerard", "a-long-enough-password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The session endpoint is what puts the setup screen on screen, so it has to say so. */
    @Test
    void the_session_says_setup_is_required_until_it_is_not() throws Exception {
        Fixture f = on("setup_6");

        assertThat(f.controller().session()).containsEntry("setupRequired", true);

        setup(f.controller(), "gerard@tecnovent.com", "a-long-enough-password");

        assertThat(f.controller().session()).containsEntry("setupRequired", false);
    }

    // The seat gate: how many members createMember will allow beyond the admin setup creates,
    // which follows the license installed in the fixture's data directory rather than anything
    // the request itself carries.

    @Test
    void enterprise_fixture_allows_members_below_its_seat_count() throws Exception {
        Path licenseDir = Files.createTempDirectory("first-account-setup-test-enterprise");
        TestLicenses.installFixture(licenseDir, "enterprise-test.license");
        Fixture f = on("members_below_limit", licenseDir);
        try {
            signInFirstAdmin(f.controller());
            // Three more, for four existing (the admin plus these) — still below the five-seat
            // limit the fixture grants, so the fifth below is allowed too.
            for (int i = 1; i <= 3; i++) {
                f.controller().createMember(new AccountController.NewMemberRequest(
                        "member" + i + "@tecnovent.com", "a-long-enough-password", null));
            }
            Accounts.UserAccount fifth = f.controller().createMember(new AccountController.NewMemberRequest(
                    "member4@tecnovent.com", "a-long-enough-password", null));

            assertThat(fifth.email()).isEqualTo("member4@tecnovent.com");
            assertThat(f.accounts().countUsers()).isEqualTo(5);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void the_seat_limit_refuses_one_more_and_names_it() throws Exception {
        Path licenseDir = Files.createTempDirectory("first-account-setup-test-enterprise-at-limit");
        TestLicenses.installFixture(licenseDir, "enterprise-test.license");
        Fixture f = on("members_at_limit", licenseDir);
        try {
            signInFirstAdmin(f.controller());
            // Four more, for five existing — exactly the fixture's seat count.
            for (int i = 1; i <= 4; i++) {
                f.controller().createMember(new AccountController.NewMemberRequest(
                        "member" + i + "@tecnovent.com", "a-long-enough-password", null));
            }
            assertThat(f.accounts().countUsers()).isEqualTo(5);

            assertThatThrownBy(() -> f.controller().createMember(new AccountController.NewMemberRequest(
                    "one-too-many@tecnovent.com", "a-long-enough-password", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5");
            assertThat(f.accounts().countUsers()).isEqualTo(5);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // Without a license, the seat limit is one — the admin who just claimed the installation —
    // which is exactly what an unenforced gate would have let a second signup slip past.
    @Test
    void without_a_license_the_second_member_is_refused() throws Exception {
        Fixture f = on("members_no_license");
        try {
            signInFirstAdmin(f.controller());

            assertThatThrownBy(() -> f.controller().createMember(new AccountController.NewMemberRequest(
                    "second@tecnovent.com", "a-long-enough-password", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1");
            assertThat(f.accounts().countUsers()).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
