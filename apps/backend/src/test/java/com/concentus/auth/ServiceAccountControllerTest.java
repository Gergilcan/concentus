package com.concentus.auth;

import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Minting tokens: the one-time answer, the role ceiling, and the Team cap.
 *
 * <p>The store is mocked — the SQL has its own test against a real database — and the license is
 * real, read from the committed fixtures, because the cap IS the license: a Team deployment holds
 * two, the third is refused with the feature's own sentence, and Enterprise and a free installation
 * are never counted.
 */
class ServiceAccountControllerTest {

    @TempDir
    Path dir;

    private final ServiceAccountStore store = mock(ServiceAccountStore.class);
    private final OrgContext orgContext = new OrgContext("default");

    @BeforeEach
    void signedInAsAnAdmin() {
        ConcentusUserDetails admin = new ConcentusUserDetails("usr_1", "org_acme", "gerard@tecnovent.com",
                "hash", Accounts.ROLE_ADMIN, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        when(store.create(anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(inv ->
                new ServiceAccount("sa_new", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4), 1L, null, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ServiceAccountController controller(String fixture) throws Exception {
        Path licensed = Files.createDirectories(dir.resolve(fixture == null ? "free" : fixture));
        if (fixture != null) TestLicenses.installFixture(licensed, fixture);
        LicenseService license = TestLicenses.serviceOn(licensed);
        return new ServiceAccountController(store, orgContext, license);
    }

    @Test
    void minting_answers_the_token_once_and_stores_only_its_hash() throws Exception {
        ServiceAccountController.Created created = controller(null)
                .create(new ServiceAccountController.CreateRequest("nightly-report", "operator"));

        assertThat(created.token()).startsWith("csa_").hasSize(44);
        assertThat(created.account().role()).isEqualTo("OPERATOR");
        assertThat(created.account().organizationId()).isEqualTo("org_acme");
        assertThat(created.account().createdBy()).isEqualTo("gerard@tecnovent.com");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).create(eq("org_acme"), eq("nightly-report"), eq("OPERATOR"), hash.capture(),
                eq("gerard@tecnovent.com"));
        assertThat(hash.getValue()).isEqualTo(ServiceAccount.hash(created.token())).doesNotContain(created.token());
    }

    // Running flows is what a machine is usually for.
    @Test
    void the_default_role_is_operator() throws Exception {
        ServiceAccountController.Created created = controller(null)
                .create(new ServiceAccountController.CreateRequest("ci", null));

        assertThat(created.account().role()).isEqualTo(Accounts.ROLE_OPERATOR);
    }

    // A token that could administer could mint more tokens — one leak would become any number.
    @Test
    void an_admin_token_is_refused_by_name_rather_than_quietly_downgraded() throws Exception {
        ServiceAccountController c = controller(null);

        assertThatThrownBy(() -> c.create(new ServiceAccountController.CreateRequest("god", "ADMIN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be an ADMIN")
                .hasMessageContaining("MEMBER");
        assertThatThrownBy(() -> c.create(new ServiceAccountController.CreateRequest("x", "editor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIEWER, OPERATOR, MEMBER");
        verify(store, never()).create(anyString(), anyString(), anyString(), anyString(), any());
        assertThat(ServiceAccountController.allowedRoles()).containsExactly("VIEWER", "OPERATOR", "MEMBER");
    }

    @Test
    void a_name_is_required() throws Exception {
        ServiceAccountController c = controller(null);

        assertThatThrownBy(() -> c.create(new ServiceAccountController.CreateRequest("  ", "viewer")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        assertThatThrownBy(() -> c.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // The cap: two on Team, the third refused with the feature's sentence and the count.
    @Test
    void a_team_deployment_holds_two_and_the_third_is_refused_with_the_refusal_and_the_count() throws Exception {
        ServiceAccountController c = controller("team-test.license");
        when(store.countActive("org_acme")).thenReturn(0L, 1L, 2L);

        c.create(new ServiceAccountController.CreateRequest("one", "viewer"));
        c.create(new ServiceAccountController.CreateRequest("two", "viewer"));

        assertThatThrownBy(() -> c.create(new ServiceAccountController.CreateRequest("three", "viewer")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason())
                            .contains(Feature.SERVICE_ACCOUNTS.label + " is an Enterprise feature")
                            .contains("2 of " + Feature.TEAM_SERVICE_ACCOUNTS)
                            .contains("revoke one");
                });
        assertThat(Feature.TEAM_SERVICE_ACCOUNTS).isEqualTo(2);
    }

    // Revoked tokens do not count: the store counts working ones, and the listing says so.
    @Test
    void the_listing_carries_the_cap_and_the_refusal_so_the_screen_can_disable_the_button_honestly() throws Exception {
        ServiceAccountController team = controller("team-test.license");
        when(store.list("org_acme")).thenReturn(List.of());
        when(store.countActive("org_acme")).thenReturn(1L);
        assertThat(team.list().limit()).isEqualTo(2);
        assertThat(team.list().refusal()).isNull();

        when(store.countActive("org_acme")).thenReturn(2L);
        assertThat(team.list().active()).isEqualTo(2);
        assertThat(team.list().refusal()).contains("Enterprise feature").contains("2 of 2");
    }

    @Test
    void enterprise_and_a_free_installation_are_never_capped() throws Exception {
        when(store.countActive("org_acme")).thenReturn(50L);
        when(store.list("org_acme")).thenReturn(List.of());

        for (String fixture : new String[] {"enterprise-test.license", null}) {
            ServiceAccountController c = controller(fixture);
            assertThat(c.list().limit()).isNull();
            assertThat(c.list().refusal()).isNull();
            assertThat(c.create(new ServiceAccountController.CreateRequest("n", "member")).token()).isNotBlank();
        }
    }

    @Test
    void only_an_admin_may_do_any_of_this() throws Exception {
        ConcentusUserDetails member = new ConcentusUserDetails("usr_2", "org_acme", "m@tecnovent.com",
                "hash", Accounts.ROLE_MEMBER, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(member, null, member.getAuthorities()));
        ServiceAccountController c = controller(null);

        assertThatThrownBy(c::list).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> c.create(new ServiceAccountController.CreateRequest("x", "viewer")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> c.revoke("sa_1")).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> c.rename("sa_1", new ServiceAccountController.RenameRequest("y")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    // And a service account is a MEMBER at most, so a token can never reach here — the ceiling
    // and the admin gate together are what stop a leaked token from minting its successors.
    @Test
    void a_service_account_itself_cannot_mint_tokens() throws Exception {
        ConcentusUserDetails machine = new ServiceAccount("sa_9", "org_acme", "ci", "ADMIN", "h", null, 0L, null, null)
                .principal();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(machine, null, machine.getAuthorities()));

        assertThatThrownBy(() -> controller(null).create(new ServiceAccountController.CreateRequest("more", "viewer")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    @Test
    void revoking_stamps_the_row_and_revoking_again_is_harmless() throws Exception {
        ServiceAccount live = new ServiceAccount("sa_1", "org_acme", "ci", "OPERATOR", "h", null, 0L, null, null);
        ServiceAccount gone = new ServiceAccount("sa_1", "org_acme", "ci", "OPERATOR", "h", null, 0L, null, 9L);
        when(store.find("sa_1", "org_acme")).thenReturn(Optional.of(live), Optional.of(gone), Optional.of(gone),
                Optional.of(gone));
        ServiceAccountController c = controller(null);

        assertThat(c.revoke("sa_1").revokedAt()).isEqualTo(9L);
        assertThat(c.revoke("sa_1").revokedAt()).isEqualTo(9L);
        verify(store).revoke(eq("sa_1"), eq("org_acme"), org.mockito.ArgumentMatchers.anyLong());
    }

    // The organization comes from the session, so another tenant's id is simply not found.
    @Test
    void an_id_from_another_organization_is_not_found() throws Exception {
        when(store.find("sa_other", "org_acme")).thenReturn(Optional.empty());
        when(store.rename("sa_other", "org_acme", "x")).thenReturn(false);
        ServiceAccountController c = controller(null);

        assertThatThrownBy(() -> c.revoke("sa_other"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> c.rename("sa_other", new ServiceAccountController.RenameRequest("x")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
