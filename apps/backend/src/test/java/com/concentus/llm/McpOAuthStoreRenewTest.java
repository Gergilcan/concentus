package com.concentus.llm;

import com.concentus.secrets.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renewing on demand, as opposed to renewing when the clock says to.
 *
 * <p>{@code accessToken} hands back what it has while that looks fresh, which is right for the
 * common case and wrong for the one that matters here: the server has just answered 401, so the
 * token it holds is dead no matter what its {@code expiresAt} claims — a revoked grant, a clock
 * that drifted, a token the provider retired early. The rejection is the evidence; the stored
 * expiry is only a guess.
 */
class McpOAuthStoreRenewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://mcp.resend.com/mcp";

    @Test
    void renews_even_when_the_stored_token_still_looks_fresh() {
        CredentialStore credentials = storedGrant("live-but-rejected", "refresh-1",
                Instant.now().plusSeconds(3600));
        McpOAuth oauth = mock(McpOAuth.class);
        when(oauth.refresh(any(), any(), anyString()))
                .thenReturn(new McpOAuth.Tokens("minted-2", "refresh-2", Instant.now().plusSeconds(900)));

        McpOAuthStore store = new McpOAuthStore(credentials, oauth, MAPPER);
        // Warm the cache the way a run does, so the test proves the renewal ignores it.
        assertThat(store.accessToken("local", URL)).contains("live-but-rejected");

        assertThat(store.renewedAccessToken("local", URL)).contains("minted-2");
    }

    @Test
    void gives_nothing_back_when_there_is_no_refresh_token_to_use() {
        CredentialStore credentials = storedGrant("dead", null, Instant.now().plusSeconds(3600));
        McpOAuthStore store = new McpOAuthStore(credentials, mock(McpOAuth.class), MAPPER);

        assertThat(store.renewedAccessToken("local", URL)).isEmpty();
    }

    @Test
    void gives_nothing_back_when_the_provider_refuses_the_refresh() {
        CredentialStore credentials = storedGrant("dead", "revoked", Instant.now().plusSeconds(3600));
        McpOAuth oauth = mock(McpOAuth.class);
        when(oauth.refresh(any(), any(), anyString()))
                .thenThrow(new LlmException("oauth", "invalid_grant"));

        McpOAuthStore store = new McpOAuthStore(credentials, oauth, MAPPER);

        assertThat(store.renewedAccessToken("local", URL)).isEmpty();
    }

    /** A credential store holding exactly one grant for {@link #URL}. */
    private static CredentialStore storedGrant(String accessToken, String refreshToken,
                                               Instant expiresAt) {
        String json = """
                {"authorizationUrl":"https://auth.example.com/authorize",
                 "tokenUrl":"https://auth.example.com/token",
                 "registrationUrl":"https://auth.example.com/register",
                 "resource":"%s","clientId":"client-1","clientSecret":"secret-1",
                 "accessToken":"%s","refreshToken":%s,"expiresAt":"%s"}"""
                .formatted(URL, accessToken,
                        refreshToken == null ? "null" : "\"" + refreshToken + "\"", expiresAt);

        CredentialStore credentials = mock(CredentialStore.class);
        CredentialStore.Credential credential = mock(CredentialStore.Credential.class);
        when(credential.id()).thenReturn("cred-1");
        when(credential.label()).thenReturn("mcp-oauth:" + URL);
        when(credentials.list("local")).thenReturn(List.of(credential));
        when(credentials.reveal("local", "cred-1")).thenReturn(Optional.of(json));
        return credentials;
    }
}
