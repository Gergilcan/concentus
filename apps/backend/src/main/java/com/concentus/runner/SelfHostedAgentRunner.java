package com.concentus.runner;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-hosted runner: this JVM drives the loop against the Claude API. Unlike the
 * managed runner, the spec's {@code cache} and {@code context} blocks are applied
 * directly as request parameters.
 *
 * <p>Beta features (MCP connector, compaction, context editing) and prompt-cache
 * placement are expressed as raw JSON via {@code putAdditionalBodyProperty}. This
 * matches the documented HTTP wire format and avoids depending on beta typed-builder
 * names that vary across SDK releases. The model, max tokens, betas and the user
 * message use the typed builder.
 *
 * <p>Scope: this issues a single turn. GitHub/GitLab access is via the configured
 * MCP servers (server-side tools, no client execution loop). See the README for
 * extending to a persistent multi-turn loop.
 */
final class SelfHostedAgentRunner implements AgentRunner {

    private final AgentSpec spec;
    private final AnthropicClient client;

    SelfHostedAgentRunner(AgentSpec spec, AnthropicClient client) {
        this.spec = spec;
        this.client = client;
    }

    @Override
    public void run(String userPrompt) {
        var builder = MessageCreateParams.builder()
                .model(spec.model.id)
                .maxTokens(spec.model.maxTokens)
                .addUserMessage(userPrompt);

        List<String> betas = new ArrayList<>();

        // system (+ prompt caching / "nvcache")
        builder.putAdditionalBodyProperty("system", JsonValue.from(systemJson()));

        // effort
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        outputConfig.put("effort", spec.model.effort);
        builder.putAdditionalBodyProperty("output_config", JsonValue.from(outputConfig));

        // MCP servers (github + gitlab) as server-side tools
        if (!spec.mcpServers.isEmpty()) {
            betas.add("mcp-client-2025-11-20");
            builder.putAdditionalBodyProperty("mcp_servers", JsonValue.from(mcpServersJson()));
            builder.putAdditionalBodyProperty("tools", JsonValue.from(mcpToolsetsJson()));
        }

        // context-swapping strategy
        Map<String, Object> contextMgmt = contextManagementJson(betas);
        if (contextMgmt != null) {
            builder.putAdditionalBodyProperty("context_management", JsonValue.from(contextMgmt));
        }

        for (String beta : betas) {
            builder.addBeta(beta);
        }

        if (spec.cache.enabled) {
            System.out.println("[local] prompt caching on (ttl=" + spec.cache.ttl
                    + ", breakpoints=" + spec.cache.breakpoints + "); min cacheable prefix ~"
                    + spec.cache.minTokens + " tokens is model-enforced.");
        }
        System.out.println("[local] model=" + spec.model.id + " effort=" + spec.model.effort
                + " context=" + spec.context.strategy + " mcpServers=" + spec.mcpServers.size());
        System.out.println("----------------------------------------------------------------");

        BetaMessage response = client.beta().messages().create(builder.build());

        // Print text blocks. MCP tool results resolve server-side and the model
        // typically summarizes them in a following text block.
        response.content().forEach(block -> block.text().ifPresent(t -> System.out.print(t.text())));
        System.out.println();
        System.out.println("\n[done] If the model paused for server-side tool work "
                + "(stop_reason=pause_turn), re-send with the assistant turn appended to continue "
                + "— see README (multi-turn continuation).");
    }

    // ------------------------------------------------------------- JSON builders

    private List<Object> systemJson() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", spec.systemPrompt);
        if (spec.cache.enabled) {
            Map<String, Object> cacheControl = new LinkedHashMap<>();
            cacheControl.put("type", "ephemeral");
            cacheControl.put("ttl", spec.cache.ttl); // "5m" | "1h"
            block.put("cache_control", cacheControl);
        }
        List<Object> system = new ArrayList<>();
        system.add(block);
        return system;
    }

    private List<Object> mcpServersJson() {
        List<Object> servers = new ArrayList<>();
        for (McpServerSpec mcp : spec.mcpServers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "url");
            m.put("name", mcp.name);
            m.put("url", mcp.url);
            String token = mcp.resolveToken();
            if (token != null) m.put("authorization_token", token);
            servers.add(m);
        }
        return servers;
    }

    private List<Object> mcpToolsetsJson() {
        List<Object> tools = new ArrayList<>();
        for (McpServerSpec mcp : spec.mcpServers) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", "mcp_toolset");
            t.put("mcp_server_name", mcp.name);
            tools.add(t);
        }
        return tools;
    }

    /** Returns the context_management body (and appends the required beta), or null for strategy=none. */
    private Map<String, Object> contextManagementJson(List<String> betas) {
        switch (spec.context.strategy) {
            case "compaction": {
                betas.add("compact-2026-01-12");
                Map<String, Object> edit = new LinkedHashMap<>();
                edit.put("type", "compact_20260112");
                return Map.of("edits", List.of(edit));
            }
            case "context-editing": {
                betas.add("context-management-2025-06-27");
                Map<String, Object> edit = new LinkedHashMap<>();
                edit.put("type", "clear_tool_uses_20250919");
                if (spec.context.clearToolInputs) edit.put("clear_tool_inputs", true);
                return Map.of("edits", List.of(edit));
            }
            default:
                return null; // none
        }
    }
}
