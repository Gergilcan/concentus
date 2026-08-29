package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowEvalCase;
import com.concentus.model.FlowEvalCaseResult;
import com.concentus.model.FlowEvalResult;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.store.EvalDatasetStore;
import com.concentus.store.EvalResultStore;
import com.concentus.store.FlowVersionStore;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvalRunService}: cases become runs, runs are waited for and judged, the
 * result is stored as it grows, and a run that cannot start fails its case rather than the
 * evaluation. The run service is a mock — nothing here executes an agent.
 */
class EvalRunServiceTest {

    private final EvalDatasetStore dataset = mock(EvalDatasetStore.class);
    private final EvalResultStore results = mock(EvalResultStore.class);
    private final RunService runs = mock(RunService.class);
    private final FlowVersionStore versions = mock(FlowVersionStore.class);
    private final AtomicInteger ids = new AtomicInteger();

    private static final FlowGraph FLOW = new FlowGraph("f1", "Flow", "local", List.of(), List.of(),
            null, List.of(), null, null);

    private EvalRunService service;

    @BeforeEach
    void setUp() {
        // The result store hands back what it was given, with an id when it had none — the one
        // behaviour of the real store the runner depends on.
        when(results.saveIn(anyString(), any())).thenAnswer(inv -> {
            FlowEvalResult r = inv.getArgument(1);
            return r.id() != null ? r : new FlowEvalResult("evr_1", r.flowId(), r.flowVersion(),
                    r.startedAt(), r.finishedAt(), r.status(), r.cases(), r.passed(), r.total());
        });
        when(versions.currentVersion("f1")).thenReturn(7);
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.of("claude"));
        EvalJudge judge = new EvalJudge(support, (args, t) -> new CliProcess.Result(1, "unused"));
        // Polls fast and gives up fast: the tests below never want to wait for a real run.
        service = new EvalRunService(dataset, results, runs, judge, versions, new OrgContext("default"), 5, 200);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static FlowEvalCase aCase(String id, String input, String expected) {
        return new FlowEvalCase(id, "f1", "Case " + id, input, expected, "contains", 0);
    }

    /** Registers a run the service will find settled with the given status and final answer. */
    private AgentRun settledRun(String id, String status, String answer) {
        AgentRun run = new AgentRun(id, "f1", "Flow", "local");
        run.status = status;
        if (answer != null) run.emit(RunEvent.of("agent_message", answer));
        when(runs.get(id)).thenReturn(Optional.of(run));
        return run;
    }

    /** Each start hands out the next of the given runs, in order. */
    private void startsGive(AgentRun... prepared) {
        when(runs.start(eq(FLOW), anyString())).thenAnswer(inv -> prepared[ids.getAndIncrement()].toSummary());
    }

    private FlowEvalResult lastSaved() {
        ArgumentCaptor<FlowEvalResult> saved = ArgumentCaptor.forClass(FlowEvalResult.class);
        verify(results, atLeastOnce()).saveIn(anyString(), saved.capture());
        return saved.getValue();
    }

    private static FlowEvalResult pending(int total) {
        return new FlowEvalResult("evr_1", "f1", 7, 1L, null, FlowEvalResult.RUNNING, List.of(), 0, total);
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void casesBecomeRunsWhoseAnswersAreJudgedAndStoredAgainstTheVersion() {
        startsGive(settledRun("run_a", "COMPLETED", "The invoice total is 120 EUR."),
                settledRun("run_b", "COMPLETED", "No invoices today."));

        service.evaluate(pending(2), FLOW,
                List.of(aCase("c1", "sum the invoices", "120 EUR"), aCase("c2", "list them", "INV-7")));

        verify(runs).start(FLOW, "sum the invoices");
        verify(runs).start(FLOW, "list them");
        FlowEvalResult done = lastSaved();
        assertThat(done.status()).isEqualTo(FlowEvalResult.DONE);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.flowVersion()).isEqualTo(7);
        assertThat(done.passed()).isEqualTo(1);
        assertThat(done.total()).isEqualTo(2);
        assertThat(done.cases()).extracting(FlowEvalCaseResult::runId).containsExactly("run_a", "run_b");
        assertThat(done.cases().get(0).passed()).isTrue();
        assertThat(done.cases().get(0).output()).contains("120 EUR");
        assertThat(done.cases().get(1).passed()).isFalse();
        assertThat(done.cases().get(1).why()).contains("INV-7");
    }

    @Test
    void theResultIsSavedAfterEveryCaseSoALongEvaluationCanBeWatched() {
        startsGive(settledRun("run_a", "COMPLETED", "yes"), settledRun("run_b", "COMPLETED", "yes"));

        service.evaluate(pending(2), FLOW, List.of(aCase("c1", "one", "yes"), aCase("c2", "two", "yes")));

        ArgumentCaptor<FlowEvalResult> saved = ArgumentCaptor.forClass(FlowEvalResult.class);
        verify(results, atLeastOnce()).saveIn(anyString(), saved.capture());
        // After the first case: still running, one judged. Then two. Then done.
        assertThat(saved.getAllValues()).extracting(r -> r.cases().size()).containsExactly(1, 2, 2);
        assertThat(saved.getAllValues()).extracting(FlowEvalResult::status)
                .containsExactly(FlowEvalResult.RUNNING, FlowEvalResult.RUNNING, FlowEvalResult.DONE);
    }

