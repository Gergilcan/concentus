package com.concentus.runner;

import com.anthropic.models.beta.agents.BetaManagedAgentsUrlMcpServerParams;
import com.concentus.config.AgentSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a managed session is told about a remote MCP server: the URL always, the credential when
 * the node has one — as the connector's {@code authorization_token}, since the typed builder has
 * no field for it — and nothing at all about a credential the node does not have.
 */
class ManagedMcpServersTest {

    @AfterEach
    void forgetTheLookup() {
        AgentSpec.setCredentialLookup(null);
    }

    private static AgentSpec.McpServerSpec server(String credentialId) {
        AgentSpec.McpServerSpec s = new AgentSpec.McpServerSpec();
        s.name = "holded";
        s.url = "https://mcp.example.com/mcp";
        s.credentialId = credentialId;
        return s;
    }

    @Test
    void a_server_with_a_credential_is_declared_with_its_token() {
        AgentSpec.setCredentialLookup(id -> "cred_1".equals(id) ? "tok-secret" : null);

        BetaManagedAgentsUrlMcpServerParams params = ManagedMcpServers.urlServer(server("cred_1"));

        assertThat(params.name()).isEqualTo("holded");
        assertThat(params.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(params._additionalProperties()).containsKey(ManagedMcpServers.AUTHORIZATION_PROPERTY);
        assertThat(params._additionalProperties().get(ManagedMcpServers.AUTHORIZATION_PROPERTY).toString())
                .contains("tok-secret");
        assertThat(ManagedMcpServers.carriesToken(server("cred_1"))).isTrue();
    }

    @Test
    void a_server_without_a_credential_is_declared_bare() {
        AgentSpec.setCredentialLookup(id -> null);

        BetaManagedAgentsUrlMcpServerParams params = ManagedMcpServers.urlServer(server(null));

        assertThat(params._additionalProperties()).doesNotContainKey(ManagedMcpServers.AUTHORIZATION_PROPERTY);
        assertThat(ManagedMcpServers.carriesToken(server(null))).isFalse();
    }
}
