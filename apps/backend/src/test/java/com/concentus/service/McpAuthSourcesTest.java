package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpClient;
import com.concentus.llm.McpOAuthStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which token a server is called with, and what happens when that token is refused.
 *
 * <p>Three call sites build MCP clients — the worker facade, the local-model executor and the tool
 * picker — and each one used to resolve a token once and hand over the string. That is why a run
 * outliving its access token failed everywhere at once: there was no object left that knew how to
 * ask for another one.
 */
class McpAuthSourcesTest {

    private static final String URL = "https://mcp.resend.com/mcp";

    @AfterEach
    void clearLookup() {
        AgentSpec.setCredentialLookup(null);
    }

    @Test
    void a_server_with_a_stored_credential_presents_it_and_has_nothing_to_renew() {
        AgentSpec.setCredentialLookup(id -> "cred-1".equals(id) ? "re_static_123" : null);
        McpServerSpec spec = new McpServerSpec();
        spec.url = URL;
        spec.credentialId = "cred-1";

        McpClient.TokenSource source = McpAuthSources.forServer(spec, mock(McpOAuthStore.class), "local");

        assertThat(source.current()).isEqualTo("re_static_123");
        assertThat(source.renew()).isNull();
    }

    @Test
    void a_server_authorized_by_oauth_asks_the_store_again_when_its_token_is_refused() {
        McpServerSpec spec = new McpServerSpec();
        spec.url = URL;

        McpOAuthStore store = mock(McpOAuthStore.class);
        when(store.accessToken("local", URL)).thenReturn(Optional.of("granted-1"));
        when(store.renewedAccessToken("local", URL)).thenReturn(Optional.of("granted-2"));

        McpClient.TokenSource source = McpAuthSources.forServer(spec, store, "local");

        assertThat(source.current()).isEqualTo("granted-1");
        assertThat(source.renew()).isEqualTo("granted-2");
    }

    @Test
    void the_grant_wins_over_a_credential_left_on_the_node() {
        AgentSpec.setCredentialLookup(id -> "leftover-token");
        McpServerSpec spec = new McpServerSpec();
        spec.url = URL;
        spec.credentialId = "cred-1";

        McpOAuthStore store = mock(McpOAuthStore.class);
        when(store.accessToken("local", URL)).thenReturn(Optional.of("granted-1"));

        assertThat(McpAuthSources.forServer(spec, store, "local").current()).isEqualTo("granted-1");
    }

    @Test
    void a_grant_that_cannot_be_renewed_reports_nothing_rather_than_an_old_token() {
        McpServerSpec spec = new McpServerSpec();
        spec.url = URL;

        McpOAuthStore store = mock(McpOAuthStore.class);
        when(store.accessToken("local", URL)).thenReturn(Optional.of("granted-1"));
        when(store.renewedAccessToken("local", URL)).thenReturn(Optional.empty());

        McpClient.TokenSource source = McpAuthSources.forServer(spec, store, "local");

        assertThat(source.renew()).isNull();
    }
}
