package com.concentus.service;

import com.concentus.model.RunEvent;
import com.concentus.secrets.CredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Carries an approval wait out of the app: Slack gets the plan and can answer it; Teams gets the
 * plan and cannot.
 *
 * <p>The whole design bends around one constraint: this backend runs on someone's machine with no
 * public URL, so nothing here may depend on being called back. Slack approval therefore works by
 * <b>polling the reactions</b> on the message this service posted — a ✅ approves, a ❌ rejects —
 * which is outbound-only and needs nothing but a bot token. Slack's Socket Mode could push instead
 * of poll, but costs an extra app-level token and a WebSocket to keep alive; a poll every few
 * seconds against one message is well inside rate limits and has no connection to babysit.
 *
 * <p>Teams has no equivalent: an incoming webhook is one-way, and anything interactive needs a Bot
 * Framework endpoint reachable from the internet — the exact thing being avoided. So Teams gets an
 * honest notification card that says where to answer, rather than buttons that could never work.
 *
 * <p>The decision itself stays in {@link RunService}: this service is handed two callbacks and
 * calls one of them, exactly as if a human had pressed the button in the app. If both reactions
 * are present, the reject wins — two people disagreeing is not an approval.
 */
@Service
public class RemoteApprovalService {

    private static final Logger log = LoggerFactory.getLogger(RemoteApprovalService.class);

    /** Between reaction polls. Slack's Tier-3 limit is ~50/min; one watch uses 12. */
    private static final long POLL_SECONDS = 5;
    /**
     * When a watch gives up. A request nobody answered in two days is stale — the run itself
     * keeps waiting in the app, which holds no connection and costs nothing.
     */
    private static final long WATCH_HOURS = 48;
    /** The plan excerpt sent out. Slack truncates around 4k chars; a plan's head says enough. */
    private static final int PLAN_EXCERPT_CHARS = 2500;

    private static final Set<String> APPROVE_REACTIONS =
            Set.of("white_check_mark", "heavy_check_mark", "+1", "thumbsup");
    private static final Set<String> REJECT_REACTIONS =
            Set.of("x", "-1", "thumbsdown", "no_entry_sign");

    /** The one HTTP seam, so tests exercise the protocol without a network. */
    interface Transport {
        /** POSTs JSON (or GETs when {@code body} is null) with a bearer token; returns the JSON reply. */
        JsonNode call(String url, String bearer, JsonNode body) throws Exception;
    }

    private static final class Watch {
        final AgentRun run;
        final String bearer;
        final String channel;
        final String ts;
        final Runnable approve;
        final Runnable reject;
        volatile ScheduledFuture<?> task;
        volatile boolean closed;
        final long deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WATCH_HOURS);