    @Test
    void anEvaluationRunIsLabelledAsOneInTheExecutionsList() {
        AgentRun run = settledRun("run_a", "COMPLETED", "yes");
        startsGive(run);

        service.evaluate(pending(1), FLOW, List.of(aCase("c1", "one", "yes")));

        assertThat(run.trigger).isEqualTo("eval");
    }

    // ---------------------------------------------------------------- when a run cannot start

    @Test
    void aRejectedSubmissionFailsItsCaseWithTheReasonAndTheRestStillRuns() {
        AgentRun second = settledRun("run_b", "COMPLETED", "fine");
        when(runs.start(eq(FLOW), anyString()))
                .thenThrow(new RejectedExecutionException())
                .thenReturn(second.toSummary());

        service.evaluate(pending(2), FLOW, List.of(aCase("c1", "one", "fine"), aCase("c2", "two", "fine")));

        FlowEvalResult done = lastSaved();
        assertThat(done.status()).isEqualTo(FlowEvalResult.DONE);
        assertThat(done.cases()).hasSize(2);
        assertThat(done.cases().get(0).passed()).isFalse();
        assertThat(done.cases().get(0).runId()).isNull();
        assertThat(done.cases().get(0).why()).contains("could not start").contains("queue is full");
        // "1/2, one could not start" is a truer score than an evaluation that crashed on case one.
        assertThat(done.cases().get(1).passed()).isTrue();
        assertThat(done.passed()).isEqualTo(1);
    }

    @Test
    void aFlowThatRefusesToStartFailsItsCaseWithTheRefusal() {
        when(runs.start(eq(FLOW), anyString()))
                .thenThrow(new IllegalStateException("This flow has spent its monthly budget."));

        service.evaluate(pending(1), FLOW, List.of(aCase("c1", "one", "fine")));

        assertThat(lastSaved().cases().get(0).why()).contains("monthly budget");
    }

    // ---------------------------------------------------------------- when a run ends badly

    @Test
    void aRunThatFailsFailsItsCaseWithTheRunsOwnError() {
        AgentRun failed = settledRun("run_a", "ERROR", null);
        failed.error = "Too many runs in progress right now.";
        startsGive(failed);

        service.evaluate(pending(1), FLOW, List.of(aCase("c1", "one", "fine")));

        FlowEvalCaseResult c = lastSaved().cases().get(0);
        assertThat(c.passed()).isFalse();
        assertThat(c.runId()).isEqualTo("run_a");
        assertThat(c.why()).contains("Too many runs in progress");
    }

    @Test
    void aRunWaitingForApprovalCannotPassBecauseNobodyIsThereToApprove() {
        startsGive(settledRun("run_a", "AWAITING_APPROVAL", "Plan: delete everything. Approve?"));

        service.evaluate(pending(1), FLOW, List.of(aCase("c1", "one", "Plan")));

        FlowEvalCaseResult c = lastSaved().cases().get(0);
        assertThat(c.passed()).isFalse();
        assertThat(c.why()).contains("approval");
    }

    @Test
    void aRunThatNeverSettlesIsStoppedAndItsCaseFailed() {
        startsGive(settledRun("run_a", "RUNNING", null));

        service.evaluate(pending(1), FLOW, List.of(aCase("c1", "one", "fine")));

        verify(runs).stop("run_a");
        FlowEvalCaseResult c = lastSaved().cases().get(0);
        assertThat(c.passed()).isFalse();
        assertThat(c.why()).contains("did not finish");
    }

    // ---------------------------------------------------------------- start()

    @Test
    void startReturnsAtOnceRunningAndFinishesOnItsOwnThread() {
        when(dataset.listForFlow("f1")).thenReturn(List.of(aCase("c1", "one", "fine")));
        startsGive(settledRun("run_a", "COMPLETED", "fine"));

        FlowEvalResult started = service.start(FLOW);

        assertThat(started.id()).isEqualTo("evr_1");
        assertThat(started.status()).isEqualTo(FlowEvalResult.RUNNING);
        assertThat(started.flowVersion()).isEqualTo(7);
        assertThat(started.total()).isEqualTo(1);
        assertThat(started.cases()).isEmpty();
        // The worker finishes the job behind the returned handle.
        verify(results, timeout(2_000)).saveIn(anyString(),
                org.mockito.ArgumentMatchers.argThat(r -> FlowEvalResult.DONE.equals(r.status())));
    }

    @Test
    void aFlowWithNoCasesIsRefusedRatherThanScoredZeroOverZero() {
        when(dataset.listForFlow("f1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(FLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no evaluation cases");
        verify(runs, never()).start(any(), anyString());
    }
}
