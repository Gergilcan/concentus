package com.concentus.llm;

import com.concentus.secrets.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signing in to a provider that is not an MCP server: Google, and anything else whose endpoints a
 * person types in rather than the app discovering them.
 *
 * <p>The sign-in happens in the system browser and comes back to this backend, which is what makes
 * it work where a vendor's auth CLI cannot — in a container the CLI has no browser and its
 * loopback listener is unreachable, while this callback lands on the same address the browser
 * already used to reach the app.
 */
class OAuthCredentialFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORG = "local";
    private static final String ID = "cred_oauth1";
    private static final String BASE = "http://127.0.0.1:8734";

    private final CredentialStore credentials = mock(CredentialStore.class);
    /** A real client — PKCE, state and URL building are the behaviour under test — with only the
     *  network call to the provider's token endpoint stubbed. */
    private final McpOAuth oauth = org.mockito.Mockito.spy(new McpOAuth(MAPPER));
    private OAuthCredentialStore store;
    private OAuthCredentialFlow flow;

    @BeforeEach
    void setUp() {
        store = new OAuthCredentialStore(credentials, oauth, MAPPER);
        flow = new OAuthCredentialFlow(store, oauth, "");

        String json = """
                {"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth",
                 "tokenUrl":"https://oauth2.googleapis.com/token",
                 "clientId":"client-1.apps.googleusercontent.com","clientSecret":"secret-1",
                 "scope":"https://www.googleapis.com/auth/adwords",
                 "authParams":"access_type=offline&prompt=consent",
                 "accessToken":null,"refreshToken":null,"expiresAt":null}""";
        CredentialStore.Credential record = mock(CredentialStore.Credential.class);
        when(record.id()).thenReturn(ID);
        when(record.kind()).thenReturn(OAuthCredentialStore.KIND);
        when(credentials.list(ORG)).thenReturn(List.of(record));
        when(credentials.reveal(ORG, ID)).thenReturn(Optional.of(json));
    }

    @Test
    void the_browser_is_sent_to_the_provider_with_everything_the_exchange_will_need() {
        OAuthCredentialFlow.StartResult started = flow.start(ORG, ID, BASE);

        assertThat(started.ok()).isTrue();
        Map<String, String> query = queryOf(started.authorizationUrl());
        assertThat(started.authorizationUrl()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", "client-1.apps.googleusercontent.com")
                .containsEntry("redirect_uri", BASE + "/api/credentials/oauth/callback")
                .containsEntry("scope", "https://www.googleapis.com/auth/adwords")
                .containsEntry("code_challenge_method", "S256")
                // Google returns a refresh token only when asked, and only the first time unless
                // consent is forced. Without both, the credential signs in and is useless tomorrow.
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent");
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("code_challenge")).isNotBlank();
    }

    @Test
    void the_grant_is_saved_when_the_browser_comes_back() {
        org.mockito.Mockito.doReturn(new McpOAuth.Tokens("access-1", "refresh-1", Instant.parse("2026-08-19T10:00:00Z")))
                .when(oauth).exchangeCode(any(), any(), eq("code-1"), anyString(), anyString());

        String state = queryOf(flow.start(ORG, ID, BASE).authorizationUrl()).get("state");
        OAuthCredentialFlow.CallbackOutcome outcome = flow.finish("code-1", state, null, null);

        assertThat(outcome.connected()).isTrue();
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(credentials).updateSecret(eq(ORG), eq(ID), saved.capture());
        assertThat(saved.getValue())
                .contains("\"refreshToken\":\"refresh-1\"")
                .contains("\"accessToken\":\"access-1\"")
                // The client half survives the sign-in; losing it would break every later refresh.
                .contains("\"clientSecret\":\"secret-1\"");
    }

    @Test
    void a_callback_with_a_state_nobody_started_is_refused() {
        OAuthCredentialFlow.CallbackOutcome outcome = flow.finish("code-1", "not-a-state", null, null);

        assertThat(outcome.connected()).isFalse();
        verify(credentials, never()).updateSecret(anyString(), anyString(), anyString());
    }

    @Test
    void the_same_state_cannot_be_used_twice() {
        org.mockito.Mockito.doReturn(new McpOAuth.Tokens("access-1", "refresh-1", null))
                .when(oauth).exchangeCode(any(), any(), anyString(), anyString(), anyString());
        String state = queryOf(flow.start(ORG, ID, BASE).authorizationUrl()).get("state");

        assertThat(flow.finish("code-1", state, null, null).connected()).isTrue();
        assertThat(flow.finish("code-1", state, null, null).connected()).isFalse();
    }

    @Test
    void a_provider_that_refuses_says_so_instead_of_reporting_a_connection() {
        String state = queryOf(flow.start(ORG, ID, BASE).authorizationUrl()).get("state");

        OAuthCredentialFlow.CallbackOutcome outcome =
                flow.finish(null, state, "access_denied", "The user said no");

        assertThat(outcome.connected()).isFalse();
        assertThat(outcome.detail()).contains("The user said no");
        verify(credentials, never()).updateSecret(anyString(), anyString(), anyString());
    }

    @Test
    void a_credential_that_is_not_an_oauth_one_cannot_start_a_sign_in() {
        CredentialStore.Credential token = mock(CredentialStore.Credential.class);
        when(token.id()).thenReturn("cred_token");
        when(token.kind()).thenReturn(CredentialStore.Kind.API_TOKEN);
        when(credentials.list(ORG)).thenReturn(List.of(token));

        OAuthCredentialFlow.StartResult started = flow.start(ORG, "cred_token", BASE);

        assertThat(started.ok()).isFalse();
        assertThat(started.error()).isNotBlank();
    }

    private static Map<String, String> queryOf(String url) {
        String query = URI.create(url).getRawQuery();
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        p -> java.net.URLDecoder.decode(p[0], java.nio.charset.StandardCharsets.UTF_8),
                        p -> p.length > 1
                                ? java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8)
                                : "",
                        (a, b) -> a));
    }
}
