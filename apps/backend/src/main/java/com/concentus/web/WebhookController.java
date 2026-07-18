package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Inbound webhooks. An external service POSTs an event to {@code /api/webhooks/{flowId}}; if the
 * flow has a webhook Input node and the request authenticates, a run starts with the event payload
 * as its input.
 *
 * <p>Authentication is provider-agnostic and configured on the Input node with two fields: the
 * <b>secret</b> (issued by the provider — we never mint one) and the <b>parameter name</b> carrying
 * the proof. That parameter is read from the request headers, falling back to the query string, and
 * accepted if its value is either:
 *
 * <ul>
 *   <li>a hex HMAC-SHA256 of the <em>raw</em> request body signed with the secret — bare hex or the
 *       {@code sha256=<hex>} form; or</li>
 *   <li>the secret itself, for providers that just echo a static token back.</li>
 * </ul>
 *
 * <p>So Linear works by setting the parameter to {@code Linear-Signature} and pasting the signing
 * secret from its webhook detail page; GitHub by using {@code X-Hub-Signature-256}; a plain caller
 * by using {@code token} or any header name it can set — no provider-specific code either way.
 * See <a href="https://linear.app/developers/webhooks#securing-webhooks">Linear's docs</a>.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final int MAX_PAYLOAD = 12000;

    /** Linear recommends rejecting deliveries whose timestamp is over a minute off, to block replays. */
    private static final long MAX_TIMESTAMP_SKEW_MS = 60_000;

    private final FlowStore flows;
    private final RunService runService;
    private final ObjectMapper mapper;

    public WebhookController(FlowStore flows, RunService runService, ObjectMapper mapper) {
        this.flows = flows;
        this.runService = runService;
        this.mapper = mapper;
    }

    /** Lightweight connectivity check some providers make before saving a webhook. */
    @GetMapping("/{flowId}")
    public Map<String, String> ping(@PathVariable String flowId) {
        return Map.of("ok", "true", "flowId", flowId);
    }

    @PostMapping("/{flowId}")
    public ResponseEntity<Map<String, String>> receive(
            @PathVariable String flowId,
            // Bound as bytes, not String: the HMAC must cover the exact bytes the provider signed.
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {

        FlowGraph flow = flows.get(flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow"));

        TriggerSpec trigger = TriggerSpec.from(flow);
        if (!trigger.webhook()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flow has no webhook Input node.");
        }

        byte[] raw = body == null ? new byte[0] : body;
        String secret = trigger.secret();
        if (secret == null || secret.isBlank()) {
            // This endpoint starts agent runs, so it is never left unauthenticated.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This webhook has no secret configured. Paste the provider's secret into the Input node.");
        }

        String param = trigger.authParam();
        String presented = presentedValue(request, param);
        if (presented == null || presented.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing '" + param + "' — expected it as a header or query parameter.");
        }
        if (!authorized(raw, presented, secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid '" + param + "'.");
        }
        requireFreshTimestamp(raw);

        String payload = new String(raw, StandardCharsets.UTF_8);
        if (payload.length() > MAX_PAYLOAD) {
            payload = payload.substring(0, MAX_PAYLOAD) + "\n…(payload truncated)";
        }
        String instruction = (trigger.prompt() == null || trigger.prompt().isBlank())
                ? "A webhook event was received. Decide what to do and act on it."
                : trigger.prompt();
        String prompt = instruction + "\n\nEvent payload (JSON):\n```json\n" + payload + "\n```";

        try {
            RunSummary run = runService.start(flow, prompt);
            log.info("Webhook triggered flow '{}' -> run {}", flow.name(), run.id());
            return ResponseEntity.accepted().body(Map.of("runId", run.id(), "status", run.status()));
        } catch (IllegalStateException e) {
            // e.g. not signed in — surface as 503 so the caller can retry later.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** Reads the configured parameter from the request headers, falling back to the query string. */
    private static String presentedValue(HttpServletRequest request, String param) {
        String fromHeader = request.getHeader(param); // header lookup is case-insensitive
        return fromHeader != null ? fromHeader : request.getParameter(param);
    }

    /**
     * One rule covering both provider styles, so no per-provider branching is needed:
     * the presented value authenticates if it is either an HMAC-SHA256 signature of the raw body
     * (Linear, GitHub, …) or the secret itself (providers that just echo a static token back).
     */
    private static boolean authorized(byte[] raw, String presented, String secret) {
        return signatureMatches(raw, presented, secret) || constantTimeEquals(secret, presented);
    }

    /**
     * Recomputes the HMAC-SHA256 of the raw body with the secret and compares it, in constant time,
     * to the presented signature. Accepts both bare hex (Linear) and the {@code sha256=<hex>} form
     * some providers use (GitHub).
     */
    private static boolean signatureMatches(byte[] raw, String presented, String secret) {
        String hex = presented.trim();
        int eq = hex.indexOf('=');
        if (eq >= 0) hex = hex.substring(eq + 1); // strip an "algo=" prefix
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException e) {
            return false; // not valid hex — treated as "not a signature", the token path may still match
        } catch (GeneralSecurityException e) {
            log.warn("Could not compute webhook signature", e);
            return false;
        }
    }

    /** Constant-time compare so a wrong token can't be recovered by timing the response. */
    private static boolean constantTimeEquals(String secret, String presented) {
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replay guard: a signature stays valid forever, so a captured delivery could be replayed.
     * Linear stamps each payload with {@code webhookTimestamp} (UNIX millis) for this.
     */
    private void requireFreshTimestamp(byte[] raw) {
        long sentAt;
        try {
            JsonNode ts = mapper.readTree(raw).get("webhookTimestamp");
            if (ts == null || !ts.canConvertToLong()) return; // not every payload carries one
            sentAt = ts.asLong();
        } catch (java.io.IOException e) {
            return; // body isn't JSON — the signature already proved it came from Linear
        }
        if (Math.abs(System.currentTimeMillis() - sentAt) > MAX_TIMESTAMP_SKEW_MS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Webhook timestamp is outside the allowed window (possible replay).");
        }
    }
}
