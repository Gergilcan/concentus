package com.concentus.auth;

import com.concentus.groups.Group;
import com.concentus.groups.GroupService;
import com.concentus.license.TestLicenses;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/account/session} carries the caller's groups, so the Visible-to select and the
 * Groups screen open without a second round trip. The list is the group service's; this is the
 * endpoint's wiring, in the package the sign-in classes keep to themselves.
 */
class SessionGroupsTest {

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void the_session_carries_the_callers_groups_and_an_empty_list_for_a_controller_built_without_them() throws Exception {
        DataSource ds = TestDatabase.freshDatabase("session_groups");
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AccountStore accounts = new AccountStore(jdbc);
        accounts.init();
        accounts.createOrganization("default", "Concentus");
        Accounts.UserAccount alice = accounts.createUser("default", "alice@x.test", "hash", Accounts.ROLE_MEMBER);
        JdbcTokenRepositoryImpl tokens = new JdbcTokenRepositoryImpl();
        tokens.setDataSource(ds);
        PersistentTokenBasedRememberMeServices rememberMe =
                new PersistentTokenBasedRememberMeServices("test-key", username -> null, tokens);
        DeviceAccountStore devices = new DeviceAccountStore(jdbc, 30);
        GroupService groups = mock(GroupService.class);
        List<Group.Ref> mine = List.of(new Group.Ref("gr_1", "Platform", true), new Group.Ref("gr_2", "Support", false));
        when(groups.mine()).thenReturn(mine);
        AccountController withGroups = new AccountController(
                mock(org.springframework.security.authentication.AuthenticationManager.class),
                accounts, new BCryptPasswordEncoder(), new OrgContext("default"), mock(OidcSignIn.class),
                new BrowserSignIn(rememberMe, devices, 30), devices,
                TestLicenses.serviceOn(Files.createTempDirectory("session-groups")),
                mock(com.concentus.audit.AuditService.class), "Concentus", groups);
        AccountController withoutGroups = new AccountController(
                mock(org.springframework.security.authentication.AuthenticationManager.class),
                accounts, new BCryptPasswordEncoder(), new OrgContext("default"), mock(OidcSignIn.class),
                new BrowserSignIn(rememberMe, devices, 30), devices,
                TestLicenses.serviceOn(Files.createTempDirectory("session-groups-2")),
                mock(com.concentus.audit.AuditService.class), "Concentus");

        // Signed out: no groups key at all, like every other fact about a person.
        assertThat(withGroups.session()).doesNotContainKey("groups");

        ConcentusUserDetails principal = ConcentusUserDetails.of(alice);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        Map<String, Object> session = withGroups.session();
        assertThat(session.get("groups")).isEqualTo(mine);
        assertThat(session.get("signedIn")).isEqualTo(true);
        assertThat(withoutGroups.session().get("groups")).isEqualTo(List.of());
    }
}
