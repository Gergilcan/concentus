package com.concentus.web;

import com.concentus.config.Settings;
import com.concentus.config.SettingsCatalog;
import com.concentus.integration.UntrustedContent;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import com.concentus.support.Texts;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A published flow, callable from outside as a plain HTTP endpoint.
 *
 * <p>{@code POST /api/public/flows/{flowId}/run} with {@code {"input": "..."}} and
 * {@code Authorization: Bearer <token>} starts a run whose first message is the input, and by
 * default waits for the run's final output so the caller gets an answer rather than a ticket.
 * The token is the one the flow's author minted on the Input node when they turned publishing on;
 * it authorizes this flow and nothing else.
 *
 * <p>What the status codes give away is chosen deliberately. An unknown flow and a flow that
 * exists but is not published answer the <em>same</em> 404, so a caller cannot use this endpoint
 * to learn which flow ids exist. A wrong token on a published flow is a 401 with no detail: the
 * caller already knows the flow is there, and the comparison is constant-time so the response
 * cannot be timed into the token either. Requests are rate-limited per presented token — wrong
 * ones included, which is what throttles a guess.
 *
 * <p>The rate is the tier's. A free installation and a Team deployment get
 * {@link Feature#TEAM_ENDPOINT_RATE_PER_MINUTE} a minute per token; Enterprise has no limit unless
 * the {@code endpoints.rate-per-minute} setting puts one back. Read per request, so changing the
 * setting takes effect on the next call, and the 429 names both the figure and where it comes from.
 *
 * <p>Publishing is orthogonal to the flow's own trigger: a cron flow keeps its schedule and also
 * answers here. Runs started this way carry the trigger label {@code api}.
 *
 * <p>The chat page under {@code /chat} is a demo surface — one self-contained HTML string, the
 * token in its URL — for trying a published flow from a browser without writing a client.
 */
@RestController
@RequestMapping("/api/public/flows")
public class PublicRunController {

    private static final Logger log = LoggerFactory.getLogger(PublicRunController.class);

    /** Longest input carried into the prompt. As the webhook's payload cap — the same reasons. */
    static final int MAX_INPUT = 12_000;
    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_TIMEOUT_SECONDS = 600;
    /**
     * Per token and minute on a free installation — the figure this endpoint has always had. Runs
     * are expensive; a client retrying in a tight loop is not a use case. The same number as the
     * Team constant, on purpose: Team buys the shared deployment, not a looser endpoint.
     */
    static final int FREE_REQUESTS_PER_MINUTE = Feature.TEAM_ENDPOINT_RATE_PER_MINUTE;

    private final FlowStore flows;
    private final RunService runService;
    private final LicenseService license;
    private final Settings settings;
    /** Whether the organization wants an admin's word before an endpoint answers. */
    private final com.concentus.policy.OrgPolicyService policies;
    private final RateLimiter limiter;
    /** How often a waiting request looks at the run. A second is fine for runs measured in tens of them. */
    private final Duration pollInterval;

    @Autowired
    public PublicRunController(FlowStore flows, RunService runService, LicenseService license, Settings settings,
                               com.concentus.policy.OrgPolicyService policies) {
        this(flows, runService, license, settings, policies, new RateLimiter(FREE_REQUESTS_PER_MINUTE, 60_000),
                Duration.ofSeconds(1));
    }

    /** With the limiter and the poll interval exposed, so tests neither wait nor guess. */
    PublicRunController(FlowStore flows, RunService runService, LicenseService license, Settings settings,
                        com.concentus.policy.OrgPolicyService policies, RateLimiter limiter, Duration pollInterval) {
        this.flows = flows;
        this.runService = runService;
        this.license = license;
        this.settings = settings;
        this.policies = policies;
        this.limiter = limiter;
        this.pollInterval = pollInterval;
    }

    /** The request body. One field, so a client has nothing to get wrong. */
    public record RunRequest(String input) {
    }

    @PostMapping("/{flowId}/run")
    public ResponseEntity<Map<String, Object>> run(
            @PathVariable String flowId,
            @RequestBody(required = false) RunRequest body,
            @RequestParam(defaultValue = "true") boolean wait,
            @RequestParam(required = false) Integer timeoutSeconds,
            HttpServletRequest request) {
        FlowGraph flow = authorize(flowId, bearer(request));

        String input = body == null || body.input() == null ? "" : body.input().strip();
        if (input.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send a JSON body with a non-empty \"input\".");
        }

        RunSummary started;
        try {
            started = runService.startTriggered(flow, prompt(TriggerSpec.from(flow), input), "api");
        } catch (IllegalStateException e) {
            // Not signed in, or at the budget ceiling: nothing the caller did, and worth a retry later.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        log.info("Published endpoint started run {} for flow '{}'.", started.id(), flow.name());
        if (!wait) return accepted(started.id(), started.status());

        long deadline = System.currentTimeMillis() + clampTimeout(timeoutSeconds) * 1000L;
        return await(started.id(), deadline);
    }

    /** For a caller that got a 202 and wants the answer once it exists. */
    @GetMapping("/{flowId}/runs/{runId}")
    public ResponseEntity<Map<String, Object>> poll(@PathVariable String flowId, @PathVariable String runId,
                                                    HttpServletRequest request) {
        authorize(flowId, bearer(request));
        // A run of another flow is "no such run" here: the token authorizes this flow's runs only.
        AgentRun run = runService.get(runId)
                .filter(r -> flowId.equals(r.flowId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        return settled(run) ? finished(run) : accepted(run.id, run.status);
    }

    /**
     * A minimal chat page: a message list, an input box, and calls to the endpoint above with
     * {@code wait=true}. The token travels in this page's URL — which is why it is a demo surface
     * and says so on the page. No framework and nothing interpolated: the page reads the flow id
     * and the token from its own location, so the HTML is one constant string.
     */
    @GetMapping(value = "/{flowId}/chat", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> chat(@PathVariable String flowId,
                                       @RequestParam(required = false) String token) {
        authorize(flowId, token);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(CHAT_PAGE);
    }

    // ------------------------------------------------------------------ the pieces

    /**
     * The flow, if this token may start it. Order matters: the limiter runs before the comparison
     * so guessing is throttled, and the 404 is the same whether the flow is missing or unpublished.
     */
    private FlowGraph authorize(String flowId, String presented) {
        int allowance = allowancePerMinute();
        if (allowance > 0 && !limiter.tryAcquire(keyOf(presented), allowance)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests for this token: the limit is " + allowance + " a minute ("
                            + tierLabel() + "). Try again in a minute.");
        }
        FlowGraph flow;
        try {
            flow = flows.get(flowId).orElse(null);
        } catch (IllegalArgumentException e) {
            flow = null; // an id the store would not even look up is, for this endpoint, absent
        }
        TriggerSpec trigger = flow == null ? null : TriggerSpec.from(flow);
        if (trigger == null || !trigger.publishedWithToken()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow");
        }
        // Published but not yet approved, where the organization requires approval: the same
        // 404 as unpublished, on purpose. The door is not open, and a different answer would tell
        // a caller that a door exists and is merely waiting.
        if (policies != null && policies.publishBlocked(flowId, trigger.publishToken())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow");
        }
        if (presented == null || presented.isBlank() || !constantTimeEquals(trigger.publishToken(), presented)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return flow;
    }

    /**
     * How many requests a token may make this minute: 0 means no limit at all.
     *
     * <p>Enterprise reads the setting each time — an admin lowering it should not have to restart
     * the server to make it bite — and unset means unlimited, which is what the tier promises.
     * Team is the constant, whatever the setting says: the setting is the Enterprise feature. A
     * free installation keeps the figure the endpoint always had.
     */
    int allowancePerMinute() {
        if (license.allows(Feature.UNLIMITED_ENDPOINT_RATE)) {
            return Math.max(0, settings.number(SettingsCatalog.ENDPOINT_RATE_PER_MINUTE, 0));
        }
        if (license.teamTier()) return Feature.TEAM_ENDPOINT_RATE_PER_MINUTE;
        return FREE_REQUESTS_PER_MINUTE;
    }

    /** Where the figure in a 429 comes from, so the caller knows what would change it. */
    private String tierLabel() {
        if (license.allows(Feature.UNLIMITED_ENDPOINT_RATE)) {
            return "Enterprise license, the " + SettingsCatalog.ENDPOINT_RATE_PER_MINUTE + " setting";
        }
        return license.teamTier() ? "Team license" : "free installation";
    }

    /** The bearer token on the request, or null when there is none. */
    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** Constant-time compare so a wrong token cannot be recovered by timing the response. */
    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The limiter's key for a token: its hash, never the token. The limiter keeps its keys in
     * memory for a minute, and a map of live secrets is not something to leave lying around.
     */
    private static String keyOf(String presented) {
        String raw = presented == null ? "" : presented;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * The run's first message: the Input node's instruction, the facts this controller can vouch
     * for, and the caller's input fenced as untrusted — it is text from outside, exactly as a
     * webhook payload is.
     */
    static String prompt(TriggerSpec trigger, String input) {
        String instruction = trigger.prompt() == null || trigger.prompt().isBlank()
                ? "A request arrived through this flow's published endpoint. Answer it."
                : trigger.prompt();
        String brief = Texts.brief(input, MAX_INPUT);
        return instruction
                + "\n\nVerified request metadata (established by Concentus, not by the request):"
                + "\n- received: " + Instant.now()
                + "\n- via: published endpoint"
                + "\n- input chars: " + brief.length()
                + "\n\n" + UntrustedContent.fenced("request input", brief);
    }

    static int clampTimeout(Integer requested) {
        if (requested == null) return DEFAULT_TIMEOUT_SECONDS;
        return Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, requested));
    }

    /** Looks at the run every {@link #pollInterval} until it has an answer or the deadline passes. */
    private ResponseEntity<Map<String, Object>> await(String runId, long deadline) {
        while (true) {
            AgentRun run = runService.get(runId).orElse(null);
            if (run == null) return accepted(runId, "UNKNOWN");
            if (settled(run)) return finished(run);
            if (System.currentTimeMillis() >= deadline) return accepted(run.id, run.status);
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return accepted(run.id, run.status);
            }
        }
    }

    /**
     * Whether the run has something to answer with. Finished states, the state where the run
     * stopped to ask the caller something — its question IS the output — and an idle session that
     * has already spoken. A run idle with nothing said yet is one that has not begun its first
     * turn, which is where every local run spends its first moment.
     */
    static boolean settled(AgentRun run) {
        return switch (run.status) {
            case "COMPLETED", "ERROR", "TERMINATED", "AWAITING_ANSWER" -> true;
            case "IDLE" -> run.finalOutput() != null;
            default -> false;
        };
    }

    private static ResponseEntity<Map<String, Object>> accepted(String runId, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", status);
        return ResponseEntity.accepted().body(body);
    }

    private static ResponseEntity<Map<String, Object>> finished(AgentRun run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id);
        body.put("status", run.status);
        body.put("output", run.finalOutput());
        if (run.error != null) body.put("error", run.error);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ the chat page

    private static final String CHAT_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Concentus — flow chat</title>
            <style>
              body { margin: 0; font: 15px/1.5 system-ui, sans-serif; background: #f6f6f4; color: #222; }
              main { max-width: 720px; margin: 0 auto; padding: 24px 16px 120px; }
              h1 { font-size: 18px; margin: 0 0 4px; }
              .note { color: #666; font-size: 13px; margin: 0 0 20px; }
              .msg { padding: 10px 14px; border-radius: 10px; margin: 8px 0; white-space: pre-wrap; word-break: break-word; }
              .you { background: #dfe9ff; margin-left: 15%; }
              .flow { background: #fff; border: 1px solid #ddd; margin-right: 15%; }
              .error { background: #ffe3e3; border: 1px solid #f5b5b5; }
              .pending { color: #666; font-style: italic; }
              form { position: fixed; left: 0; right: 0; bottom: 0; background: #fff; border-top: 1px solid #ddd; padding: 12px 16px; display: flex; gap: 8px; }
              form > div { max-width: 720px; margin: 0 auto; display: flex; gap: 8px; width: 100%; }
              textarea { flex: 1; resize: none; font: inherit; padding: 8px 10px; border: 1px solid #bbb; border-radius: 8px; }
              button { font: inherit; padding: 8px 16px; border: 0; border-radius: 8px; background: #2d5bff; color: #fff; cursor: pointer; }
              button:disabled { background: #9db0ff; cursor: default; }
            </style>
            </head>
            <body>
            <main>
              <h1>Flow chat</h1>
              <p class="note">A demo surface for a published Concentus flow: each message starts a run and this page
              waits for its final output. The token is in this page's address — do not share the link.</p>
              <div id="log"></div>
            </main>
            <form id="f"><div>
              <textarea id="in" rows="2" placeholder="Type a message and press Enter" autofocus></textarea>
              <button id="send" type="submit">Send</button>
            </div></form>
            <script>
            (function () {
              var flowId = location.pathname.split('/').slice(-2, -1)[0];
              var token = new URLSearchParams(location.search).get('token') || '';
              var base = location.origin + '/api/public/flows/' + encodeURIComponent(flowId);
              var log = document.getElementById('log');
              var input = document.getElementById('in');
              var send = document.getElementById('send');
              var form = document.getElementById('f');

              function add(kind, text) {
                var el = document.createElement('div');
                el.className = 'msg ' + kind;
                el.textContent = text;
                log.appendChild(el);
                window.scrollTo(0, document.body.scrollHeight);
                return el;
              }
              function headers() {
                return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token };
              }
              function finish(pending, status, body) {
                if (status === 200) {
                  pending.className = 'msg flow';
                  pending.textContent = body.output || '(the run finished without a message)';
                  if (body.error) add('error', body.error);
                } else {
                  pending.className = 'msg error';
                  pending.textContent = (body && body.error) || ('HTTP ' + status);
                }
              }
              function poll(runId, pending) {
                fetch(base + '/runs/' + encodeURIComponent(runId), { headers: headers() })
                  .then(function (r) { return r.json().then(function (b) { return [r.status, b]; }); })
                  .then(function (sb) {
                    if (sb[0] === 202) { setTimeout(function () { poll(runId, pending); }, 2000); }
                    else { finish(pending, sb[0], sb[1]); }
                  })
                  .catch(function (e) { finish(pending, 0, { error: String(e) }); });
              }
              function submit() {
                var text = input.value.trim();
                if (!text) return;
                input.value = '';
                add('you', text);
                var pending = add('msg pending', 'Running…');
                send.disabled = true;
                fetch(base + '/run?wait=true&timeoutSeconds=300', { method: 'POST', headers: headers(), body: JSON.stringify({ input: text }) })
                  .then(function (r) { return r.json().then(function (b) { return [r.status, b]; }); })
                  .then(function (sb) {
                    if (sb[0] === 202 && sb[1] && sb[1].runId) { poll(sb[1].runId, pending); }
                    else { finish(pending, sb[0], sb[1]); }
                  })
                  .catch(function (e) { finish(pending, 0, { error: String(e) }); })
                  .then(function () { send.disabled = false; input.focus(); });
              }
              form.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
              input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
              });
            })();
            </script>
            </body>
            </html>
            """;
}