        Watch(AgentRun run, String bearer, String channel, String ts, Runnable approve, Runnable reject) {
            this.run = run;
            this.bearer = bearer;
            this.channel = channel;
            this.ts = ts;
            this.approve = approve;
            this.reject = reject;
        }
    }

    private final CredentialResolver credentials;
    private final ObjectMapper mapper;
    private final Transport transport;
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-approvals");
        t.setDaemon(true);
        return t;
    });

    // @Autowired explicitly: with the package-private test constructor alongside, Spring refuses
    // to pick between the two and fails at startup with "No default constructor found".
    @org.springframework.beans.factory.annotation.Autowired
    public RemoteApprovalService(CredentialResolver credentials, ObjectMapper mapper) {
        this(credentials, mapper, null);
    }

    RemoteApprovalService(CredentialResolver credentials, ObjectMapper mapper, Transport transport) {
        this.credentials = credentials;
        this.mapper = mapper;
        this.transport = transport != null ? transport : new HttpTransport();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * A run just stopped to ask a human. Posts to whatever this flow configured; failures land in
     * the run's own console, because "why did Slack never ping" is a question about this run.
     */
    public void runAwaitingApproval(AgentRun run, Runnable approve, Runnable reject) {
        notifyTeams(run);
        watchSlack(run, approve, reject);
    }

    /**
     * The wait ended — from a reaction, the app, or a stop. Cancels the poll and rewrites the
     * Slack message so the channel shows the outcome instead of instructions nobody can follow
     * any more. All message updating happens here, whoever decided: one path, one wording.
     */
    public void settled(String runId, String decision) {
        Watch w = watches.remove(runId);
        if (w == null || w.closed) return;
        w.closed = true;
        if (w.task != null) w.task.cancel(false);
        String text = switch (decision) {
            case "approved" -> "✅ Approved — \"" + flowName(w.run) + "\" is carrying out its plan.";
            case "rejected" -> "🚫 Rejected — \"" + flowName(w.run) + "\" changed nothing.";
            default -> "⏹ \"" + flowName(w.run) + "\" is no longer waiting for approval.";
        };
        ObjectNode body = mapper.createObjectNode();
        body.put("channel", w.channel);
        body.put("ts", w.ts);
        body.put("text", text + " (run " + w.run.id + ")");
        // Off this thread: settled() runs inside the approve/reject request, and an HTTP round
        // trip to Slack must not be what the app's own button waits on.
        scheduler.execute(() -> {
            try {
                slack(w.bearer, "chat.update", body);
            } catch (Exception e) {
                log.debug("Slack message update for run {} failed: {}", runId, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ slack

    private void watchSlack(AgentRun run, Runnable approve, Runnable reject) {
        String channel = run.approvalSlackChannel;
        if (channel == null || channel.isBlank()) return;
        String token = credentials.resolve(run.approvalSlackCredentialId);
        if (token == null || token.isBlank()) {
            run.emit(RunEvent.of("system", "Slack approval is configured but its bot-token "
                    + "credential resolves to nothing — approve from the app instead."));
            return;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("channel", channel.trim());
        body.put("text", "⏳ Concentus: \"" + flowName(run) + "\" is waiting for approval.\n\n"
                + planExcerpt(run)
                + "\n\nReact with ✅ to approve or ❌ to reject. (run " + run.id + ")");
        JsonNode res;
        try {
            res = slack(token, "chat.postMessage", body);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", "Slack approval request could not be sent: "
                    + e.getMessage() + " — approve from the app instead."));
            return;
        }
        if (!res.path("ok").asBoolean(false)) {
            // Slack's error names are the actionable part: channel_not_found means the id is
            // wrong, not_in_channel means the bot was never invited.
            run.emit(RunEvent.of("system", "Slack rejected the approval request ("
                    + res.path("error").asText("unknown error")
                    + ") — approve from the app instead."));
            return;
        }

        Watch w = new Watch(run, token, res.path("channel").asText(channel.trim()),
                res.path("ts").asText(), approve, reject);
        watches.put(run.id, w);
        w.task = scheduler.scheduleWithFixedDelay(() -> pollOnce(run.id), POLL_SECONDS,
                POLL_SECONDS, TimeUnit.SECONDS);
        run.emit(RunEvent.of("system",
                "Approval request posted to Slack — a ✅ reaction there approves this run."));
    }

    /** One reaction check. Package-private so tests drive the protocol without a scheduler. */
    void pollOnce(String runId) {
        Watch w = watches.get(runId);
        if (w == null || w.closed) return;

        // The app may have decided, or the run may have been stopped: the watch is then noise.
        if (!"AWAITING_APPROVAL".equals(w.run.status)) {
            settled(runId, "closed");
            return;
        }
        if (System.currentTimeMillis() > w.deadline) {
            w.run.emit(RunEvent.of("system", "Slack approval request expired after " + WATCH_HOURS
                    + "h with no reaction — the run still waits in the app."));
            settled(runId, "closed");
            return;
        }

        JsonNode res;
        try {
            res = slack(w.bearer, "reactions.get?channel="
                    + URLEncoder.encode(w.channel, StandardCharsets.UTF_8)
                    + "&timestamp=" + URLEncoder.encode(w.ts, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            log.debug("Slack reaction poll for run {} failed: {}", runId, e.getMessage());
            return; // transient — the next tick retries
        }
        if (!res.path("ok").asBoolean(false)) return;

        boolean approved = false;
        boolean rejected = false;
        for (JsonNode reaction : res.path("message").path("reactions")) {
            String name = reaction.path("name").asText();
            if (APPROVE_REACTIONS.contains(name)) approved = true;
            if (REJECT_REACTIONS.contains(name)) rejected = true;
        }
        if (!approved && !rejected) return;

        // Reject wins a disagreement: two people answering differently is not an approval, and
        // the safe direction for a feature whose whole point is asking first is "don't act".
        Runnable decision = rejected ? w.reject : w.approve;
        w.run.emit(RunEvent.of("system",
                rejected ? "Rejected via Slack reaction." : "Approved via Slack reaction."));
        try {
            decision.run(); // RunService flips the run and calls settled(), which closes this watch
        } catch (RuntimeException e) {
            // The app got there first; the state it chose stands and the watch just closes.
            settled(runId, "closed");
        }
    }

    private JsonNode slack(String bearer, String method, JsonNode body) throws Exception {
        return transport.call("https://slack.com/api/" + method, bearer, body);
    }

    // ------------------------------------------------------------------ teams

    /**
     * Teams is told, not asked — see the class comment. The card names where to actually answer,
     * so it never reads as a broken button.
     */
    private void notifyTeams(AgentRun run) {
        String url = run.approvalTeamsWebhook;
        if (url == null || url.isBlank()) return;
        if (!url.startsWith("https://")) {
            run.emit(RunEvent.of("system", "Teams approval webhook ignored — not an https URL."));
            return;
        }

        ObjectNode card = mapper.createObjectNode();
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        var cardBody = card.putArray("body");
        ObjectNode title = cardBody.addObject();
        title.put("type", "TextBlock");
        title.put("weight", "Bolder");
        title.put("wrap", true);
        title.put("text", "⏳ \"" + flowName(run) + "\" is waiting for approval");
        ObjectNode plan = cardBody.addObject();
        plan.put("type", "TextBlock");
        plan.put("wrap", true);
        plan.put("text", planExcerpt(run));
        ObjectNode where = cardBody.addObject();
        where.put("type", "TextBlock");
        where.put("wrap", true);
        where.put("isSubtle", true);
        where.put("text", "Approve or reject from Concentus (run " + run.id + ")"
                + (run.approvalSlackChannel != null && !run.approvalSlackChannel.isBlank()
                        ? ", or react in Slack." : ". This channel cannot carry your reply."));

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "message");
        ObjectNode attachment = envelope.putArray("attachments").addObject();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.set("content", card);

        try {
            transport.call(url, null, envelope);
            run.emit(RunEvent.of("system", "Approval notice posted to Teams (notification only)."));
        } catch (Exception e) {
            run.emit(RunEvent.of("system",
                    "Teams approval notice could not be sent: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ shared

    private static String flowName(AgentRun run) {
        return run.flowName == null || run.flowName.isBlank() ? "flow" : run.flowName;
    }

    /** The plan the agent proposed — its last message — cut to what a chat message can carry. */
    private static String planExcerpt(AgentRun run) {
        String plan = run.finalOutput();
        if (plan == null || plan.isBlank()) {
            return "(The run produced no plan text — open the app to see its console.)";
        }
        return plan.length() <= PLAN_EXCERPT_CHARS
                ? plan
                : plan.substring(0, PLAN_EXCERPT_CHARS) + "\n… (truncated — full plan in the app)";
    }

    /** Real HTTP. Kept dumb: JSON in, JSON out, bearer when given, GET when there is no body. */
    private final class HttpTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

        @Override
        public JsonNode call(String url, String bearer, JsonNode body) throws Exception {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10));
            if (bearer != null) req.header("Authorization", "Bearer " + bearer);
            if (body != null) {
                req.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                req.GET();
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            String text = res.body();
            // A Teams webhook answers "1" or empty rather than JSON; normalize to an empty object.
            if (text == null || text.isBlank() || !text.trim().startsWith("{")) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(text);
        }
    }

    /** How many approval requests are being watched right now — for tests and diagnostics. */
    int activeWatches() {
        return watches.size();
    }

    /** For tests: the emoji sets are the contract users learn, so they are asserted directly. */
    static List<Set<String>> reactionSets() {
        return List.of(APPROVE_REACTIONS, REJECT_REACTIONS);
    }
}
