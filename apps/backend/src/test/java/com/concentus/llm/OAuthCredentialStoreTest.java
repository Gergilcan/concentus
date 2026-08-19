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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A credential that holds a whole OAuth authorization rather than one secret string.
 *
 * <p>It exists so nobody has to run a vendor's auth CLI on the machine that runs the flows. The
 * app does the browser dance once, keeps the grant encrypted, and hands the pieces to a local MCP
 * server through its environment — which is the only way this works at all in a container, where
 * a CLI that opens a browser and listens on its own loopback port has neither.
 *
 * <p>Hence the fields: the same authorization answers four environment variables for a server like
 * Google Ads (client id, client secret, refresh token, and an access token for anything that just
 * wants a bearer).
 */
class OAuthCredentialStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORG = "local";
    private static final String ID = "cred_oauth1";

    private final CredentialStore credentials = mock(CredentialStore.class);
    private final McpOAuth oauth = mock(McpOAuth.class);

    private OAuthCredentialStore store() {
        return new OAuthCredentialStore(credentials, oauth, MAPPER);
    }

    /** A stored grant, expiring when told. */
    private void stored(String accessToken, String refreshToken, Instant expiresAt) {
        String json = """
                {"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth",
                 "tokenUrl":"https://oauth2.googleapis.com/token",
                 "clientId":"client-1.apps.googleusercontent.com","clientSecret":"secret-1",
                 "scope":"https://www.googleapis.com/auth/adwords",
                 "accessToken":%s,"refreshToken":%s,"expiresAt":%s}"""
                .formatted(quote(accessToken), quote(refreshToken),
                        expiresAt == null ? "null" : "\"" + expiresAt + "\"");

        CredentialStore.Credential record = mock(CredentialStore.Credential.class);
        when(record.id()).thenReturn(ID);
        when(record.kind()).thenReturn(OAuthCredentialStore.KIND);
        when(credentials.list(ORG)).thenReturn(List.of(record));
        when(credentials.reveal(ORG, ID)).thenReturn(Optional.of(json));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    void hands_over_the_client_and_the_refresh_token_a_local_server_needs() {
        stored("access-1", "refresh-1", Instant.now().plusSeconds(3600));
        OAuthCredentialStore store = store();

        assertThat(store.field(ORG, ID, "client_id")).contains("client-1.apps.googleusercontent.com");
        assertThat(store.field(ORG, ID, "client_secret")).contains("secret-1");
        assertThat(store.field(ORG, ID, "refresh_token")).contains("refresh-1");
        assertThat(store.field(ORG, ID, "scope")).contains("https://www.googleapis.com/auth/adwords");
    }

    @Test
    void the_default_field_is_the_access_token_never_the_stored_json() {
        // The blob carries the client secret and the refresh token. Handing it to a process
        // because someone wrote credential:<id> without a suffix would be a quiet disaster.
        stored("access-1", "refresh-1", Instant.now().plusSeconds(3600));

        assertThat(store().field(ORG, ID, null)).contains("access-1");
    }

    @Test
    void an_expired_access_token_is_renewed_before_it_is_handed_over() {
        stored("access-old", "refresh-1", Instant.now().minusSeconds(60));
        when(oauth.refresh(any(), any(), eq("refresh-1")))
                .thenReturn(new McpOAuth.Tokens("access-new", "refresh-2",
                        Instant.now().plusSeconds(3600)));

        assertThat(store().field(ORG, ID, "access_token")).contains("access-new");
        // Saved, so the next run starts from the new pair rather than repeating the exchange.
        verify(credentials).updateSecret(eq(ORG), eq(ID), anyString());
    }

    @Test
    void a_live_access_token_is_handed_over_without_calling_the_provider() {
        stored("access-1", "refresh-1", Instant.now().plusSeconds(3600));

        assertThat(store().field(ORG, ID, "access_token")).contains("access-1");
        verify(oauth, never()).refresh(any(), any(), anyString());
    }

    @Test
    void an_authorization_that_was_never_completed_yields_nothing_rather_than_an_empty_string() {
        // Created but not connected yet: the browser dance has not happened.
        stored(null, null, null);

        assertThat(store().field(ORG, ID, "access_token")).isEmpty();
        assertThat(store().field(ORG, ID, "refresh_token")).isEmpty();
        // The client half is configuration, not the grant, so it is readable from the start.
        assertThat(store().field(ORG, ID, "client_id")).isPresent();
    }

    @Test
    void a_field_nobody_defined_is_refused_instead_of_guessed() {
        stored("access-1", "refresh-1", Instant.now().plusSeconds(3600));

        assertThat(store().field(ORG, ID, "id_token")).isEmpty();
    }

    @Test
    void a_credential_of_another_kind_is_not_read_as_a_grant() {
        CredentialStore.Credential record = mock(CredentialStore.Credential.class);
        when(record.id()).thenReturn(ID);
        when(record.kind()).thenReturn(CredentialStore.Kind.API_TOKEN);
        when(credentials.list(ORG)).thenReturn(List.of(record));

        assertThat(store().field(ORG, ID, "client_id")).isEmpty();
    }
}
