package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicRunController}: what each status code gives away, the shapes of the
 * answers, and the wait. Hand-wired mocks, as for the webhook — only the controller's own logic
 * is under test — and a poll interval of a millisecond, so "waits for the run" takes no time.
 */
class PublicRunControllerTest {

    private static final String TOKEN = "3f1c2b6e-0d7a-4c11-9a58-3d4a1c9e7b20";

    private final FlowStore flows = mock(FlowStore.class);
    private final RunService runService = mock(RunService.class);
    /** The organization's rules: a fresh mock requires no approval, as before approvals existed. */
    private final com.concentus.policy.OrgPolicyService policies =
            mock(com.concentus.policy.OrgPolicyService.class);
    private final AgentRun run = new AgentRun("run_1", "f1", "Flow", "local");

    private PublicRunController controller() {
        return controller(new RateLimiter(1_000, 60_000));
    }

    private PublicRunController controller(RateLimiter limiter) {
        return new PublicRunController(flows, runService, policies, limiter, Duration.ofMillis(1));
    }

    private static FlowGraph flow(String id, Map<String, Object> inputData) {
        FlowNode input = new FlowNode("in1", "input", null, inputData);
        return new FlowGraph(id, "Flow", "local", List.of(input), List.of(), null, List.of(), null, null);
    }

    private static Map<String, Object> published(String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", "manual");
        data.put("prompt", "Answer as the support desk.");
        data.put("published", true);
        data.put("publishToken", token);
        return data;
    }

    private static MockHttpServletRequest bearing(String token) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (token != null) r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    private static PublicRunController.RunRequest input(String text) {
        return new PublicRunController.RunRequest(text);
    }

    @BeforeEach
    void aPublishedFlowAndARunThatStarts() {
        when(flows.get("f1")).thenReturn(Optional.of(flow("f1", published(TOKEN))));
        Map<String, Object> unpublished = new HashMap<>(published(TOKEN));
        unpublished.put("published", false);
        when(flows.get("f2")).thenReturn(Optional.of(flow("f2", unpublished)));
        when(flows.get("nope")).thenReturn(Optional.empty());
        when(runService.startTriggered(any(FlowGraph.class), anyString(), eq("api"))).thenReturn(
                new RunSummary("run_1", "f1", "Flow", "local", "STARTING", 0L, null, List.of(), null, "api",
                        0L, 0L, 0.0, false, 1));
        when(runService.get("run_1")).thenReturn(Optional.of(run));
    }

    // ---- what the status codes give away ----

    @Test
    void anUnknownFlowAndAnUnpublishedFlowAnswerTheSame404() {
        PublicRunController c = controller();

        ResponseStatusException unknown = (ResponseStatusException) org.assertj.core.api.Assertions.catchThrowable(
                () -> c.run("nope", input("hi"), true, null, bearing(TOKEN)));
        ResponseStatusException unpublished = (ResponseStatusException) org.assertj.core.api.Assertions.catchThrowable(
                () -> c.run("f2", input("hi"), true, null, bearing(TOKEN)));

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unpublished.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Byte for byte the same, so the answer cannot be used to tell the two apart.
        assertThat(unpublished.getReason()).isEqualTo(unknown.getReason());
        verify(runService, never()).startTriggered(any(), anyString(), anyString());
    }

