package com.concentus.web;

import com.concentus.auth.AccountStore;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.mcp.StudioTool;
import com.concentus.mcp.StudioToolset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concentus itself, as an MCP server — so an outside agent can design, validate, run and steer
 * flows the way a person does through the canvas.
 *
 * <p>This is the mirror of the other four MCP endpoints in this package. Those exist so a flow's
 * agents can reach outwards (its API nodes, a worker's facade, the planner, the verifier). This one
 * exists so something outside can reach in: point Claude Code at it and "write me a flow that
 * reviews my pull requests" becomes a real flow on the dashboard, validated before it is saved.
 *
 * <p><b>Where the tools live.</b> Not here. A single controller holding two dozen tool definitions
 * and their behaviour would be the largest file in the project and the least readable; instead each
 * {@link StudioToolset} contributes its own group and this class is only the transport. Adding a
 * tool never touches this file.
 *
 * <p><b>Reachability.</b> Outside the session check, like the run-tools endpoints, because an MCP
 * client has no cookie. What replaces the session depends on the deployment:
 * <ul>
 *   <li><b>Desktop</b> ({@code app.auth.enabled=false}) — the socket is bound to {@code 127.0.0.1}
 *       and every other API endpoint is already open on it. Requiring a token here and nowhere else
 *       would be security theatre: anything that could call this could call {@code /api/flows}
 *       directly. A token may still be set, and is then enforced.</li>
 *   <li><b>Server</b> ({@code app.auth.enabled=true}) — a token is <em>required</em>, and it stands
 *       for a real account: the request runs as {@code app.mcp.studio.account}, so every store sees
 *       the same organization it would for that person's browser session. With no token configured
 *       the endpoint answers 401 to everyone, which is the only safe reading of "reachable
 *       deployment, no credential set".</li>
 * </ul>
 */
@RestController
@RequestMapping(McpStudioController.PATH)
public class McpStudioController {

    /** Also named in {@code SecurityConfig} — both the permit rule and the CSRF exemption. */
    public static final String PATH = "/api/mcp/studio";

    /** Accepted alongside {@code Authorization: Bearer}, matching the other MCP endpoints' style. */
    public static final String TOKEN_HEADER = "X-Concentus-Studio-Token";

    private static final Logger log = LoggerFactory.getLogger(McpStudioController.class);

    /**
     * Told to the client at handshake, and the only chance to set expectations before the model
     * starts guessing. Two things earn their place: that flows are validated rather than trusted,
     * and that runs are asynchronous — the two facts an agent otherwise learns by getting them
     * wrong.
     */
    private static final String INSTRUCTIONS = """
            Concentus designs and runs multi-agent flows. A flow is a graph of agent and capability \
            nodes that executes as a real Claude session on this machine.

            Call flow_schema before writing or editing any flow — the format is specific to this \
            product. Call validate_flow before saving: it compiles the graph and reports what would \
            fail at run time, and a rejection tells you exactly what to fix.

            Runs are asynchronous. run_flow returns a run id immediately, with nothing done yet; \
            poll get_run until its status is COMPLETED or ERROR. Read what an individual agent \
            produced with get_run_nodes.

            Deleting a flow, clearing its memory and removing a library entry all require \
            confirm=true, and all of them are things to ask the user about first.""";

    private final Map<String, StudioTool> tools;
    private final ObjectMapper mapper;
    private final OrgContext orgContext;
    private final AccountStore accounts;
    private final String token;
    private final String accountEmail;
    /** Loopback and one person at the machine — the guard this endpoint actually rests on. */
    private final boolean desktop;

    public McpStudioController(List<StudioToolset> toolsets, ObjectMapper mapper,
                               OrgContext orgContext, AccountStore accounts,
                               @Value("${app.mcp.studio.token:}") String token,
                               @Value("${app.mcp.studio.account:}") String accountEmail,
                               @Value("${app.desktop:false}") boolean desktop) {
        this.tools = index(toolsets);
        this.mapper = mapper;
        this.orgContext = orgContext;
        this.accounts = accounts;
        this.token = token == null ? "" : token.trim();
        this.accountEmail = accountEmail == null ? "" : accountEmail.trim();
        this.desktop = desktop;
        log.info("Studio MCP server ready at {} with {} tools.", PATH, tools.size());
        if (!desktop && this.token.isEmpty()) {
            log.warn("Studio MCP server is DISABLED: authentication is on and no "
                    + "app.mcp.studio.token (CONCENTUS_MCP_TOKEN) is configured. Set one, plus "
                    + "app.mcp.studio.account (CONCENTUS_MCP_ACCOUNT) naming the account it acts as.");
        }
    }

    /**
     * Every toolset's tools by name, refusing a collision.
     *
     * <p>Fails at startup rather than at call time on purpose. Two tools sharing a name is a
     * mistake whose runtime symptom is one of them silently never being reachable — the kind of
     * bug that survives review because everything looks registered.
     *
     * <p>Insertion order is kept, so {@code tools/list} presents each toolset's tools together in
     * the order they were written. Not cosmetic: it is the order a model reads them in, and
     * {@code flow_schema} arriving before the tools that need it is worth the unmodifiable wrapper
     * over {@code Map.copyOf}, which would hash them into an arbitrary order.
     */
    private static Map<String, StudioTool> index(List<StudioToolset> toolsets) {
        Map<String, StudioTool> byName = new LinkedHashMap<>();
        for (StudioToolset toolset : toolsets) {
            for (StudioTool tool : toolset.tools()) {
                StudioTool clash = byName.putIfAbsent(tool.name(), tool);
                if (clash != null) {
                    throw new IllegalStateException("Two Studio MCP tools are named '" + tool.name()
                            + "'. Tool names must be unique across every StudioToolset.");
                }
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = TOKEN_HEADER, required = false) String headerToken,
            @RequestBody JsonNode request) {
        if (!authorize(bearer(authorization, headerToken))) {
            return ResponseEntity.status(401).build();
        }
        try {
            String method = request.path("method").asText("");
            JsonNode id = request.get("id");
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                // Notifications carry no id and expect no result.
                case "notifications/initialized", "notifications/cancelled" ->
                        ResponseEntity.accepted().build();
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> ok(id, toolsCall(request.path("params")));
                case "ping" -> ok(id, mapper.createObjectNode());
                default -> error(id, -32601, "Method not supported: " + method);
            };
        } finally {
            // Whatever this request authenticated as must not outlive it. The filter chain clears
            // the holder too; doing it here as well costs nothing and does not depend on which
            // chain matched.
            SecurityContextHolder.clearContext();
        }
    }

    // -------------------------------------------------------------------- auth

    /** The presented token, from either accepted header. Null when neither carried one. */
    private static String bearer(String authorization, String headerToken) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = authorization.substring(7).trim();
            if (!value.isEmpty()) return value;
        }
        if (headerToken != null && !headerToken.isBlank()) return headerToken.trim();
        return null;
    }

    /**
     * Whether this request may proceed — and, when authentication is on, who it proceeds as.
     *
     * <p>The organization is taken from the account the token stands for, never from the request,
     * for exactly the reason {@link OrgContext} gives: it is what stops a caller from addressing
     * another tenant's data. A token whose account cannot be resolved is refused rather than
     * falling back to the default organization, because that fallback would be a cross-tenant read.
     */
    private boolean authorize(String presented) {
        if (!token.isEmpty()) {
            if (!McpJsonRpc.tokenMatches(token, presented)) return false;
            // A configured token still has to say which account it acts as, except on the desktop,
            // where the single organization is the only one there is.
            return desktop || impersonateConfiguredAccount();
        }
        // No token: reachable only on the desktop build, and there the guard is the same one the
        // shutdown endpoint rests on — a socket bound to loopback and one person at the machine.
        // Not the account check, which is why this did not change when accounts became mandatory.
        return desktop;
    }

    private boolean impersonateConfiguredAccount() {
        if (accountEmail.isEmpty()) {
            log.warn("Studio MCP: token accepted but app.mcp.studio.account is not set, so there is "
                    + "no organization to act in. Refusing the request.");
            return false;
        }
        ConcentusUserDetails user = accounts.findByEmail(accountEmail)
                .map(ConcentusUserDetails::of)
                .orElse(null);
        if (user == null || !user.enabled()) {
            log.warn("Studio MCP: app.mcp.studio.account '{}' is not an enabled account. Refusing.",
                    accountEmail);
            return false;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        return true;
    }

    // ------------------------------------------------------------------- tools

    private ObjectNode initializeResult() {
        ObjectNode result = McpJsonRpc.initializeResult(mapper, "concentus-studio");
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode listed = result.putArray("tools");
        for (StudioTool tool : tools.values()) {
            ObjectNode node = listed.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    /**
     * Runs one tool, and answers every failure as a tool result rather than a transport error.
     *
     * <p>A JSON-RPC error ends the exchange; a failed tool result is something the model reads and
     * acts on. Since almost everything that goes wrong here is the model's to fix — a flow id that
     * does not exist, a graph that will not compile, a missing argument — the useful thing is
     * always to hand back the message and let it try again.
     */
    private ObjectNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        StudioTool tool = tools.get(name);
        if (tool == null) {
            return McpJsonRpc.callResult(mapper, true, "Unknown tool '" + name
                    + "'. Call tools/list for the current set.");
        }
        JsonNode arguments = params.path("arguments");
        if (!arguments.isObject()) arguments = mapper.createObjectNode();
        try {
            StudioTool.Result result = tool.handler().apply(arguments);
            if (result.isError()) log.debug("Studio MCP tool {} answered with a failure.", name);
            return McpJsonRpc.callResult(mapper, result.isError(), result.text());
        } catch (ResponseStatusException e) {
            // What the REST controllers throw for "no such flow" / "no such run" — the reason is
            // already written for a person, so it needs no translation.
            return McpJsonRpc.callResult(mapper, true, reason(e));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return McpJsonRpc.callResult(mapper, true, e.getMessage());
        } catch (RuntimeException e) {
            // Unexpected: log it with the stack trace, and tell the caller enough to report it
            // without leaking internals into an agent's context.
            log.error("Studio MCP tool {} failed unexpectedly.", name, e);
            return McpJsonRpc.callResult(mapper, true, "The tool failed unexpectedly: "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()));
        }
    }

    private static String reason(ResponseStatusException e) {
        String reason = e.getReason();
        return reason == null || reason.isBlank() ? e.getMessage() : reason;
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, ObjectNode result) {
        return McpJsonRpc.ok(mapper, id, result);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        return McpJsonRpc.error(mapper, id, code, message);
    }
}
