package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.McpClient;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.FacadeProfile;
import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.concentus.service.FacadeToolGate;
import com.concentus.service.McpAuthSources;
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

import java.util.Locale;

/**
 * The MCP facade each independent worker talks to — the only route from a worker process to the
 * flow's MCP servers, and therefore the place where the worker's profile is <em>enforced</em>
 * rather than suggested.
 *
 * <p>Same transport as {@link RunToolsController} (per-token JSON-RPC over HTTP, because the CLI
 * has no cookie), but scoped one level tighter: the token is per <b>worker</b>, minted when its
 * workspace is prepared, so one worker cannot present another worker's identity — or the
 * coordinator's — to widen its tool set.
 *
 * <p>Tool names are prefixed {@code <server>__<tool>}: two servers may both expose a
 * {@code list_invoices}, and the run log should say which server a call actually went to.
 */
@RestController
@RequestMapping("/api/runs/{runId}/workers/{nodeId}/tools")
public class WorkerToolsController {

    private static final Logger log = LoggerFactory.getLogger(WorkerToolsController.class);
    public static final String TOKEN_HEADER = RunToolsController.TOKEN_HEADER;

    private final RunService runs;
    private final ObjectMapper mapper;
    private final McpOAuthStore mcpOAuth;
    private final OrgContext orgContext;

    public WorkerToolsController(RunService runs, ObjectMapper mapper,
                                 McpOAuthStore mcpOAuth, OrgContext orgContext) {
        this.runs = runs;
        this.mapper = mapper;
        this.mcpOAuth = mcpOAuth;
        this.orgContext = orgContext;
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@PathVariable String runId, @PathVariable String nodeId,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestBody JsonNode request) {
        AgentRun run = runs.get(runId).orElse(null);
        String expected = run == null ? null : run.workerToolTokens.get(nodeId);
        if (!McpJsonRpc.tokenMatches(expected, token)) {
            return ResponseEntity.status(401).build();
        }

        AgentSpec worker = workerSpec(run, nodeId);
        FacadeProfile profile = run.workerFacadeProfiles.get(nodeId);
        if (worker == null || profile == null) {
            // A token exists but the worker or its frozen profile does not — a restart lost the
            // in-memory state. 401 rather than an empty tool list that reads as "nothing wired".
            return ResponseEntity.status(401).build();
        }

        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        return switch (method) {
            case "initialize" -> ok(id, initializeResult());
            case "notifications/initialized", "notifications/cancelled" ->
                    ResponseEntity.accepted().build();
            case "tools/list" -> ok(id, toolsList(run, worker, profile));
            case "tools/call" -> ok(id, toolsCall(run, worker, profile, request.path("params")));
            case "ping" -> ok(id, mapper.createObjectNode());
            default -> error(id, -32601, "Method not supported: " + method);
        };
    }

    private static AgentSpec workerSpec(AgentRun run, String nodeId) {
        if (run.compiled == null) return null;
        for (AgentSpec s : run.compiled.allAgents()) {
            if (nodeId.equals(s.nodeId)) return s;
        }
        // Plan-born workers exist on the run, not the canvas.
        return run.syntheticWorkers.get(nodeId);
    }

    private ObjectNode initializeResult() {
        return McpJsonRpc.initializeResult(mapper, "concentus-facade");
    }

    // ------------------------------------------------------------------- tools

    /** {@code <server>__<tool>}, tolerant of servers whose own names carry odd characters. */
    static String prefixed(String serverName, String toolName) {
        return serverName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_")
                + "__" + toolName;
    }

    /**
     * Connects to one of the worker's servers through the run's shared client cache — the
     * handshake is per session, and every worker of a run re-connecting per call would churn
     * sessions on the server exactly the way the local-model path already learned not to.
     */
    private McpClient clientFor(AgentRun run, McpServerSpec server) {
        // A source rather than a token: the client is cached for the whole run, and an OAuth
        // access token does not last that long — fifteen minutes is a common lifetime, and the
        // worker that sends the mail at the end of a long run is exactly who paid for that.
        McpClient.TokenSource auth = McpAuthSources.forServer(server, mcpOAuth,
                orgContext.defaultOrganizationId());
        return run.mcpClients.computeIfAbsent(server.name,
                k -> new McpClient(server.name, server.url, auth, mapper));
    }

