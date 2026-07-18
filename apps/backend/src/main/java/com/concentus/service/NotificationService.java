package com.concentus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts a JSON payload to a flow's notification webhook when one of its runs fails. The body
 * carries both a Slack-compatible {@code text} field and structured run details, so the same URL
 * works for a Slack/Discord incoming webhook or a custom endpoint. Fire-and-forget.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);
        return t;
    });

    public NotificationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void runFailed(AgentRun run) {
        String url = run.notifyWebhook;
        if (url == null || url.isBlank()) return;
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            log.warn("Ignoring notification webhook for run {} — not an http(s) URL.", run.id);
            return;
        }
        String flow = run.flowName == null ? "flow" : run.flowName;
        String reason = run.error == null ? "The run reported an error." : run.error;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "❌ Concentus: flow \"" + flow + "\" failed — " + reason);
        body.put("event", "run.failed");
        body.put("runId", run.id);
        body.put("flowId", run.flowId);
        body.put("flowName", flow);
        body.put("trigger", run.trigger);
        body.put("error", reason);
        body.put("outputTokens", run.totalOutputTokens);

        exec.submit(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 300) {
                    log.warn("Failure notification for run {} returned HTTP {}", run.id, res.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failure notification for run {} could not be delivered: {}", run.id, e.getMessage());
            }
        });
    }
}