    @Test
    void aWrongTokenIs401WithNoDetail() {
        assertThatThrownBy(() -> controller().run("f1", input("hi"), true, null, bearing("not-it")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getReason()).isNull();
                });
        verify(runService, never()).startTriggered(any(), anyString(), anyString());
    }

    @Test
    void aMissingTokenIs401Too() {
        assertThatThrownBy(() -> controller().run("f1", input("hi"), true, null, bearing(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aTokenThatMerelyStartsLikeTheRealOneIsStillWrong() {
        // The comparison is constant-time over both byte arrays, which also means a prefix, a
        // longer string and a different length all fail the same way.
        for (String almost : List.of(TOKEN.substring(0, TOKEN.length() - 1), TOKEN + "0", TOKEN.toUpperCase())) {
            assertThatThrownBy(() -> controller().run("f1", input("hi"), true, null, bearing(almost)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    void theBearerSchemeIsReadCaseInsensitivelyAndNothingElseCounts() {
        MockHttpServletRequest lower = new MockHttpServletRequest();
        lower.addHeader("Authorization", "bearer " + TOKEN);
        assertThat(PublicRunController.bearer(lower)).isEqualTo(TOKEN);

        MockHttpServletRequest basic = new MockHttpServletRequest();
        basic.addHeader("Authorization", "Basic " + TOKEN);
        assertThat(PublicRunController.bearer(basic)).isNull();
    }

    @Test
    void anEmptyInputIs400() {
        assertThatThrownBy(() -> controller().run("f1", input("   "), true, null, bearing(TOKEN)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller().run("f1", null, true, null, bearing(TOKEN)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void requestsAreRateLimitedPerToken() {
        PublicRunController c = controller(new RateLimiter(2, 60_000));
        c.run("f1", input("one"), false, null, bearing(TOKEN));
        c.run("f1", input("two"), false, null, bearing(TOKEN));

        assertThatThrownBy(() -> c.run("f1", input("three"), false, null, bearing(TOKEN)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // Wrong tokens are counted too — that is what throttles a guess.
        PublicRunController guessed = controller(new RateLimiter(1, 60_000));
        assertThatThrownBy(() -> guessed.run("f1", input("x"), false, null, bearing("guess")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> guessed.run("f1", input("x"), false, null, bearing("guess")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    // ---- the answers ----

    @Test
    void waitFalseAnswers202AtOnceWithTheInputFencedIntoThePrompt() {
        ResponseEntity<Map<String, Object>> response =
                controller().run("f1", input("Where is my order #42?"), false, null, bearing(TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("runId", "run_1").containsEntry("status", "STARTING");
        assertThat(response.getBody()).doesNotContainKey("output");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(runService).startTriggered(any(FlowGraph.class), prompt.capture(), eq("api"));
        assertThat(prompt.getValue())
                .startsWith("Answer as the support desk.")
                .contains("Verified request metadata (established by Concentus, not by the request)")
                .contains("- via: published endpoint")
                .contains("The untrusted request input follows")
                .contains("Where is my order #42?");
    }

    @Test
    void waitTrueAnswers200WithTheFinalOutputOnceTheRunIsDone() {
        run.status = "COMPLETED";
        run.emit(RunEvent.of("agent_message", "Order #42 ships tomorrow."));

        ResponseEntity<Map<String, Object>> response =
                controller().run("f1", input("Where is my order #42?"), true, 5, bearing(TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("runId", "run_1")
                .containsEntry("status", "COMPLETED")
                .containsEntry("output", "Order #42 ships tomorrow.");
    }

    @Test
    void aRunThatStopsToAskAQuestionAnswersWithTheQuestion() {
        run.status = "AWAITING_ANSWER";
        run.emit(RunEvent.of("agent_message", "Which order — 42 or 43?"));

        ResponseEntity<Map<String, Object>> response =
                controller().run("f1", input("Where is my order?"), true, 5, bearing(TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("output", "Which order — 42 or 43?");
    }

    @Test
    void waitTrueGivesUpWith202WhenTheRunOutlivesTheTimeout() {
        run.status = "RUNNING";

        ResponseEntity<Map<String, Object>> response =
                controller().run("f1", input("Think hard."), true, 1, bearing(TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("runId", "run_1").containsEntry("status", "RUNNING");
    }

    @Test
    void anIdleRunThatHasNotSpokenYetIsNotAnAnswer() {
        // Every local run is IDLE for its first moment, before the first turn is dispatched.
        run.status = "IDLE";
        assertThat(PublicRunController.settled(run)).isFalse();

        run.emit(RunEvent.of("agent_message", "Done."));
        assertThat(PublicRunController.settled(run)).isTrue();
    }

    @Test
    void theTimeoutIsClampedToTheAllowedRange() {
        assertThat(PublicRunController.clampTimeout(null)).isEqualTo(120);
        assertThat(PublicRunController.clampTimeout(0)).isEqualTo(1);
        assertThat(PublicRunController.clampTimeout(5_000)).isEqualTo(600);
        assertThat(PublicRunController.clampTimeout(30)).isEqualTo(30);
    }

    // ---- polling ----

    @Test
    void pollingReturnsTheRunWhileItRunsAndItsOutputWhenDone() {
        run.status = "RUNNING";
        ResponseEntity<Map<String, Object>> running = controller().poll("f1", "run_1", bearing(TOKEN));
        assertThat(running.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(running.getBody()).containsEntry("status", "RUNNING").doesNotContainKey("output");

        run.status = "COMPLETED";
        run.emit(RunEvent.of("agent_message", "42"));
        ResponseEntity<Map<String, Object>> done = controller().poll("f1", "run_1", bearing(TOKEN));
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("output", "42");
    }

    @Test
    void aRunOfAnotherFlowIsNoSuchRunHere() {
        // f3 is published with its own token; its holder must not be able to read f1's runs.
        when(flows.get("f3")).thenReturn(Optional.of(flow("f3", published("other-token"))));

        assertThatThrownBy(() -> controller().poll("f3", "run_1", bearing("other-token")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- the chat page ----

    @Test
    void theChatPageIsServedForTheRightTokenAndIsSelfContained() {
        ResponseEntity<String> page = controller().chat("f1", TOKEN);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(page.getBody())
                .contains("<!doctype html>")
                .contains("'Bearer ' + token")
                .contains("/run?wait=true")
                // The token is not baked into the markup; the page reads it from its own address.
                .doesNotContain(TOKEN)
                .doesNotContain("<script src=");
    }

    @Test
    void theChatPageIs404WithoutAPublishedFlowAnd401WithTheWrongToken() {
        assertThatThrownBy(() -> controller().chat("f2", TOKEN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller().chat("f1", "wrong"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ---- publish approval (organization policy) ----

    @Test
    void aPublishedFlowAwaitingApprovalAnswersTheSame404AsAnUnpublishedOne() {
        when(policies.publishBlocked("f1", TOKEN)).thenReturn(true);
        PublicRunController c = controller();

        ResponseStatusException waiting = (ResponseStatusException) org.assertj.core.api.Assertions.catchThrowable(
                () -> c.run("f1", input("hi"), true, null, bearing(TOKEN)));
        ResponseStatusException unpublished = (ResponseStatusException) org.assertj.core.api.Assertions.catchThrowable(
                () -> c.run("f2", input("hi"), true, null, bearing(TOKEN)));

        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Byte for byte the same: a door that is "merely waiting" must not read differently from
        // one that does not exist.
        assertThat(waiting.getReason()).isEqualTo(unpublished.getReason());
        // And the chat page and the poll are shut too, not only the run.
        assertThatThrownBy(() -> c.chat("f1", TOKEN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(runService, never()).startTriggered(any(), anyString(), anyString());
    }

    @Test
    void onceApprovedTheSameCallStartsTheRun() {
        when(policies.publishBlocked("f1", TOKEN)).thenReturn(false);
        run.status = "COMPLETED";

        ResponseEntity<Map<String, Object>> response = controller().run("f1", input("hi"), true, null, bearing(TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runService).startTriggered(any(FlowGraph.class), anyString(), eq("api"));
    }
}