    private ObjectNode toolsList(AgentRun run, AgentSpec worker, FacadeProfile profile) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (McpServerSpec server : worker.mcpServers) {
            if (server.url == null || server.url.isBlank()) continue;
            try {
                McpClient client = clientFor(run, server);
                for (ChatTypes.ToolSpec t : FacadeToolGate.visible(client.listTools(), server, profile)) {
                    ObjectNode tool = tools.addObject();
                    tool.put("name", prefixed(server.name, t.name()));
                    String description = t.description() == null ? "" : t.description();
                    if (!profile.readOnly() && profile.dryRunEnabled()
                            && !FacadeToolGate.isReadTool(t.name())) {
                        // Said on the tool itself, so the worker plans around a simulation
                        // instead of believing it changed something.
                        description = "[DRY RUN — a call is simulated, not executed] " + description;
                    }
                    tool.put("description", description);
                    tool.set("inputSchema", t.parameters() == null
                            ? emptyObjectSchema() : t.parameters());
                }
            } catch (RuntimeException e) {
                run.mcpClients.remove(server.name);
                log.warn("facade: '{}' unreachable for worker {}: {}",
                        server.name, worker.name, e.getMessage());
                run.emit(RunEvent.of("system", "Facade: MCP server '" + server.name
                        + "' is unreachable, so its tools are missing from " + worker.name + ": "
                        + e.getMessage(), worker.name, worker.nodeId));
            }
        }
        return result;
    }

    private ObjectNode toolsCall(AgentRun run, AgentSpec worker, FacadeProfile profile,
                                 JsonNode params) {
        String fullName = params.path("name").asText("");
        int sep = fullName.indexOf("__");
        if (sep <= 0) {
            return callResult(true, "Unknown tool '" + fullName
                    + "'. Call tools/list for the current set.");
        }
        String serverKey = fullName.substring(0, sep);
        String toolName = fullName.substring(sep + 2);

        McpServerSpec server = null;
        for (McpServerSpec s : worker.mcpServers) {
            if (s.name != null && prefixed(s.name, "").equals(serverKey + "__")) {
                server = s;
                break;
            }
        }
        if (server == null) {
            return callResult(true, "No server named '" + serverKey + "' is wired to this worker.");
        }
        // Checked on every call, not just at listing: an MCP server's tools are all callable by
        // name whether or not they were advertised, and the facade must hold either way.
        if (!FacadeToolGate.allowlisted(toolName, server, profile)) {
            return callResult(true, "'" + toolName + "' is not part of this worker's profile ('"
                    + profile.name() + "'). It was NOT executed.");
        }

        String argsJson = params.path("arguments").toString();
        switch (FacadeToolGate.decide(toolName, profile)) {
            case BLOCK -> {
                run.emit(RunEvent.of("tool_use", "Facade BLOCKED " + fullName + " (read-only)",
                        worker.name, worker.nodeId));
                return callResult(true, "'" + toolName + "' looks like a write and this worker's "
                        + "profile ('" + profile.name() + "') is read-only. It was NOT executed. "
                        + "Report the action you wanted to take instead of performing it.");
            }
            case DRY_RUN -> {
                run.emit(RunEvent.of("tool_use", "Facade DRY-RUN " + fullName,
                        worker.name, worker.nodeId));
                return callResult(false, "DRY RUN — '" + toolName + "' was NOT executed. It would "
                        + "have been called with arguments: " + argsJson + ". Treat this as a "
                        + "proposed action: include it in your report as something a human (or a "
                        + "later step with write permission) should confirm. Do not claim it "
                        + "happened.");
            }
            default -> {
                // fall through to execution below
            }
        }

        try {
            McpClient client = clientFor(run, server);
            run.emit(RunEvent.of("tool_use", server.name + " · " + toolName,
                    worker.name, worker.nodeId));
            return callResult(false, client.callTool(toolName, argsJson));
        } catch (RuntimeException e) {
            return callResult(true, "The call failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- plumbing

    private ObjectNode emptyObjectSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        return schema;
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
