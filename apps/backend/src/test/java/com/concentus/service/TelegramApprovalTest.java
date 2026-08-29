package com.concentus.service;

import com.concentus.config.Settings;
import com.concentus.model.RunEvent;
import com.concentus.secrets.CredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The Telegram channel: the one remote channel that carries the answer back without a public
 * URL — the app polls the bot, the buttons come back as callback queries, a reply names the
 * question it answers. Driven through the fake transport, like the Slack tests.
 */
class TelegramApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Call(String url, JsonNode body) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    /** What getUpdates answers next; tests set it to steer the poll. */
    private volatile String updatesJson = "{\"ok\":true,\"result\":[]}";

    private final RemoteApprovalService.Transport transport = (url, bearer, body) -> {
        calls.add(new Call(url, body));
        if (url.endsWith("/sendMessage")) return MAPPER.readTree("{\"ok\":true,\"result\":{\"message_id\":41}}");
        if (url.endsWith("/getUpdates")) return MAPPER.readTree(updatesJson);
        return MAPPER.readTree("{\"ok\":true}");
    };

    private RemoteApprovalService service(boolean configured) {
        Settings settings = configured
                ? Settings.of(Map.of("approvals.telegram.bot-token", "123:abc", "approvals.telegram.chat-id", "555"))
                : Settings.none();
        return new RemoteApprovalService(mock(CredentialResolver.class), MAPPER, transport, settings);
    }

    private static AgentRun awaiting(String status) {
        return awaiting("run-1", status);
    }

    private static AgentRun awaiting(String id, String status) {
        AgentRun run = new AgentRun(id, "f1", "Presupuestos", "local");
        run.status = status;
        run.emit(RunEvent.of("agent_message", "PLAN: create three draft estimates in Holded."));
        return run;
    }

    private List<Call> callsTo(String method) {
        return calls.stream().filter(c -> c.url().endsWith("/" + method)).toList();
    }

    @Test
    void nothing_is_sent_when_telegram_is_not_configured() {
        RemoteApprovalService service = service(false);

        service.runAwaitingApproval(awaiting("AWAITING_APPROVAL"), () -> { }, () -> { });

        assertThat(calls).isEmpty();
    }

    @Test
    void an_approval_request_is_a_message_with_two_buttons_and_the_token_never_reaches_the_log() {
        RemoteApprovalService service = service(true);
        AgentRun run = awaiting("AWAITING_APPROVAL");

        service.runAwaitingApproval(run, () -> { }, () -> { });

        List<Call> sent = callsTo("sendMessage");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).url()).startsWith("https://api.telegram.org/bot123:abc/");
        JsonNode body = sent.get(0).body();
        assertThat(body.path("chat_id").asText()).isEqualTo("555");
        assertThat(body.path("text").asText()).contains("Presupuestos").contains("draft estimates");
        JsonNode buttons = body.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(buttons.get(0).path("callback_data").asText()).isEqualTo("approve:run-1");
        assertThat(buttons.get(1).path("callback_data").asText()).isEqualTo("reject:run-1");
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("posted to Telegram"));
        assertThat(run.bufferedEvents()).noneSatisfy(e -> assertThat(e.text()).contains("123:abc"));
    }

    @Test
    void the_approve_button_approves_acknowledges_and_rewrites_the_message() {
        RemoteApprovalService service = service(true);
        AgentRun run = awaiting("AWAITING_APPROVAL");
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        service.runAwaitingApproval(run, () -> {
            approved.incrementAndGet();
            run.status = "RUNNING";
            service.settled(run.id, "approved"); // what RunService does after the flip
        }, rejected::incrementAndGet);

        updatesJson = "{\"ok\":true,\"result\":[{\"update_id\":900,\"callback_query\":{\"id\":\"cq1\","
                + "\"data\":\"approve:run-1\"}}]}";
        service.pollTelegramOnce();

        assertThat(approved).hasValue(1);
        assertThat(rejected).hasValue(0);
        assertThat(callsTo("answerCallbackQuery")).hasSize(1);
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("Approved via Telegram"));
        // The next poll asks from after the update it consumed, so a button is never read twice.
        // A second request keeps the poller alive: with nothing to watch it stops asking at all.
        service.runAwaitingApproval(awaiting("run-2", "AWAITING_APPROVAL"), () -> { }, () -> { });
        updatesJson = "{\"ok\":true,\"result\":[]}";
        service.pollTelegramOnce();
        List<Call> polls = callsTo("getUpdates");
        assertThat(polls.get(polls.size() - 1).body().path("offset").asLong()).isEqualTo(901);
    }

    @Test
    void a_reply_to_the_question_answers_it_and_an_unrelated_message_does_not() {
        RemoteApprovalService service = service(true);
        AgentRun run = awaiting("AWAITING_ANSWER");
        AtomicReference<String> answer = new AtomicReference<>();
        service.runAwaitingAnswer(run, answer::set);
        JsonNode sent = callsTo("sendMessage").get(0).body();
        assertThat(sent.path("reply_markup").path("force_reply").asBoolean()).isTrue();

        updatesJson = "{\"ok\":true,\"result\":["
                + "{\"update_id\":1,\"message\":{\"text\":\"hello bot\"}},"
                + "{\"update_id\":2,\"message\":{\"text\":\"Go with the second option\",\"reply_to_message\":{\"message_id\":41}}}]}";
        service.pollTelegramOnce();

        assertThat(answer.get()).isEqualTo("Go with the second option");
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("Answered via Telegram"));
    }

    @Test
    void a_request_the_app_settled_first_is_closed_and_a_late_button_is_told_so() {
        RemoteApprovalService service = service(true);
        AgentRun run = awaiting("AWAITING_APPROVAL");
        AtomicInteger approved = new AtomicInteger();
        service.runAwaitingApproval(run, approved::incrementAndGet, () -> { });

        run.status = "RUNNING"; // approved from the app
        updatesJson = "{\"ok\":true,\"result\":[{\"update_id\":5,\"callback_query\":{\"id\":\"cq9\",\"data\":\"approve:run-1\"}}]}";
        service.pollTelegramOnce();

        assertThat(approved).hasValue(0);
        // The watch closed on the status check before any update was read.
        assertThat(callsTo("getUpdates")).isEmpty();
    }
}
