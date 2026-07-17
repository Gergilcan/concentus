package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
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

import java.util.Map;

/**
 * Inbound webhooks. An external service (e.g. Linear) POSTs an event to
 * {@code /api/webhooks/{flowId}?token=SECRET}; if the flow has a webhook Input node and the token
 * matches, a run starts with the event payload as its input.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final int MAX_PAYLOAD = 12000;

    private final FlowStore flows;
    private final RunService runService;

    public WebhookController(FlowStore flows, RunService runService) {
        this.flows = flows;
        this.runService = runService;
    }

    /** Lightweight connectivity check some providers make before saving a webhook. */
    @GetMapping("/{flowId}")
    public Map<String, String> ping(@PathVariable String flowId) {
        return Map.of("ok", "true", "flowId", flowId);
    }

    @PostMapping("/{flowId}")
    public ResponseEntity<Map<String, String>> receive(
            @PathVariable String flowId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = "X-Webhook-Token", required = false) String headerToken,
            @RequestBody(required = false) String body) {

        FlowGraph flow = flows.get(flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow"));

        TriggerSpec trigger = TriggerSpec.from(flow);
        if (!trigger.webhook()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flow has no webhook Input node.");
        }

        // Shared-secret check: the flow's secret must match the presented token (query or header).
        String presented = token != null ? token : headerToken;
        String secret = trigger.secret();
        if (secret != null && !secret.isBlank() && !secret.equals(presented)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook token.");
        }

        String payload = body == null ? "" : body;
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
}
