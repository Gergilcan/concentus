package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One hop between the {@code claude} CLI and an OAuth-protected MCP server, so the token can be
 * decided per request instead of per run.
 *
 * <p>The CLI reads its MCP servers from a config file written once, when the run's workspace is
 * prepared. A token written into that file is therefore the token the entire run uses — and an
 * OAuth access token is not built for that: fifteen minutes is a common lifetime, and a flow that
 * gathers data, thinks, and only then sends a mail routinely takes longer. Worse, the 401 never
 * came near this backend, so nothing could react to it: the run simply failed at whichever call
 * happened to be first after the clock ran out.
 *
 * <p>So for those servers the config points here instead. Every request is forwarded with a token
 * resolved at that moment; a rejection renews the grant and repeats the call once. Servers
 * authenticated by a credential the user pasted keep going straight to the remote — there is
 * nothing to renew, and a hop that adds nothing is a hop that can only add failure modes.
 *
 * <p>Deliberately a passthrough rather than an MCP implementation: the body is forwarded verbatim
 * and the response returned as it arrives, headers and content type included, so a server that
 * answers with an SSE stream, negotiates a protocol version or issues a session id keeps working
 * without this class knowing what any of those mean.
 */
@RestController
@RequestMapping(RunMcpProxyController.PATH)
public class RunMcpProxyController {

    /** Also read by {@link com.concentus.service.LocalClaudeExecutor} when writing the CLI config. */
    public static final String PATH = "/api/runs/{runId}/mcp";
    public static final String TOKEN_HEADER = RunToolsController.TOKEN_HEADER;

