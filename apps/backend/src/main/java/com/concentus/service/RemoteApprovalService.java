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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Carries a run's wait for a human out of the app: Slack can answer it, Teams can only be told.
 *
 * <p>Two waits, one transport. A run stopped for APPROVAL is settled by a ✅/❌ reaction on the
 * message this service posted. A run that stopped to ASK something is settled by a threaded REPLY
 * to it — the reply becomes the run's next command. Same token, same poll loop, same TTL; the
 * difference is only what a poll looks for and what it does with the answer.
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

    /**
     * One posted message being watched. {@code approve}/{@code reject} belong to an approval
     * watch and {@code answer} to a question watch; a run is only ever in one of the two states,
     * so one map keyed by run id holds both.
     */
    private static final class Watch {
        final AgentRun run;
        /** "approval" (react to decide) or "answer" (reply in thread to continue). */
        final String kind;
        final String bearer;
        final String channel;
        final String ts;
        final Runnable approve;
        final Runnable reject;
        final java.util.function.Consumer<String> answer;
        volatile ScheduledFuture<?> task;
        volatile boolean closed;
        final long deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WATCH_HOURS);

        Watch(AgentRun run, String kind, String bearer, String channel, String ts,
              Runnable approve, Runnable reject, java.util.function.Consumer<String> answer) {
            this.run = run;
            this.kind = kind;
            this.bearer = bearer;
            this.channel = channel;
            this.ts = ts;
            this.approve = approve;
            this.reject = reject;
            this.answer = answer;
        }
    }

    private final CredentialResolver credentials;
    private final ObjectMapper mapper;
    private final Transport transport;
    /** Where the Telegram bot lives: installation-wide settings, read per request. */
    private final com.concentus.config.Settings settings;
    private final Map<String, TelegramWatch> telegramWatches = new ConcurrentHashMap<>();
    /** The bot's update cursor: getUpdates is one stream per bot, so one poller serves every watch. */
    private volatile long telegramOffset;
    private volatile ScheduledFuture<?> telegramPoll;
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-approvals");
        t.setDaemon(true);
        return t;
    });

    // @Autowired explicitly: with the package-private test constructor alongside, Spring refuses
    // to pick between the two and fails at startup with "No default constructor found".
    @org.springframework.beans.factory.annotation.Autowired
    public RemoteApprovalService(CredentialResolver credentials, ObjectMapper mapper,
                                 com.concentus.config.Settings settings) {
        this(credentials, mapper, null, settings);
    }

    RemoteApprovalService(CredentialResolver credentials, ObjectMapper mapper, Transport transport) {
        this(credentials, mapper, transport, com.concentus.config.Settings.none());
    }

    RemoteApprovalService(CredentialResolver credentials, ObjectMapper mapper, Transport transport,
                          com.concentus.config.Settings settings) {
        this.credentials = credentials;
        this.mapper = mapper;
        this.transport = transport != null ? transport : new HttpTransport();
        this.settings = settings == null ? com.concentus.config.Settings.none() : settings;
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
        notifyTeams(run, "⏳ \"" + flowName(run) + "\" is waiting for approval",
                "Approve or reject from Concentus (run " + run.id + ")",
                ", or react in Slack.", ". This channel cannot carry your reply.");
        watchSlack(run, approve, reject);
        watchTelegram(run, "approval", approve, reject, null);
    }

    /**
     * The wait ended — from a reaction, the app, or a stop. Cancels the poll and rewrites the
     * Slack message so the channel shows the outcome instead of instructions nobody can follow
     * any more. All message updating happens here, whoever decided: one path, one wording.
     */
    public void settled(String runId, String decision) {
        settleTelegram(runId, decision);
        Watch w = watches.remove(runId);
        if (w == null || w.closed) return;
        w.closed = true;
        if (w.task != null) w.task.cancel(false);
        // A question is left standing. Rewriting that message would erase the question itself
        // from the channel — and unlike an approval, there are no instructions in it to go stale:
        // a reply to a run that has moved on is answered in the thread when it arrives.
        if ("answer".equals(w.kind)) return;
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
                + finalOutputExcerpt(run)
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

        Watch w = new Watch(run, "approval", token, res.path("channel").asText(channel.trim()),
                res.path("ts").asText(), approve, reject, null);
        watches.put(run.id, w);
        w.task = scheduler.scheduleWithFixedDelay(() -> pollOnce(run.id), POLL_SECONDS,
                POLL_SECONDS, TimeUnit.SECONDS);
        run.emit(RunEvent.of("system",
                "Approval request posted to Slack — a ✅ reaction there approves this run."));
    }

    // ------------------------------------------------------------------ questions

    /**
     * A run stopped to ASK the user something. Posts the question where the flow's approvals go,
     * and takes the first threaded reply as the run's next command.
     *
     * <p>The full human loop from a phone: the run asks, you reply in the thread, the run carries
     * on. The reply is used verbatim — this service does not interpret it, exactly as the app's
     * own command box does not.
     */
    public void runAwaitingAnswer(AgentRun run, java.util.function.Consumer<String> answer) {
        notifyTeams(run, "❓ \"" + flowName(run) + "\" asked you something",
                "Answer from Concentus (run " + run.id + ")",
                ", or reply in the Slack thread.", ". This channel cannot carry your reply.");
        watchSlackAnswer(run, answer);
        watchTelegram(run, "answer", null, null, answer);
    }

    private void watchSlackAnswer(AgentRun run, java.util.function.Consumer<String> answer) {
        String channel = run.approvalSlackChannel;
        if (channel == null || channel.isBlank()) return;
        String token = credentials.resolve(run.approvalSlackCredentialId);
        if (token == null || token.isBlank()) {
            run.emit(RunEvent.of("system", "Slack is configured for this flow but its bot-token "
                    + "credential resolves to nothing — answer from the app instead."));
            return;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("channel", channel.trim());
        body.put("text", "❓ Concentus: \"" + flowName(run) + "\" asked you something.\n\n"
                + finalOutputExcerpt(run)
                + "\n\nReply in this thread and the run continues with your answer. (run "
                + run.id + ")");
        JsonNode res;
        try {
            res = slack(token, "chat.postMessage", body);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", "The question could not be sent to Slack: "
                    + e.getMessage() + " — answer from the app instead."));
            return;
        }
        if (!res.path("ok").asBoolean(false)) {
            run.emit(RunEvent.of("system", "Slack rejected the question ("
                    + res.path("error").asText("unknown error") + ") — answer from the app instead."));
            return;
        }

        Watch w = new Watch(run, "answer", token, res.path("channel").asText(channel.trim()),
                res.path("ts").asText(), null, null, answer);
        watches.put(run.id, w);
        w.task = scheduler.scheduleWithFixedDelay(() -> pollOnce(run.id), POLL_SECONDS,
                POLL_SECONDS, TimeUnit.SECONDS);
        run.emit(RunEvent.of("system",
                "Question posted to Slack — a reply in that thread continues this run."));
    }

    /**
     * One check of the question's thread. The FIRST human reply wins and closes the watch: a
     * second reply to a question already answered would be a command the run never asked for.
     */
    private void pollAnswer(Watch w) {
        JsonNode res;
        try {
            res = slack(w.bearer, "conversations.replies?channel="
                    + URLEncoder.encode(w.channel, StandardCharsets.UTF_8)
                    + "&ts=" + URLEncoder.encode(w.ts, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            log.debug("Slack thread poll for run {} failed: {}", w.run.id, e.getMessage());
            return; // transient — the next tick retries
        }
        if (!res.path("ok").asBoolean(false)) return;

        String reply = null;
        for (JsonNode message : res.path("messages")) {
            // The parent is the question itself, and anything with a bot_id is this service —
            // including the ✔ it posts after answering. Neither is a human replying.
            if (w.ts.equals(message.path("ts").asText())) continue;
            if (!message.path("bot_id").asText("").isBlank()) continue;
            String text = message.path("text").asText("").trim();
            if (!text.isEmpty()) {
                reply = text;
                break;
            }
        }
        if (reply == null) return;

        closeWatch(w);
        w.run.emit(RunEvent.of("system", "Answered via Slack: " + reply));
        try {
            w.answer.accept(reply);
        } catch (RuntimeException e) {
            // The app got there first, or the run moved on. Say so in the thread rather than
            // leaving someone believing their reply is being worked on.
            replyInThread(w, "⚠ That reply arrived too late — the run was no longer waiting.");
            return;
        }
        replyInThread(w, "✔ Sent to \"" + flowName(w.run) + "\" — it is working on your answer.");
    }

    /** Posts into the question's own thread, so the confirmation sits under what it answers. */
    private void replyInThread(Watch w, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("channel", w.channel);
        body.put("thread_ts", w.ts);
        body.put("text", text);
        scheduler.execute(() -> {
            try {
                slack(w.bearer, "chat.postMessage", body);
            } catch (Exception e) {
                log.debug("Slack thread reply for run {} failed: {}", w.run.id, e.getMessage());
            }
        });
    }

    /** Stops a watch polling, without touching the message it posted. */
    private void closeWatch(Watch w) {
        w.closed = true;
        watches.remove(w.run.id, w);
        if (w.task != null) w.task.cancel(false);
    }

    /** One reaction check. Package-private so tests drive the protocol without a scheduler. */
    void pollOnce(String runId) {
        Watch w = watches.get(runId);
        if (w == null || w.closed) return;

        boolean answering = "answer".equals(w.kind);
        // The app may have answered or decided, or the run may have been stopped: the watch is
        // then noise. This is also what closes a question watch when the reply came from the app.
        if (!(answering ? "AWAITING_ANSWER" : "AWAITING_APPROVAL").equals(w.run.status)) {
            settled(runId, "closed");
            return;
        }
        if (System.currentTimeMillis() > w.deadline) {
            w.run.emit(RunEvent.of("system", "The Slack " + (answering ? "question" : "approval request")
                    + " expired after " + WATCH_HOURS + "h with no reply — the run still waits in the app."));
            settled(runId, "closed");
            return;
        }
        if (answering) {
            pollAnswer(w);
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

    // ------------------------------------------------------------------ telegram

    /**
     * One approval request or one question posted to the Telegram chat, and what decides it.
     * A message id rather than Slack's channel+ts; the bot token is not kept here because it
     * is read from the settings at every call, so a rotated token takes effect at once.
     */
    private static final class TelegramWatch {
        final AgentRun run;
        final String kind;
        final String chatId;
        final long messageId;
        final Runnable approve;
        final Runnable reject;
        final java.util.function.Consumer<String> answer;
        volatile boolean closed;
        final long deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WATCH_HOURS);

        TelegramWatch(AgentRun run, String kind, String chatId, long messageId,
                      Runnable approve, Runnable reject, java.util.function.Consumer<String> answer) {
            this.run = run;
            this.kind = kind;
            this.chatId = chatId;
            this.messageId = messageId;
            this.approve = approve;
            this.reject = reject;
            this.answer = answer;
        }
    }

    private String telegramToken() {
        return settings.get("approvals.telegram.bot-token", "").trim();
    }

    private String telegramChat() {
        return settings.get("approvals.telegram.chat-id", "").trim();
    }

    /**
     * Posts the request to Telegram and starts the bot's poller if it is not running.
     *
     * <p>Telegram is the channel that can carry the answer back without a public URL: a bot is
     * polled with getUpdates, the buttons on the message come back as callback queries, and a
     * reply to the question comes back as a message that names what it replies to. Teams has
     * no equivalent; Slack has reactions. Nothing here is a webhook.
     */
    private void watchTelegram(AgentRun run, String kind, Runnable approve, Runnable reject,
                               java.util.function.Consumer<String> answer) {
        String token = telegramToken();
        String chat = telegramChat();
        if (token.isEmpty() || chat.isEmpty()) return;
        boolean answering = "answer".equals(kind);

        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chat);
        body.put("text", (answering
                ? "\u2753 Concentus: \"" + flowName(run) + "\" asked you something.\n\n" + finalOutputExcerpt(run)
                        + "\n\nReply to this message and the run continues with your answer."
                : "\u23f3 Concentus: \"" + flowName(run) + "\" is waiting for approval.\n\n" + finalOutputExcerpt(run))
                + " (run " + run.id + ")");
        if (answering) {
            body.putObject("reply_markup").put("force_reply", true).put("selective", true);
        } else {
            var row = body.putObject("reply_markup").putArray("inline_keyboard").addArray();
            row.addObject().put("text", "\u2705 Approve").put("callback_data", "approve:" + run.id);
            row.addObject().put("text", "\u274c Reject").put("callback_data", "reject:" + run.id);
        }
        JsonNode res;
        try {
            res = telegram(token, "sendMessage", body);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", "The Telegram " + (answering ? "question" : "approval request")
                    + " could not be sent: " + e.getMessage() + " — use the app instead."));
            return;
        }
        if (!res.path("ok").asBoolean(false)) {
            run.emit(RunEvent.of("system", "Telegram rejected the " + (answering ? "question" : "approval request")
                    + " (" + res.path("description").asText("unknown error") + ") — use the app instead."));
            return;
        }
        long messageId = res.path("result").path("message_id").asLong(0);
        telegramWatches.put(run.id, new TelegramWatch(run, kind, chat, messageId, approve, reject, answer));
        synchronized (this) {
            if (telegramPoll == null) {
                telegramPoll = scheduler.scheduleWithFixedDelay(this::pollTelegramOnce, POLL_SECONDS,
                        POLL_SECONDS, TimeUnit.SECONDS);
            }
        }
        run.emit(RunEvent.of("system", answering
                ? "Question posted to Telegram — a reply there answers it."
                : "Approval request posted to Telegram — the buttons there decide."));
    }

    /** One getUpdates round for every Telegram watch. Package-private so tests drive it without a clock. */
    void pollTelegramOnce() {
        // Expire and close first, so a stale watch never consumes a fresh update.
        for (TelegramWatch w : java.util.List.copyOf(telegramWatches.values())) {
            boolean answering = "answer".equals(w.kind);
            if (!(answering ? "AWAITING_ANSWER" : "AWAITING_APPROVAL").equals(w.run.status)) {
                settleTelegram(w.run.id, "closed");
            } else if (System.currentTimeMillis() > w.deadline) {
                w.run.emit(RunEvent.of("system", "The Telegram " + (answering ? "question" : "approval request")
                        + " expired after " + WATCH_HOURS + "h with no reply — the run still waits in the app."));
                settleTelegram(w.run.id, "closed");
            }
        }
        if (telegramWatches.isEmpty()) {
            synchronized (this) {
                if (telegramPoll != null) {
                    telegramPoll.cancel(false);
                    telegramPoll = null;
                }
            }
            return;
        }
        String token = telegramToken();
        if (token.isEmpty()) return;
        ObjectNode body = mapper.createObjectNode();
        body.put("offset", telegramOffset);
        body.put("timeout", 0);
        body.putArray("allowed_updates").add("callback_query").add("message");
        JsonNode res;
        try {
            res = telegram(token, "getUpdates", body);
        } catch (Exception e) {
            log.debug("Telegram poll failed: {}", e.getMessage());
            return; // transient — the next tick retries
        }
        if (!res.path("ok").asBoolean(false)) return;
        for (JsonNode update : res.path("result")) {
            long id = update.path("update_id").asLong(-1);
            if (id >= 0) telegramOffset = Math.max(telegramOffset, id + 1);
            if (update.has("callback_query")) {
                handleCallback(token, update.path("callback_query"));
            } else if (update.has("message")) {
                handleReply(update.path("message"));
            }
        }
    }

    private void handleCallback(String token, JsonNode query) {
        String data = query.path("data").asText("");
        int sep = data.indexOf(':');
        if (sep <= 0) return;
        String verb = data.substring(0, sep);
        String runId = data.substring(sep + 1);
        TelegramWatch w = telegramWatches.get(runId);
        ObjectNode ack = mapper.createObjectNode();
        ack.put("callback_query_id", query.path("id").asText(""));
        if (w == null || w.closed || !"approval".equals(w.kind)) {
            ack.put("text", "This request is no longer waiting.");
            try {
                telegram(token, "answerCallbackQuery", ack);
            } catch (Exception ignored) {
                // the button just stays; nothing to decide
            }
            return;
        }
        boolean rejected = !"approve".equals(verb);
        ack.put("text", rejected ? "Rejected." : "Approved.");
        try {
            telegram(token, "answerCallbackQuery", ack);
        } catch (Exception ignored) {
            // the decision below is what matters; the toast is a courtesy
        }
        w.run.emit(RunEvent.of("system", rejected ? "Rejected via Telegram." : "Approved via Telegram."));
        try {
            (rejected ? w.reject : w.approve).run(); // RunService flips the run and calls settled()
        } catch (RuntimeException e) {
            // The app got there first; the state it chose stands and the watch just closes.
            settleTelegram(runId, "closed");
        }
    }

    private void handleReply(JsonNode message) {
        long repliedTo = message.path("reply_to_message").path("message_id").asLong(-1);
        String text = message.path("text").asText("");
        if (repliedTo < 0 || text.isBlank()) return;
        for (TelegramWatch w : java.util.List.copyOf(telegramWatches.values())) {
            if (w.closed || !"answer".equals(w.kind) || w.messageId != repliedTo) continue;
            w.run.emit(RunEvent.of("system", "Answered via Telegram."));
            try {
                w.answer.accept(text); // RunService continues the run and calls settled()
            } catch (RuntimeException e) {
                settleTelegram(w.run.id, "closed");
            }
            return;
        }
    }

    private void settleTelegram(String runId, String decision) {
        TelegramWatch w = telegramWatches.remove(runId);
        if (w == null || w.closed) return;
        w.closed = true;
        if ("answer".equals(w.kind)) return; // the question stays readable, as in Slack
        String text = switch (decision) {
            case "approved" -> "\u2705 Approved — \"" + flowName(w.run) + "\" is carrying out its plan.";
            case "rejected" -> "\ud83d\udeab Rejected — \"" + flowName(w.run) + "\" changed nothing.";
            default -> "\u23f9 \"" + flowName(w.run) + "\" is no longer waiting for approval.";
        };
        String token = telegramToken();
        if (token.isEmpty()) return;
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", w.chatId);
        body.put("message_id", w.messageId);
        body.put("text", text + " (run " + w.run.id + ")");
        // Off this thread, for the reason the Slack update is: the app's own button must not
        // wait on a round trip to Telegram.
        scheduler.execute(() -> {
            try {
                telegram(token, "editMessageText", body);
            } catch (Exception e) {
                log.debug("Telegram message update for run {} failed: {}", runId, e.getMessage());
            }
        });
    }

    private JsonNode telegram(String token, String method, JsonNode body) throws Exception {
        // The token is part of the URL by Telegram's design; no bearer. Never logged.
        return transport.call("https://api.telegram.org/bot" + token + "/" + method, null, body);
    }

    // ------------------------------------------------------------------ teams

    /**
     * Teams is told, not asked — see the class comment. The card names where to actually answer,
     * so it never reads as a broken button.
     */
    private void notifyTeams(AgentRun run, String title, String where,
                             String slackSuffix, String noSlackSuffix) {
        String url = run.approvalTeamsWebhook;
        if (url == null || url.isBlank()) return;
        if (!url.startsWith("https://")) {
            run.emit(RunEvent.of("system", "Teams webhook ignored — not an https URL."));
            return;
        }

        ObjectNode card = mapper.createObjectNode();
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        var cardBody = card.putArray("body");
        ObjectNode titleBlock = cardBody.addObject();
        titleBlock.put("type", "TextBlock");
        titleBlock.put("weight", "Bolder");
        titleBlock.put("wrap", true);
        titleBlock.put("text", title);
        ObjectNode detail = cardBody.addObject();
        detail.put("type", "TextBlock");
        detail.put("wrap", true);
        detail.put("text", finalOutputExcerpt(run));
        ObjectNode whereBlock = cardBody.addObject();
        whereBlock.put("type", "TextBlock");
        whereBlock.put("wrap", true);
        whereBlock.put("isSubtle", true);
        whereBlock.put("text", where
                + (run.approvalSlackChannel != null && !run.approvalSlackChannel.isBlank()
                        ? slackSuffix : noSlackSuffix));

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "message");
        ObjectNode attachment = envelope.putArray("attachments").addObject();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.set("content", card);

        try {
            transport.call(url, null, envelope);
            run.emit(RunEvent.of("system", "Notice posted to Teams (notification only)."));
        } catch (Exception e) {
            run.emit(RunEvent.of("system", "Teams notice could not be sent: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ shared

    private static String flowName(AgentRun run) {
        return run.flowName == null || run.flowName.isBlank() ? "flow" : run.flowName;
    }

    /**
     * The agent's last message, cut to what a chat message can carry. It is the plan for an
     * approval and the question for an answer — the same field either way, because in both cases
     * what a human needs to see is the last thing the agent said.
     */
    private static String finalOutputExcerpt(AgentRun run) {
        String text = run.finalOutput();
        if (text == null || text.isBlank()) {
            return "(The run produced no text — open the app to see its console.)";
        }
        return text.length() <= PLAN_EXCERPT_CHARS
                ? text
                : text.substring(0, PLAN_EXCERPT_CHARS) + "\n… (truncated — the rest is in the app)";
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

}
