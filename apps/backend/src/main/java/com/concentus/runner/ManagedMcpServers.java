package com.concentus.runner;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.agents.BetaManagedAgentsUrlMcpServerParams;
import com.concentus.config.AgentSpec.McpServerSpec;

/**
 * How a remote MCP server is declared to a managed session, with its credential when the node
 * has one.
 *
 * <p>The SDK's typed builder has no field for a token, so it rides as the
 * {@code authorization_token} property the Anthropic MCP connector reads; a server with no
 * credential is declared bare, exactly as before. A session in Anthropic's cloud cannot reach
 * this machine's facade endpoint, so the token itself is what goes — which is why managed runs
 * are the one place a node's credential leaves the machine, and the run log says so per server.
 * Not verified against a live managed session from here: the property name is the connector's,
 * and a session that refuses it says so in its own error.
 */
public final class ManagedMcpServers {

    public static final String AUTHORIZATION_PROPERTY = "authorization_token";

    private ManagedMcpServers() {
    }

    public static BetaManagedAgentsUrlMcpServerParams urlServer(McpServerSpec mcp) {
        var server = BetaManagedAgentsUrlMcpServerParams.builder()
                .type(BetaManagedAgentsUrlMcpServerParams.Type.URL)
                .name(mcp.name)
                .url(mcp.url);
        String token = mcp.resolveToken();
        if (token != null && !token.isBlank()) {
            server.putAdditionalProperty(AUTHORIZATION_PROPERTY, JsonValue.from(token));
        }
        return server.build();
    }

    /** Whether the declaration carried a credential — for the run log, never the token itself. */
    public static boolean carriesToken(McpServerSpec mcp) {
        String token = mcp.resolveToken();
        return token != null && !token.isBlank();
    }
}