    private static final Logger log = LoggerFactory.getLogger(RunMcpProxyController.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    /** Response headers worth carrying back; the rest are this hop's business, not the CLI's. */
    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";

    private final RunService runs;
    private final McpOAuthStore oauth;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;
    /** A span per tool call: which server, on which run, and how long it took. */
    private final com.concentus.telemetry.Telemetry telemetry;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @org.springframework.beans.factory.annotation.Autowired
    public RunMcpProxyController(RunService runs, McpOAuthStore oauth, OrgContext orgContext,
                                 ObjectMapper mapper,
                                 com.concentus.telemetry.Telemetry telemetry) {
        this.runs = runs;
        this.oauth = oauth;
        this.orgContext = orgContext;
        this.mapper = mapper;
        this.telemetry = telemetry;
    }

    /** The shape tests build: a proxy that records nothing, because they are about what it proxies. */
    RunMcpProxyController(RunService runs, McpOAuthStore oauth, OrgContext orgContext,
                          ObjectMapper mapper) {
        this(runs, oauth, orgContext, mapper, com.concentus.telemetry.Telemetry.none());
    }

    @PostMapping("/{server}")
    public ResponseEntity<byte[]> proxy(@PathVariable String runId, @PathVariable String server,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                                        @RequestHeader(value = VERSION_HEADER, required = false) String protocolVersion,
                                        @RequestBody(required = false) byte[] body) {
        try (var span = telemetry.start(com.concentus.telemetry.Telemetry.SPAN_TOOL)) {
            span.tag(com.concentus.telemetry.Telemetry.ATTR_RUN_ID, runId)
                    .tag(com.concentus.telemetry.Telemetry.ATTR_SERVER, server);
            return proxied(runId, server, token, sessionId, protocolVersion, body);
        }
    }

    /**
     * The proxying itself.
     *
     * <p>Split from {@link #proxy} so the span wraps everything including a refusal: a tool call
     * rejected for a bad token is exactly the kind of thing somebody is looking for in a trace,
     * and instrumenting only the happy path would hide it.
     */
    private ResponseEntity<byte[]> proxied(String runId, String server, String token,
                                           String sessionId, String protocolVersion, byte[] body) {
        AgentRun run = runs.get(runId).orElse(null);
        String expected = run == null ? null : run.toolToken;
        if (!McpJsonRpc.tokenMatches(expected, token)) {
            return ResponseEntity.status(401).build();
        }

        McpServerSpec spec = serverSpec(run, server);
        if (spec == null || spec.url == null || spec.url.isBlank()) {
            // Named by the config this backend wrote itself, so an unknown name is not a typo to
            // forgive — it is a request to reach something this run was never given.
            return ResponseEntity.status(404).build();
        }

        String organizationId = orgContext.defaultOrganizationId();
        String bearer = oauth.accessToken(organizationId, spec.url).orElse(null);
        HttpResponse<byte[]> res = forward(spec.url, bearer, sessionId, protocolVersion, body);

        if (isAuthRejection(res.statusCode())) {
            String renewed = oauth.renewedAccessToken(organizationId, spec.url).orElse(null);
            if (renewed == null || renewed.isBlank()) {
                return cannotRenew(run, spec, body);
            }
            log.info("Renewed the grant for '{}' mid-run ({}) and repeated the call.",
                    spec.name, runId);
            res = forward(spec.url, renewed, sessionId, protocolVersion, body);
        }

        return relay(res);
    }

    /**
     * Session teardown. Forwarded rather than answered here: the session belongs to the remote
     * server, and swallowing the DELETE would leave it holding state for a run that is over.
     */
    @DeleteMapping("/{server}")
    public ResponseEntity<byte[]> end(@PathVariable String runId, @PathVariable String server,
                                      @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        AgentRun run = runs.get(runId).orElse(null);
        if (!McpJsonRpc.tokenMatches(run == null ? null : run.toolToken, token)) {
            return ResponseEntity.status(401).build();
        }
        McpServerSpec spec = serverSpec(run, server);
        if (spec == null || spec.url == null || spec.url.isBlank()) {
            return ResponseEntity.status(404).build();
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(spec.url))
                .timeout(Duration.ofSeconds(10))
                .DELETE();
        String bearer = oauth.accessToken(orgContext.defaultOrganizationId(), spec.url).orElse(null);
        if (bearer != null && !bearer.isBlank()) b.header("Authorization", "Bearer " + bearer);
        if (sessionId != null) b.header(SESSION_HEADER, sessionId);
        try {
            return relay(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(504).build();
        } catch (Exception e) {
            // A failed teardown must never be the thing that fails a run.
            log.debug("Session teardown for '{}' failed: {}", spec.name, e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }

    private static McpServerSpec serverSpec(AgentRun run, String name) {
        if (run == null || run.compiled == null || name == null) return null;
        for (AgentSpec agent : run.compiled.allAgents()) {
            for (McpServerSpec m : agent.mcpServers) {
                if (m.name != null && m.name.equalsIgnoreCase(name)) return m;
            }
        }
        return null;
    }

    private HttpResponse<byte[]> forward(String url, String bearer, String sessionId,
                                         String protocolVersion, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                // Both, because the server picks which one it answers with.
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        if (bearer != null && !bearer.isBlank()) b.header("Authorization", "Bearer " + bearer);
        if (sessionId != null) b.header(SESSION_HEADER, sessionId);
        if (protocolVersion != null) b.header(VERSION_HEADER, protocolVersion);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted forwarding an MCP call", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the MCP server: " + e.getMessage(), e);
        }
    }

    /** The remote's answer, as it came: status, body, content type, session and version. */
    private ResponseEntity<byte[]> relay(HttpResponse<byte[]> res) {
        HttpHeaders headers = new HttpHeaders();
        res.headers().firstValue("Content-Type").ifPresent(v -> headers.set("Content-Type", v));
        res.headers().firstValue(SESSION_HEADER).ifPresent(v -> headers.set(SESSION_HEADER, v));
        res.headers().firstValue(VERSION_HEADER).ifPresent(v -> headers.set(VERSION_HEADER, v));
        return ResponseEntity.status(res.statusCode()).headers(headers).body(res.body());
    }

    /**
     * The one case automation cannot cover: the grant is gone, and getting another needs a browser
     * and a person.
     *
     * <p>So the run stops rather than continues. A flow whose last step is "send the report" must
     * not finish reporting success on the strength of steps that did happen — an unattended cron
     * run has nobody reading the console, and silence here is what turns a broken authorization
     * into a week of missing mail nobody noticed.
     */
    private ResponseEntity<byte[]> cannotRenew(AgentRun run, McpServerSpec spec, byte[] body) {
        String message = "'" + spec.name + "' rejected the access token and the authorization "
                + "could not be renewed. Authorize it again from Resources → MCP, then run this "
                + "flow once by hand to confirm.";
        run.error = message;
        run.emit(RunEvent.of("system", "MCP: " + message));
        log.warn("Run {} stopped: the grant for '{}' cannot be renewed.", run.id, spec.name);
        try {
            runs.stop(run.id);
        } catch (RuntimeException e) {
            log.warn("Could not stop run {} after the failed renewal: {}", run.id, e.getMessage());
        }

        // Answered as a JSON-RPC error too, echoing the caller's id, so whatever the CLI does
        // between the call and the stop reads the reason rather than a bare socket close.
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        try {
            var id = mapper.readTree(body == null ? new byte[0] : body).get("id");
            if (id != null) envelope.set("id", id);
        } catch (Exception ignored) {
            // A body we cannot parse still deserves the error; it just gets no id.
        }
        ObjectNode error = envelope.putObject("error");
        error.put("code", -32001);
        error.put("message", message);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        return ResponseEntity.status(200).headers(headers)
                .body(envelope.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 401 is the spec's answer; 403 is what several servers send for the same thing. */
    private static boolean isAuthRejection(int status) {
        return status == 401 || status == 403;
    }
}
