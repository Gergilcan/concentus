package com.concentus.auth;

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

    private static Fixture on(String databaseName) {
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

        AccountController controller = new AccountController(
                mock(org.springframework.security.authentication.AuthenticationManager.class),
                accounts, ENCODER, orgContext, mock(OidcSignIn.class), rememberMe,
                new DeviceAccountStore(jdbc, 30), 30, "Concentus");
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

    @Test
    void the_first_account_administers_the_installation() {
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
    void it_refuses_once_an_account_exists() {
        Fixture f = on("setup_2");
        setup(f.controller(), "first@tecnovent.com", "a-long-enough-password");

        assertThatThrownBy(() -> setup(f.controller(), "second@tecnovent.com", "another-long-one"))
                .hasMessageContaining("already has an account");
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // Somebody who chose a password seconds ago has already proved what a login form would ask
    // them to prove, so the setup screen hands them the app rather than a sign-in screen.
    @Test
    void whoever_sets_it_up_is_signed_in_and_this_browser_can_return_to_them() {
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
    void a_password_too_short_to_be_re_set_later_is_refused_now() {
        Fixture f = on("setup_4");

        assertThatThrownBy(() -> setup(f.controller(), "gerard@tecnovent.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.accounts().countUsers()).isZero();
    }

    @Test
    void something_that_is_not_an_address_is_refused() {
        Fixture f = on("setup_5");

        assertThatThrownBy(() -> setup(f.controller(), "gerard", "a-long-enough-password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The session endpoint is what puts the setup screen on screen, so it has to say so. */
    @Test
    void the_session_says_setup_is_required_until_it_is_not() {
        Fixture f = on("setup_6");

        assertThat(f.controller().session()).containsEntry("setupRequired", true);

        setup(f.controller(), "gerard@tecnovent.com", "a-long-enough-password");

        assertThat(f.controller().session()).containsEntry("setupRequired", false);
    }
}
