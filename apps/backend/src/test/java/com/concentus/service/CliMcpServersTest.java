package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpOAuthStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the {@code claude} CLI is told about each of a run's MCP servers.
 *
 * <p>The file is written once, before the CLI starts, and never read again — so whatever token
 * lands in it is the token for the whole run. That is fine for a credential someone pasted and
 * wrong for an OAuth grant, which expires in minutes; those go through this backend instead, where
 * the token is resolved per request and a rejection can be renewed.
 */
class CliMcpServersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 8734;

    private final McpOAuthStore oauth = mock(McpOAuthStore.class);

    @AfterEach
    void clearLookup() {
        AgentSpec.setCredentialLookup(null);
    }

    private CliMcpServers servers() {
        return new CliMcpServers(oauth, MAPPER, PORT);
    }

    private JsonNode node(McpServerSpec spec) {
        return servers().node(spec, "default", "run-1", "tok-run");
    }

    @Test
    void a_local_command_is_handed_over_as_it_is() {
        McpServerSpec spec = new McpServerSpec();
        spec.name = "google-ads-escritura";
        spec.command = "npx";
        spec.args = List.of("-y", "mcp-google-ads");
        spec.env = Map.of("GOOGLE_ADS_MCP_WRITE", "true");

        JsonNode server = node(spec);

        assertThat(server.path("type").asText()).isEqualTo("stdio");
        assertThat(server.path("command").asText()).isEqualTo("npx");
        assertThat(server.path("args")).hasSize(2);
        assertThat(server.path("env").path("GOOGLE_ADS_MCP_WRITE").asText()).isEqualTo("true");
    }

    @Test
    void a_pasted_credential_goes_straight_to_the_server() {
        // Nothing to renew, so the extra hop would buy nothing and could only break something.
        AgentSpec.setCredentialLookup(id -> "glpat_abc");
        McpServerSpec spec = new McpServerSpec();
        spec.name = "gitlab";
        spec.url = "https://gitlab.com/api/v4/mcp";
        spec.credentialId = "cred-1";
        spec.authHeader = "PRIVATE-TOKEN";

        JsonNode server = node(spec);

        assertThat(server.path("url").asText()).isEqualTo("https://gitlab.com/api/v4/mcp");
        assertThat(server.path("headers").path("PRIVATE-TOKEN").asText()).isEqualTo("glpat_abc");
    }

    @Test
    void an_oauth_grant_is_routed_through_this_backend_instead() {
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.of("granted-1"));
        McpServerSpec spec = new McpServerSpec();
        spec.name = "resend";
        spec.url = "https://mcp.resend.com/mcp";

        JsonNode server = node(spec);

        assertThat(server.path("url").asText())
                .isEqualTo("http://127.0.0.1:8734/api/runs/run-1/mcp/resend");
        assertThat(server.path("headers").path("X-Concentus-Tools-Token").asText()).isEqualTo("tok-run");
        // The access token stays on this side: the CLI never holds one it cannot renew.
        assertThat(server.path("headers").has("Authorization")).isFalse();
    }

    @Test
    void a_server_name_with_a_space_survives_the_url() {
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.of("granted-1"));
        McpServerSpec spec = new McpServerSpec();
        spec.name = "Google Ads";
        spec.url = "https://example.com/mcp";

        assertThat(node(spec).path("url").asText())
                .isEqualTo("http://127.0.0.1:8734/api/runs/run-1/mcp/Google%20Ads");
    }

    @Test
    void a_server_with_no_authorization_at_all_is_left_alone() {
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.empty());
        McpServerSpec spec = new McpServerSpec();
        spec.name = "learn";
        spec.url = "https://learn.microsoft.com/api/mcp";

        JsonNode server = node(spec);

        // No header, so a grant the CLI holds from `claude mcp login` still gets its chance.
        assertThat(server.path("url").asText()).isEqualTo("https://learn.microsoft.com/api/mcp");
        assertThat(server.has("headers")).isFalse();
    }

    @Test
    void without_a_run_token_the_grant_is_sent_the_old_way_rather_than_to_a_dead_proxy() {
        // Belt and braces: a caller that forgot to mint the run's token must not produce a config
        // pointing at an endpoint that will 401 every single call.
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.of("granted-1"));
        McpServerSpec spec = new McpServerSpec();
        spec.name = "resend";
        spec.url = "https://mcp.resend.com/mcp";

        JsonNode server = servers().node(spec, "default", "run-1", null);

        assertThat(server.path("url").asText()).isEqualTo("https://mcp.resend.com/mcp");
        assertThat(server.path("headers").path("Authorization").asText()).isEqualTo("Bearer granted-1");
    }
}
