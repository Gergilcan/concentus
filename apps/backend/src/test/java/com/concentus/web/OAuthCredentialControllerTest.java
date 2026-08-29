package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.llm.McpOAuth;
import com.concentus.llm.OAuthCredentialFlow;
import com.concentus.llm.OAuthCredentialStore;
import com.concentus.secrets.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The HTTP surface of an OAuth credential: create it, see whether it is connected, sign in.
 *
 * <p>The rule the rest of {@link CredentialController} enforces by shape — values go in and never
 * come back — has to be enforced by hand here, because this credential's value is a document with
 * a client secret and a refresh token in it. Hence a status endpoint that reports about the grant
 * without reporting the grant.
 */
class OAuthCredentialControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORG = "local";

    private final CredentialStore credentials = mock(CredentialStore.class);
    private OAuthCredentialStore store;
    private OAuthCredentialController controller;

    @org.junit.jupiter.api.AfterEach
    void clearRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @BeforeEach
    void setUp() {
        // The status and start endpoints derive the callback address from the request, so the
        // handlers need one even when called directly.
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        McpOAuth oauth = new McpOAuth(MAPPER);
        store = new OAuthCredentialStore(credentials, oauth, MAPPER);
        OrgContext org = mock(OrgContext.class);
        when(org.currentOrganizationId()).thenReturn(ORG);
        controller = new OAuthCredentialController(credentials, store,
                new OAuthCredentialFlow(store, oauth, "http://127.0.0.1:8734"), org);
    }

    @Test
    void creating_one_stores_the_configuration_and_no_tokens_yet() {
        CredentialStore.Credential created = mock(CredentialStore.Credential.class);
        when(created.id()).thenReturn("cred_new");
        when(credentials.create(eq(ORG), eq("Google Ads"), eq(OAuthCredentialStore.KIND), anyString()))
                .thenReturn(created);

        controller.create(new OAuthCredentialController.NewOAuthCredential("Google Ads",
                "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                "client-1", "secret-1", "https://www.googleapis.com/auth/adwords",
                "access_type=offline&prompt=consent"));

        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(credentials)
                .create(eq(ORG), eq("Google Ads"), eq(OAuthCredentialStore.KIND), secret.capture());
        assertThat(store.parse(secret.getValue())).hasValueSatisfying(grant -> {
            assertThat(grant.clientId()).isEqualTo("client-1");
            assertThat(grant.authParams()).isEqualTo("access_type=offline&prompt=consent");
            assertThat(grant.refreshToken()).isNull();
        });
    }

    @Test
    void the_status_says_enough_to_act_on_and_nothing_secret() {
        stored("access-1", "refresh-1", Instant.parse("2026-08-19T10:00:00Z"));

        Map<String, Object> status = controller.status("cred_1");

        assertThat(status).containsEntry("connected", true)
                .containsEntry("clientId", "client-1")
                .containsEntry("scope", "https://www.googleapis.com/auth/adwords")
                .containsEntry("hasRefreshToken", true)
                // The address to paste into the provider's console, which is where every one of
                // these sign-ins fails the first time.
                .containsEntry("redirectUri", "http://127.0.0.1:8734/api/credentials/oauth/callback");
        assertThat(status).doesNotContainKeys("clientSecret", "refreshToken", "accessToken");
    }

    @Test
    void a_credential_signed_in_without_a_refresh_token_does_not_report_itself_as_settled() {
        // It works for an hour and then stops. Reporting it as connected-and-done is how that
        // becomes a mystery at 3am instead of a warning today.
        stored("access-1", null, Instant.parse("2026-08-19T10:00:00Z"));

        assertThat(controller.status("cred_1")).containsEntry("hasRefreshToken", false);
    }

    @Test
    void starting_a_sign_in_hands_back_the_providers_url() {
        stored(null, null, null);

        Map<String, Object> started = controller.start("cred_1");

        assertThat(started).containsEntry("ok", true);
        assertThat((String) started.get("authorizationUrl"))
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("access_type=offline");
    }

    @Test
    void the_callback_page_says_what_happened_rather_than_rendering_blank() {
        var response = controller.callback("code-1", "unknown-state", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("no longer valid");
    }

    private void stored(String accessToken, String refreshToken, Instant expiresAt) {
        String json = """
                {"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth",
                 "tokenUrl":"https://oauth2.googleapis.com/token",
                 "clientId":"client-1","clientSecret":"secret-1",
                 "scope":"https://www.googleapis.com/auth/adwords",
                 "authParams":"access_type=offline&prompt=consent",
                 "accessToken":%s,"refreshToken":%s,"expiresAt":%s}"""
                .formatted(quote(accessToken), quote(refreshToken),
                        expiresAt == null ? "null" : "\"" + expiresAt + "\"");
        CredentialStore.Credential record = mock(CredentialStore.Credential.class);
        when(record.id()).thenReturn("cred_1");
        when(record.kind()).thenReturn(OAuthCredentialStore.KIND);
        when(credentials.list(ORG)).thenReturn(List.of(record));
        when(credentials.reveal(ORG, "cred_1")).thenReturn(Optional.of(json));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
