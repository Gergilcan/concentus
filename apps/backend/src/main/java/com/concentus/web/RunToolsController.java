package com.concentus.web;

import com.concentus.api.ApiCaller;
import com.concentus.api.OpenApiCatalog;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.ApiSourceSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MCP server each run's API nodes become.
 *
 * <p>The {@code claude} CLI already speaks MCP over HTTP, and each run already gets its own
 * {@code mcp-config.json} — so the shortest sound path from "an OpenAPI spec on the canvas" to "a
 * typed tool the agent can call" is for this backend to <em>be</em> an MCP server, scoped per run.
 * Three JSON-RPC methods cover the tools surface: {@code initialize}, {@code tools/list},
 * {@code tools/call}.
 *
 * <p>Reachable without a session, because the CLI has no cookie — every request instead carries a
 * per-run bearer token minted at workspace preparation and passed to the CLI inside its own MCP
 * config. Compared in constant time; a miss is a 401 with no detail.
 */
@RestController
@RequestMapping("/api/runs/{runId}/tools")
public class RunToolsController {

    private static final Logger log = LoggerFactory.getLogger(RunToolsController.class);
    public static final String TOKEN_HEADER = "X-Concentus-Tools-Token";

    private final RunService runs;
    private final OpenApiCatalog catalog;
    private final ApiCaller caller;
    private final ObjectMapper mapper;

    public RunToolsController(RunService runs, OpenApiCatalog catalog, ApiCaller caller,
                              ObjectMapper mapper) {
        this.runs = runs;
        this.catalog = catalog;
        this.caller = caller;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@PathVariable String runId,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestBody JsonNode request) {
        AgentRun run = runs.get(runId).orElse(null);
        if (run == null || run.toolToken == null || token == null
                || !MessageDigest.isEqual(run.toolToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }

        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        return switch (method) {
            case "initialize" -> ok(id, initializeResult());
            // Notifications carry no id and expect no result.
            case "notifications/initialized", "notifications/cancelled" ->
                    ResponseEntity.accepted().build();
            case "tools/list" -> ok(id, toolsList(run));
            case "tools/call" -> ok(id, toolsCall(run, request.path("params")));
            case "ping" -> ok(id, mapper.createObjectNode());
            default -> error(id, -32601, "Method not supported: " + method);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "concentus-apis");
        info.put("version", "1.0");
        return result;
    }

    /**
     * Tools from every allowed operation of every API node in the flow, across all agents.
     *
     * <p>Names are prefixed with the node's label so two APIs with a colliding operationId stay
     * distinct tools, and the run log names which node a call went through.
     */
    private Map<String, ResolvedTool> resolve(AgentRun run) {
        Map<String, ResolvedTool> out = new LinkedHashMap<>();
        if (run.compiled == null) return out;
        for (AgentSpec agent : run.compiled.allAgents()) {
            for (ApiSourceSpec spec : agent.apiSources) {
                OpenApiCatalog.Parsed parsed;
                try {
                    parsed = catalog.load(spec.specUrl, spec.specInline);
                } catch (RuntimeException e) {
                    log.warn("API node '{}': spec unusable ({})", spec.label, e.getMessage());
                    continue;
                }
                for (OpenApiCatalog.Operation op : parsed.operations()) {
                    if (!spec.ops.contains(op.key())) continue;
                    String name = OpenApiCatalog.sanitize(spec.label) + "__" + op.id();
                    out.putIfAbsent(name, new ResolvedTool(spec, op, parsed.baseUrl()));
                }
            }
        }
        return out;
    }

    private record ResolvedTool(ApiSourceSpec spec, OpenApiCatalog.Operation op, String baseUrl) {
    }

    private ObjectNode toolsList(AgentRun run) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        resolve(run).forEach((name, tool) -> {
            ObjectNode t = tools.addObject();
            t.put("name", name);
            t.put("description", (tool.op().description().isBlank()
                    ? tool.op().key()
                    : tool.op().description()) + " [" + tool.op().key() + "]");
            t.set("inputSchema", catalog.inputSchema(tool.op()));
        });
        return result;
    }

    private ObjectNode toolsCall(AgentRun run, JsonNode params) {
        String name = params.path("name").asText("");
        ResolvedTool tool = resolve(run).get(name);
        if (tool == null) {
            return callResult(true, "Unknown tool '" + name + "'. Call tools/list for the current set.");
        }
        try {
            ApiCaller.Result result = caller.call(tool.spec(), tool.op(), tool.baseUrl(),
                    params.path("arguments"));
            String text = "HTTP " + result.status() + "\n" + result.body();
            run.emit(com.concentus.model.RunEvent.of("tool_use",
                    "API " + tool.spec().label + ": " + tool.op().key() + " → "
                            + (result.status() == 0 ? "rejected before sending" : "HTTP " + result.status())));
            return callResult(!result.ok(), text);
        } catch (Exception e) {
            return callResult(true, "The call failed before a response arrived: " + e.getMessage());
        }
    }

    /** MCP tool-result shape: content blocks plus an isError flag the model can react to. */
    private ObjectNode callResult(boolean isError, String text) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) envelope.set("id", id);
        envelope.set("result", result);
        return ResponseEntity.ok(envelope);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) envelope.set("id", id);
        ObjectNode err = envelope.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return ResponseEntity.ok(envelope);
    }
}
