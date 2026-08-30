package com.concentus.service;

import com.concentus.model.RunEvent;
import com.concentus.secrets.CredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Slack/Teams approval protocol against a fake transport: what gets posted, which reactions
 * decide, who wins a disagreement, and that every failure path falls back to "approve from the
 * app" instead of losing the run. No network anywhere.
 */
class RemoteApprovalServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Call(String url, String bearer, JsonNode body) {
    }

    private final CredentialResolver credentials = mock(CredentialResolver.class);
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    /** What reactions.get answers next; tests set it to steer the poll. */
    private volatile String reactionsJson = "{\"ok\":true,\"message\":{\"reactions\":[]}}";
    /** When set, chat.postMessage answers this instead of ok:true. */
    private volatile String postMessageJson;
    /** What conversations.replies answers next; tests set it to steer the question watch. */
    private volatile String repliesJson =
            "{\"ok\":true,\"messages\":[{\"ts\":\"111.222\",\"text\":\"the question\"}]}";

    private final RemoteApprovalService.Transport transport = (url, bearer, body) -> {
        calls.add(new Call(url, bearer, body));
        if (url.contains("chat.postMessage")) {
            return MAPPER.readTree(postMessageJson != null ? postMessageJson
                    : "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"111.222\"}");
        }
        if (url.contains("reactions.get")) return MAPPER.readTree(reactionsJson);
        if (url.contains("conversations.replies")) return MAPPER.readTree(repliesJson);
        return MAPPER.readTree("{\"ok\":true}");
    };

    private final RemoteApprovalService service =
            new RemoteApprovalService(credentials, MAPPER, transport);

    private AgentRun awaitingRun() {
        AgentRun run = new AgentRun("run-1", "f1", "Presupuestos");
        run.status = "AWAITING_APPROVAL";
        run.emit(RunEvent.of("agent_message", "PLAN: create three draft estimates in Holded."));
        return run;
    }

    private AgentRun slackRun() {
        AgentRun run = awaitingRun();
        run.approvalSlackCredentialId = "cred_slack";
        run.approvalSlackChannel = "C0123456789";
        when(credentials.resolve("cred_slack")).thenReturn("xoxb-token");
        return run;
    }

    private List<Call> callsTo(String urlPart) {
        return calls.stream().filter(c -> c.url().contains(urlPart)).toList();
    }

    /** Waits briefly for work the service hands to its own scheduler thread. */
    private void awaitCallsTo(String urlPart) throws InterruptedException {
        awaitCallsTo(urlPart, 1);
    }

    /**
     * The same, for a call that is not the first of its kind — a thread confirmation follows the
     * question's own chat.postMessage, so waiting for "at least one" would not wait at all.
     */
    private void awaitCallsTo(String urlPart, int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (callsTo(urlPart).size() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    // ---------------------------------------------------------------- posting

    @Test
    void postsThePlanToSlackAndStartsWatching() {
        AgentRun run = slackRun();

        service.runAwaitingApproval(run, () -> {}, () -> {});

        Call post = callsTo("chat.postMessage").get(0);
        assertThat(post.bearer()).isEqualTo("xoxb-token");
        assertThat(post.body().path("channel").asText()).isEqualTo("C0123456789");
        String text = post.body().path("text").asText();
        assertThat(text).contains("Presupuestos").contains("PLAN: create three draft estimates")
                .contains("run-1").contains("✅").contains("❌");
        assertThat(service.activeWatches()).isEqualTo(1);
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("posted to Slack"));
    }

    @Test
    void aSlackApiErrorNamesItselfAndFallsBackToTheApp() {
        AgentRun run = slackRun();
        // not_in_channel is the classic: the app was installed but nobody invited the bot.
        postMessageJson = "{\"ok\":false,\"error\":\"not_in_channel\"}";

        service.runAwaitingApproval(run, () -> {}, () -> {});

        assertThat(service.activeWatches()).isZero();
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("not_in_channel").contains("approve from the app"));
    }

    @Test
    void aMissingCredentialSaysSoInsteadOfPostingNothingSilently() {
        AgentRun run = awaitingRun();
        run.approvalSlackChannel = "C1";
        run.approvalSlackCredentialId = "cred_gone";
        when(credentials.resolve("cred_gone")).thenReturn(null);

        service.runAwaitingApproval(run, () -> {}, () -> {});

        assertThat(calls).isEmpty();
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("resolves to nothing"));
    }

    @Test
    void withNoChannelsConfiguredNothingLeavesTheMachine() {
        service.runAwaitingApproval(awaitingRun(), () -> {}, () -> {});

        assertThat(calls).isEmpty();
        assertThat(service.activeWatches()).isZero();
    }

    // ---------------------------------------------------------------- reactions

    @Test
    void aCheckReactionApprovesTheRun() {
        AgentRun run = slackRun();
        AtomicBoolean approved = new AtomicBoolean();
        // The callback stands in for RunService.approve, which flips the run and calls settled().
        service.runAwaitingApproval(run, () -> {
            approved.set(true);
            service.settled(run.id, "approved");
        }, () -> {});

        reactionsJson = "{\"ok\":true,\"message\":{\"reactions\":[{\"name\":\"white_check_mark\",\"count\":1}]}}";
        service.pollOnce(run.id);

        assertThat(approved).isTrue();
        assertThat(service.activeWatches()).isZero();
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("Approved via Slack reaction"));
    }

    @Test
    void rejectWinsWhenBothReactionsArePresent() {
        AgentRun run = slackRun();
        AtomicBoolean approved = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        service.runAwaitingApproval(run,
                () -> approved.set(true),
                () -> {
                    rejected.set(true);
                    service.settled(run.id, "rejected");
                });

        // Two people disagreed. Acting anyway would make the ✅ override the ❌ — the wrong
        // default for a feature whose whole point is asking first.
        reactionsJson = "{\"ok\":true,\"message\":{\"reactions\":["
                + "{\"name\":\"white_check_mark\",\"count\":1},{\"name\":\"x\",\"count\":1}]}}";
        service.pollOnce(run.id);

        assertThat(rejected).isTrue();
        assertThat(approved).isFalse();
    }

    @Test
    void anUnrelatedReactionDecidesNothing() {
        AgentRun run = slackRun();
        AtomicBoolean decided = new AtomicBoolean();
        service.runAwaitingApproval(run, () -> decided.set(true), () -> decided.set(true));

        reactionsJson = "{\"ok\":true,\"message\":{\"reactions\":[{\"name\":\"eyes\",\"count\":3}]}}";
        service.pollOnce(run.id);

        assertThat(decided).isFalse();
        assertThat(service.activeWatches()).isEqualTo(1);
    }

    @Test
    void aRunThatStoppedWaitingClosesItsWatchQuietly() throws Exception {
        AgentRun run = slackRun();
        service.runAwaitingApproval(run, () -> {}, () -> {});

        run.status = "TERMINATED"; // stopped from the app while the question was out
        service.pollOnce(run.id);

        assertThat(service.activeWatches()).isZero();
        awaitCallsTo("chat.update");
        assertThat(callsTo("chat.update").get(0).body().path("text").asText())
                .contains("no longer waiting");
    }

    // ---------------------------------------------------------------- settling

    @Test
    void settlingRewritesTheSlackMessageWithTheOutcomeOnce() throws Exception {
        AgentRun run = slackRun();
        service.runAwaitingApproval(run, () -> {}, () -> {});

        service.settled(run.id, "approved");
        service.settled(run.id, "approved"); // second decision arrives late — must be a no-op

        awaitCallsTo("chat.update");
        List<Call> updates = callsTo("chat.update");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).body().path("text").asText())
                .contains("✅ Approved").contains("run-1");
        assertThat(updates.get(0).body().path("ts").asText()).isEqualTo("111.222");
    }

    // ---------------------------------------------------------------- teams

    @Test
    void teamsGetsAnHonestNotificationCardNotButtons() {
        AgentRun run = awaitingRun();
        run.approvalTeamsWebhook = "https://example.webhook.office.com/x";

        service.runAwaitingApproval(run, () -> {}, () -> {});

        Call post = calls.get(0);
        assertThat(post.url()).isEqualTo("https://example.webhook.office.com/x");
        assertThat(post.bearer()).isNull();
        JsonNode card = post.body().path("attachments").get(0).path("content");
        assertThat(card.path("type").asText()).isEqualTo("AdaptiveCard");
        String cardText = card.path("body").toString();
        assertThat(cardText).contains("waiting for approval").contains("PLAN: create three");
        // The card says where to answer instead of pretending this channel can.
        assertThat(cardText).contains("cannot carry your reply");
        assertThat(service.activeWatches()).isZero(); // notification only — nothing to watch
    }

    @Test
    void aNonHttpsTeamsWebhookIsRefused() {
        AgentRun run = awaitingRun();
        run.approvalTeamsWebhook = "http://internal/hook";

        service.runAwaitingApproval(run, () -> {}, () -> {});

        assertThat(calls).isEmpty();
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("not an https URL"));
    }

    // ---------------------------------------------------------------- questions (AWAITING_ANSWER)

    private AgentRun askingRun() {
        AgentRun run = new AgentRun("run-2", "f1", "Presupuestos");
        run.status = "AWAITING_ANSWER";
        run.approvalSlackCredentialId = "cred_slack";
        run.approvalSlackChannel = "C0123456789";
        when(credentials.resolve("cred_slack")).thenReturn("xoxb-token");
        run.emit(RunEvent.of("agent_message", "Which client should I invoice first?"));
        return run;
    }

    @Test
    void postsTheQuestionToSlackAndWatchesItsThread() {
        AgentRun run = askingRun();

        service.runAwaitingAnswer(run, reply -> {});

        Call post = callsTo("chat.postMessage").get(0);
        assertThat(post.body().path("text").asText())
                .contains("Which client should I invoice first?")
                .contains("Reply in this thread")
                .contains("run-2");
        assertThat(service.activeWatches()).isEqualTo(1);
    }

    @Test
    void theFirstThreadedReplyBecomesTheRunsNextCommand() throws Exception {
        AgentRun run = askingRun();
        List<String> answers = new CopyOnWriteArrayList<>();
        service.runAwaitingAnswer(run, answers::add);

        repliesJson = "{\"ok\":true,\"messages\":["
                + "{\"ts\":\"111.222\",\"text\":\"Which client should I invoice first?\",\"bot_id\":\"B1\"},"
                + "{\"ts\":\"111.999\",\"text\":\"Start with ACME\",\"user\":\"U1\"}]}";
        service.pollOnce(run.id);

        assertThat(answers).containsExactly("Start with ACME");
        // Answered means done: a second reply to a question already taken would be a command
        // nobody asked for.
        assertThat(service.activeWatches()).isZero();
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("Answered via Slack: Start with ACME"));

        // The confirmation goes into the thread, under the question it answers.
        awaitCallsTo("chat.postMessage", 2);
        Call confirmation = callsTo("chat.postMessage").get(1);
        assertThat(confirmation.body().path("thread_ts").asText()).isEqualTo("111.222");
        assertThat(confirmation.body().path("text").asText()).contains("✔").contains("Presupuestos");
    }

    @Test
    void theServicesOwnMessagesInTheThreadAreNotMistakenForAnAnswer() {
        // The parent is the question and every bot message is this service — including the ✔ it
        // posts after answering. Taking either as a reply would loop the run against itself.
        AgentRun run = askingRun();
        List<String> answers = new CopyOnWriteArrayList<>();
        service.runAwaitingAnswer(run, answers::add);

        repliesJson = "{\"ok\":true,\"messages\":["
                + "{\"ts\":\"111.222\",\"text\":\"the question\"},"
                + "{\"ts\":\"111.333\",\"text\":\"✔ Sent to the flow\",\"bot_id\":\"B1\"}]}";
        service.pollOnce(run.id);

        assertThat(answers).isEmpty();
        assertThat(service.activeWatches()).isEqualTo(1);
    }

    @Test
    void aReplyThatArrivesAfterTheRunMovedOnIsToldSoInTheThread() throws Exception {
        AgentRun run = askingRun();
        service.runAwaitingAnswer(run, reply -> {
            throw new IllegalStateException("This execution is not waiting for an answer.");
        });

        repliesJson = "{\"ok\":true,\"messages\":[{\"ts\":\"111.222\",\"text\":\"q\"},"
                + "{\"ts\":\"111.999\",\"text\":\"too late\",\"user\":\"U1\"}]}";
        service.pollOnce(run.id);

        awaitCallsTo("chat.postMessage", 2);
        Call last = callsTo("chat.postMessage").get(1);
        assertThat(last.body().path("text").asText()).contains("too late");
        assertThat(last.body().path("thread_ts").asText()).isEqualTo("111.222");
    }

    @Test
    void answeringFromTheAppClosesTheThreadWatchOnTheNextPoll() {
        AgentRun run = askingRun();
        service.runAwaitingAnswer(run, reply -> {});

        run.status = "RUNNING"; // the answer was typed in the app; the run is working again
        service.pollOnce(run.id);

        assertThat(service.activeWatches()).isZero();
        // And the question message is left alone — rewriting it would erase the question itself.
        assertThat(callsTo("chat.update")).isEmpty();
    }

    @Test
    void aQuestionOnAFlowWithNoSlackChannelStaysInTheApp() {
        AgentRun run = new AgentRun("run-3", "f1", "Flow");
        run.status = "AWAITING_ANSWER";

        service.runAwaitingAnswer(run, reply -> {});

        assertThat(calls).isEmpty();
        assertThat(service.activeWatches()).isZero();
    }

    @Test
    void teamsIsToldAboutAQuestionButCannotCarryTheAnswer() {
        AgentRun run = new AgentRun("run-4", "f1", "Presupuestos");
        run.status = "AWAITING_ANSWER";
        run.approvalTeamsWebhook = "https://example.webhook.office.com/x";
        run.emit(RunEvent.of("agent_message", "Which client should I invoice first?"));

        service.runAwaitingAnswer(run, reply -> {});

        String card = calls.get(0).body().path("attachments").get(0).path("content")
                .path("body").toString();
        assertThat(card).contains("asked you something")
                .contains("Which client should I invoice first?")
                .contains("cannot carry your reply");
    }
}
