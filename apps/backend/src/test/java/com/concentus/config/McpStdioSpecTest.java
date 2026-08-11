package com.concentus.config;

import com.concentus.config.AgentSpec.McpServerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The stdio half of an MCP spec: what counts as one, and how its env reaches the process. */
class McpStdioSpecTest {

    @Test
    void aCommandMakesTheSpecStdioAndSatisfiesValidation() {
        AgentSpec spec = new AgentSpec();
        spec.name = "a";
        spec.model.id = "claude-x";
        spec.model.maxTokens = 100;
        McpServerSpec mcp = new McpServerSpec();
        mcp.name = "google-ads";
        mcp.command = "npx";
        spec.mcpServers.add(mcp);

        assertThat(mcp.isStdio()).isTrue();
        assertThatCode(spec::validate).doesNotThrowAnyException();
    }

    @Test
    void envValuesWithTheCredentialPrefixAreResolvedAndOthersPassThrough() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.command = "npx";
        mcp.env.put("DEV_TOKEN", "credential:cred_7");
        mcp.env.put("CUSTOMER_ID", "1234567890");
        mcp.env.put("MISSING", "credential:nope");

        Map<String, String> resolved =
                mcp.resolveEnv(id -> "cred_7".equals(id) ? "s3cret" : null);

        assertThat(resolved).containsEntry("DEV_TOKEN", "s3cret");
        assertThat(resolved).containsEntry("CUSTOMER_ID", "1234567890");
        // An unknown credential resolves to empty rather than leaking the reference downstream.
        assertThat(resolved).containsEntry("MISSING", "");
    }

    @Test
    void resolveEnvKeepsTheAuthoredOrder() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.command = "python";
        mcp.env.put("B", "2");
        mcp.env.put("A", "1");

        assertThat(List.copyOf(mcp.resolveEnv(id -> null).keySet())).containsExactly("B", "A");
    }
}
