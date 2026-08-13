package com.concentus.web;

import com.concentus.api.ApiCaller;
import com.concentus.api.OpenApiCatalog;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.ApiSourceSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.store.FlowMemoryStore;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** How many notes {@code memory_read} returns — the newest ones, in chronological order. */
    static final int MEMORY_READ_LIMIT = 50;

    private final RunService runs;
    private final OpenApiCatalog catalog;
    private final ApiCaller caller;
    private final ObjectMapper mapper;
    private final FlowMemoryStore memory;

    public RunToolsController(RunService runs, OpenApiCatalog catalog, ApiCaller caller,
                              ObjectMapper mapper, FlowMemoryStore memory) {
        this.runs = runs;
        this.catalog = catalog;
        this.caller = caller;
        this.mapper = mapper;
        this.memory = memory;
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@PathVariable String runId,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestBody JsonNode request) {
        AgentRun run = runs.get(runId).orElse(null);
        if (run == null || !McpJsonRpc.tokenMatches(run.toolToken, token)) {
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
        return McpJsonRpc.initializeResult(mapper, "concentus-apis");
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
        if (hasMemory(run)) appendMemoryTools(tools);
        return result;
    }

    // ------------------------------------------------------------- flow memory

    /**
     * Memory belongs to a flow, not a run, so only runs of a saved flow get the tools. An ad-hoc
     * canvas has no stable identity for notes to survive under — the tools are absent rather than
     * present-but-failing, so the model never plans around a memory it cannot have.
     */
    private static boolean hasMemory(AgentRun run) {
        return run.flowId != null && !run.flowId.isBlank();
    }

    private void appendMemoryTools(ArrayNode tools) {
        ObjectNode read = tools.addObject();
        read.put("name", "memory_read");
        read.put("description", "Read this flow's persistent memory: notes that previous runs of "
                + "this same flow left behind. Call it before starting work — an earlier run may "
                + "already have learned something you are about to redo. Returns the newest "
                + MEMORY_READ_LIMIT + " notes, oldest first.");
        ObjectNode readSchema = read.putObject("inputSchema");
        readSchema.put("type", "object");
        readSchema.putObject("properties");

        ObjectNode append = tools.addObject();
        append.put("name", "memory_append");
        append.put("description", "Leave a note in this flow's persistent memory, readable by "
                + "every future run of this flow. Use it when you learn something a future run "
                + "should know: a decision taken, state reached, an approach that failed. Keep it "
                + "short and factual — memory is shared notes, not a transcript.");
        ObjectNode appendSchema = append.putObject("inputSchema");
        appendSchema.put("type", "object");
        appendSchema.putObject("properties").putObject("note")
                .put("type", "string")
                .put("description", "The note to remember, at most "
                        + FlowMemoryStore.MAX_NOTE_CHARS + " characters.");
        appendSchema.putArray("required").add("note");
    }

    private ObjectNode memoryRead(AgentRun run) {
        if (!memory.isAvailable()) {
            return callResult(true, "Memory is unavailable right now: the database cannot be "
                    + "reached. Work without it and say so in your answer.");
        }
        List<FlowMemoryStore.Note> notes = memory.recent(run.flowId, MEMORY_READ_LIMIT);
        run.emit(com.concentus.model.RunEvent.of("tool_use",
                "Memory: read " + notes.size() + " note(s)"));
        if (notes.isEmpty()) {
            return callResult(false, "This flow's memory is empty — no previous run has left a "
                    + "note yet.");
        }
        int total = memory.count(run.flowId);
        StringBuilder text = new StringBuilder();
        text.append(total).append(" note(s)");
        if (total > notes.size()) {
            text.append(" — showing the newest ").append(notes.size());
        }
        text.append(", oldest first:\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (FlowMemoryStore.Note n : notes) {
            String when = fmt.format(Instant.ofEpochMilli(n.createdAt())
                    .atZone(ZoneId.systemDefault()));
            text.append("\n[").append(when).append("] ").append(n.note());
        }
        return callResult(false, text.toString());
    }

    private ObjectNode memoryAppend(AgentRun run, JsonNode params) {
        if (!memory.isAvailable()) {
            return callResult(true, "The note was NOT saved: the database cannot be reached. "
                    + "Do not claim it was remembered.");
        }
        String note = params.path("arguments").path("note").asText("");
        try {
            memory.append(run.flowId, run.id, note);
        } catch (IllegalArgumentException e) {
            return callResult(true, e.getMessage());
        } catch (Exception e) {
            return callResult(true, "The note was NOT saved: " + e.getMessage());
        }
        int total = memory.count(run.flowId);
        run.emit(com.concentus.model.RunEvent.of("tool_use",
                "Memory: note saved (" + total + " total)"));
        return callResult(false, "Saved. This flow now has " + total + " note(s).");
    }

    private ObjectNode toolsCall(AgentRun run, JsonNode params) {
        String name = params.path("name").asText("");
        if (hasMemory(run)) {
            if ("memory_read".equals(name)) return memoryRead(run);
            if ("memory_append".equals(name)) return memoryAppend(run, params);
        }
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

    private ObjectNode callResult(boolean isError, String text) {
        return McpJsonRpc.callResult(mapper, isError, text);
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, ObjectNode result) {
        return McpJsonRpc.ok(mapper, id, result);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        return McpJsonRpc.error(mapper, id, code, message);
    }
}
