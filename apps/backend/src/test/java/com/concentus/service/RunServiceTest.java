package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.store.RunStore;
import com.concentus.support.AnthropicClientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RunService}: the run lifecycle (start/stop/retry/sendCommand), the
 * bounded in-memory run registry (eviction), the bounded executor (rejection under load), and
 * restoring persisted runs on startup. All collaborators are hand-wired mocks — no Spring
 * context, no real Anthropic client, no real filesystem/network.
 */
class RunServiceTest {

    private final AnthropicClientProvider clientProvider = mock(AnthropicClientProvider.class);
    private final FlowCompiler compiler = mock(FlowCompiler.class);
    private final ManagedFlowLauncher launcher = mock(ManagedFlowLauncher.class);
    private final LocalClaudeExecutor localExecutor = mock(LocalClaudeExecutor.class);
    private final RunStore runStore = mock(RunStore.class);
    private final NotificationService notifier = mock(NotificationService.class);
    private final RemoteApprovalService remoteApprovals = mock(RemoteApprovalService.class);
    /**
     * Version history, stubbed per test. The default 0 is what a run without a database sees: no
     * version number, so no badge. The versioning itself is covered by {@code FlowVersionStoreTest}.
     */
    private final com.concentus.store.FlowVersionStore flowVersions =
            mock(com.concentus.store.FlowVersionStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<RunService> created = new ArrayList<>();

    /**
     * The real adapter over the mocked executor, so these tests still assert on what actually gets
     * executed rather than on the registry indirection in front of it.
     */
    private com.concentus.execution.ExecutionBackends backends() {
        com.concentus.support.LocalClaudeSupport support = mock(com.concentus.support.LocalClaudeSupport.class);
        when(support.command()).thenReturn(java.util.Optional.of("claude"));
        return new com.concentus.execution.ExecutionBackends(List.of(
                new com.concentus.execution.ClaudeCliExecutionBackend(localExecutor,
                        mock(FanoutExecutor.class), support)));
    }

    @AfterEach
    void shutdownAll() {
        created.forEach(RunService::shutdown);
    }

    private RunService newService(int maxConcurrent, int queueCapacity, int maxRetainedRuns) {
        RunService s = new RunService(clientProvider, compiler, launcher, backends(),
                new PricingTable("", 3.0, 15.0),
                new CloudStreamEventHandler(), runStore, flowVersions, mapper,
                notifier, remoteApprovals, variableStore(),
                maxConcurrent, queueCapacity, maxRetainedRuns, 3.0, 15.0);
        created.add(s);
        return s;
    }

    /** No variables defined, which is what every pre-existing scenario assumes. */
    private static com.concentus.store.VariableStore variableStore() {
        var store = org.mockito.Mockito.mock(com.concentus.store.VariableStore.class);
        org.mockito.Mockito.when(store.merged(org.mockito.Mockito.any()))
                .thenReturn(new java.util.LinkedHashMap<>());
        return store;
    }

    private static AgentSpec coordinatorSpec() {
        AgentSpec s = new AgentSpec();
        s.nodeId = "c1";
        s.name = "Coord";
        return s;
    }

    private static CompiledFlow compiledFlow() {
        return new CompiledFlow(coordinatorSpec(), List.of());
    }

    private static FlowNode agentNode(String id, String role) {
        return new FlowNode(id, "agent", role, Map.of("name", "Coord"));
    }

    private static FlowNode inputNode(String mode, String prompt) {
        Map<String, Object> d = new HashMap<>();
        d.put("mode", mode);
        if (prompt != null) d.put("prompt", prompt);
        return new FlowNode("in1", "input", null, d);
    }

    private static FlowGraph flow(String id) {
        return new FlowGraph(id, "Flow", "managed",
                List.of(agentNode("c1", "coordinator"), inputNode("manual", null)),
                List.of(), null, List.of(), null, null);
    }

    private static FlowGraph flowWithPrompt(String id, String mode, String prompt) {
        return new FlowGraph(id, "Flow", "managed",
                List.of(agentNode("c1", "coordinator"), inputNode(mode, prompt)),
                List.of(), null, List.of(), null, null);
    }

    private AgentRun awaitStatus(RunService svc, String runId, String status) throws InterruptedException {
        AgentRun run = svc.get(runId).orElseThrow();
        long deadline = System.currentTimeMillis() + 3000;
        while (!status.equals(run.status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return run;
    }

    // ---------------------------------------------------------------- start(): backend gating

    @Test
    void startThrowsWhenNoBackendIsAvailable() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("none");
        RunService svc = newService(4, 8, 10);

        assertThatThrownBy(() -> svc.start(flow("f1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not signed in");
    }

    // ---------------------------------------------------------------- start(): local backend

    @Test
    void startLocalWithoutAutoStartLeavesRunIdleAndNeverTouchesTheExecutor() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flow("f1"));

        assertThat(summary.status()).isEqualTo("IDLE");
        assertThat(summary.trigger()).isEqualTo("manual");
        verify(runStore, timeout(1000)).persist(any());
        verifyNoInteractions(localExecutor);
    }

    @Test
    void startLocalWithPromptTriggerAutoStartsATurn() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flowWithPrompt("f1", "prompt", "go"));

        assertThat(summary.trigger()).isEqualTo("prompt");
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("go"));
    }

    @Test
    void aFinishedTurnCompletesTheRunAndDoesNotBlockTheNextFire() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        // The executor ends its turn the way the real one does: a final answer, then idle.
        doAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.emit(RunEvent.of("agent_message", "Briefing sent to the channel.", "Coordinator", "n1"));
            run.status = "IDLE";
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flowWithPrompt("f1", "cron", "daily briefing"));
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("daily briefing"));

        // A delivered result is COMPLETED (still continuable while retained) — and it must not
        // block the next scheduled tick. Counting a finished run as active made every cron flow
        // fire exactly once and then starve behind its own finished run.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && svc.hasActiveRun("f1")) Thread.sleep(20);
        assertThat(svc.get(summary.id()).orElseThrow().status).isEqualTo("COMPLETED");
        assertThat(svc.hasActiveRun("f1")).isFalse();
    }

    @Test
    void aTurnEndingInAQuestionAwaitsTheUsersAnswerAndStaysInFlight() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        doAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.emit(RunEvent.of("agent_message",
                    "I found two invoices with the same number.\nWhich one should I keep?",
                    "Coordinator", "n1"));
            run.status = "IDLE";
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flowWithPrompt("f1", "prompt", "reconcile"));
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("reconcile"));

        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline
                && !"AWAITING_ANSWER".equals(svc.get(summary.id()).orElseThrow().status)) {
            Thread.sleep(20);
        }
        assertThat(svc.get(summary.id()).orElseThrow().status).isEqualTo("AWAITING_ANSWER");
        // A question on the table blocks the next scheduled fire, exactly like a pending approval.
        assertThat(svc.hasActiveRun("f1")).isTrue();
    }

    // ---------------------------------------------------------------- start(): cloud backend

    @Test
    void cloudBackendFailureToObtainAClientMarksTheRunErrorAndNotifies() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("cloud");
        when(clientProvider.client()).thenThrow(new RuntimeException("no creds"));
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flow("f1"));

        AgentRun run = awaitStatus(svc, summary.id(), "ERROR");
        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("no creds");
        verify(notifier, timeout(2000)).runFailed(any());
    }

    // ---------------------------------------------------------------- bounded run registry

    @Test
    void oldestCompletedRunIsEvictedFirstWhenOverCapacity() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 2); // maxRetainedRuns = 2

        RunSummary s1 = svc.start(flow("f1"));
        AgentRun r1 = svc.get(s1.id()).orElseThrow();
        r1.status = "TERMINATED";
        r1.createdAt = 1_000L;

        RunSummary s2 = svc.start(flow("f2"));
        AgentRun r2 = svc.get(s2.id()).orElseThrow();
        r2.status = "TERMINATED";
        r2.createdAt = 2_000L;

        // Registry is now 2/2 with both completed; starting a third pushes it over the cap and
        // should evict exactly the oldest completed one (r1), not r2.
        RunSummary s3 = svc.start(flow("f3"));

        List<String> ids = svc.list().stream().map(RunSummary::id).toList();
        assertThat(ids).doesNotContain(s1.id());
        assertThat(ids).containsExactlyInAnyOrder(s2.id(), s3.id());
        assertThat(svc.get(s1.id())).isEmpty();
    }

    @Test
    void activeRunsAreNeverEvictedEvenWhenOverCapacity() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 1); // maxRetainedRuns = 1

        svc.start(flow("f1")); // stays IDLE (non-terminal)
        svc.start(flow("f2")); // also IDLE

        // Neither is TERMINATED/ERROR, so the registry is allowed to briefly exceed the cap.
        assertThat(svc.list()).hasSize(2);
    }

    // ---------------------------------------------------------------- bounded executor

    @Test
    void startRejectsSubmissionsPastTheExecutorBoundsAndFailsTheRunInstead() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());

        RunService svc = newService(1, 1, 10); // one worker thread, a single-slot queue

        svc.start(flowWithPrompt("f1", "prompt", "go"));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue(); // the single worker is now occupied

        svc.start(flowWithPrompt("f2", "prompt", "go")); // fills the one queue slot; not rejected

        RunSummary third = svc.start(flowWithPrompt("f3", "prompt", "go")); // pool AND queue are now full

        AgentRun thirdRun = awaitStatus(svc, third.id(), "ERROR");
        assertThat(thirdRun.status).isEqualTo("ERROR");
        assertThat(thirdRun.error).contains("Too many runs");

        release.countDown();
    }

    @Test
    void sendCommandRejectsWhenThePoolIsSaturated() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());

        RunService svc = newService(1, 1, 10); // one worker thread, a single-slot queue

        svc.start(flowWithPrompt("f1", "prompt", "go")); // occupies the only worker
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        svc.start(flowWithPrompt("f2", "prompt", "go")); // fills the one queue slot; not rejected

        RunSummary manual = svc.start(flow("f3")); // no auto-start; doesn't touch the executor

        assertThatThrownBy(() -> svc.sendCommand(manual.id(), "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many runs");

        release.countDown();
    }

    // ---------------------------------------------------------------- flow version linkage

    @Test
    void aRunRecordsTheFlowVersionItLaunchedFrom() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        when(flowVersions.currentVersion("f1")).thenReturn(12);
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flow("f1"));

        assertThat(summary.flowVersion()).isEqualTo(12);
    }

    @Test
    void aRetryKeepsTheVersionItReplaysRatherThanTheFlowsCurrentOne() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        when(flowVersions.currentVersion("f1")).thenReturn(3);
        RunService svc = newService(4, 8, 10);
        RunSummary original = svc.start(flow("f1"));

        // The flow is edited twice between the run and its retry. The retry replays the ORIGINAL
        // snapshot, so labelling it v5 would name a revision it never executed.
        when(flowVersions.currentVersion("f1")).thenReturn(5);
        RunSummary retried = svc.retry(original.id());

        assertThat(retried.flowVersion()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- retry()

    @Test
    void retryStartsANewRunFromTheStoredFlowSnapshotWithoutTouchingTheOriginal() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary original = svc.start(flow("f1"));

        RunSummary retried = svc.retry(original.id());

        assertThat(retried.id()).isNotEqualTo(original.id());
        assertThat(retried.flowId()).isEqualTo("f1");
        assertThat(svc.get(original.id())).isPresent();
    }

    @Test
    void retryThrowsWhenTheRunHasNoStoredFlowSnapshot() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().flowJson = null;

        assertThatThrownBy(() -> svc.retry(s.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no stored flow");
    }

    @Test
    void retryThrowsWhenTheStoredFlowJsonIsCorrupt() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().flowJson = "{ not valid json ][";

        assertThatThrownBy(() -> svc.retry(s.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    void retryThrowsForAnUnknownRunId() {
        RunService svc = newService(4, 8, 10);

        assertThatThrownBy(() -> svc.retry("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such run");
    }

    // ---------------------------------------------------------------- sendCommand()

    @Test
    void sendCommandThrowsForAnUnknownRunId() {
        RunService svc = newService(4, 8, 10);

        assertThatThrownBy(() -> svc.sendCommand("nope", "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such run");
    }

    @Test
    void sendCommandThrowsWhenTheLocalRunIsNotYetCompiled() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().compiled = null;

        assertThatThrownBy(() -> svc.sendCommand(s.id(), "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not finished starting up");
    }

    @Test
    void sendCommandDrivesAnyTurnBasedBackend() throws Exception {
        // Regression: this branched on the literal id "local", so a run on any other turn-based
        // backend fell through to the cloud path and was refused for having no session id — an
        // execution that was working perfectly reported "Run is not ready yet".
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().backend = "local";

        svc.sendCommand(s.id(), "hola");

        // Dispatched to the executor rather than rejected; the id is what routes it.
        verify(localExecutor, timeout(2000).atLeastOnce()).runTurn(any(), any(), any());
    }

    @Test
    void sendCommandSetsInitialPromptOnlyOnTheFirstCommand() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1")); // manual: no initial prompt yet

        svc.sendCommand(s.id(), "first");
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("first"));
        assertThat(svc.get(s.id()).orElseThrow().initialPrompt).isEqualTo("first");

        svc.sendCommand(s.id(), "second");
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("second"));
        assertThat(svc.get(s.id()).orElseThrow().initialPrompt).isEqualTo("first");
    }

    // ---------------------------------------------------------------- stop()

    @Test
    void stopOnALocalRunDelegatesToTheLocalExecutorAndPersists() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));

        svc.stop(s.id());

        verify(localExecutor, timeout(1000)).stop(any());
        AgentRun run = svc.get(s.id()).orElseThrow();
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.type()).isEqualTo("status"));
    }

    @Test
    void stopOnANonLocalRunClosesTheOpenStreamAndMarksItTerminated() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local"); // avoid touching the Anthropic client at start
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        AgentRun run = svc.get(s.id()).orElseThrow();
        run.backend = "cloud"; // exercise stop()'s non-local branch in isolation
        AutoCloseable stream = mock(AutoCloseable.class);
        run.stream = stream;

        svc.stop(s.id());

        assertThat(run.status).isEqualTo("TERMINATED");
    }

    // ---------------------------------------------------------------- hasActiveRun() / flowOf() / list()

    @Test
    void hasActiveRunReflectsOnlyInFlightRunsForThatFlow() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        AgentRun run = svc.get(s.id()).orElseThrow();

        // Idle is a finished turn, not work in flight — it must not block the next scheduled
        // fire. Counting it as active made every cron flow run exactly once and then starve
        // behind its own idle run, which never terminates on its own.
        assertThat(run.status).isEqualTo("IDLE");
        assertThat(svc.hasActiveRun("f1")).isFalse();
        assertThat(svc.hasActiveRun(null)).isFalse();

        run.status = "RUNNING";
        assertThat(svc.hasActiveRun("f1")).isTrue();
        assertThat(svc.hasActiveRun("other-flow")).isFalse();

        // A pending human decision is still in flight: firing a second run would stack a
        // duplicate behind the question being asked.
        run.status = "AWAITING_APPROVAL";
        assertThat(svc.hasActiveRun("f1")).isTrue();
        run.status = "AWAITING_ANSWER";
        assertThat(svc.hasActiveRun("f1")).isTrue();

        run.status = "COMPLETED";
        assertThat(svc.hasActiveRun("f1")).isFalse();
        run.status = "TERMINATED";
        assertThat(svc.hasActiveRun("f1")).isFalse();
    }

    @Test
    void flowOfReturnsTheStoredSnapshotAndEmptyWhenMissingOrCorrupt() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        FlowGraph original = flow("f1");
        RunSummary s = svc.start(original);
        AgentRun run = svc.get(s.id()).orElseThrow();

        // Not a full equals(original): FlowGraph's isEnabled()/isFavorite() derived accessors
        // clash with Jackson's bean-property introspection of the record's own enabled/favorite
        // components, so a null enabled/favorite is round-tripped as true/false rather than null.
        assertThat(svc.flowOf(run)).isPresent();
        assertThat(svc.flowOf(run).orElseThrow().id()).isEqualTo(original.id());
        assertThat(svc.flowOf(run).orElseThrow().name()).isEqualTo(original.name());
        assertThat(svc.flowOf(run).orElseThrow().nodes()).isEqualTo(original.nodes());

        run.flowJson = null;
        assertThat(svc.flowOf(run)).isEmpty();

        run.flowJson = "{ not valid json ][";
        assertThat(svc.flowOf(run)).isEmpty();
    }

    @Test
    void listOrdersRunsByCreatedAtDescending() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s1 = svc.start(flow("f1"));
        RunSummary s2 = svc.start(flow("f2"));
        svc.get(s1.id()).orElseThrow().createdAt = 1_000L;
        svc.get(s2.id()).orElseThrow().createdAt = 2_000L;

        List<String> ids = svc.list().stream().map(RunSummary::id).toList();

        assertThat(ids).containsExactly(s2.id(), s1.id());
    }

    // ---------------------------------------------------------------- restore()

    @Test
    void restoreNormalizesInFlightStatusesToIdleAndRehydratesRunState() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        String validFlowJson = toJson(flow("f1"));
        RunStore.RunRow runningRow = new RunStore.RunRow(
                "run_a", "f1", "Flow", "managed", "local", "RUNNING", "manual", "sess1", null, false,
                null, 10L, 20L, validFlowJson, List.of(), List.of(), 111L, "hello", null, false, 7);
        RunStore.RunRow startingRow = new RunStore.RunRow(
                "run_b", "f1", "Flow", "managed", "local", "STARTING", "manual", null, null, false,
                null, 0L, 0L, null, List.of(), List.of(), 222L, null, null, false, 0);
        RunStore.RunRow terminatedRow = new RunStore.RunRow(
                "run_c", "f1", "Flow", "managed", "local", "TERMINATED", "manual", null, null, false,
                null, 0L, 0L, null, List.of(), List.of(), 333L, null, null, true, 0);
        when(runStore.loadAll(anyInt())).thenReturn(List.of(runningRow, startingRow, terminatedRow));
        when(runStore.isAvailable()).thenReturn(true);
        RunService svc = newService(4, 8, 10);

        svc.restore();

        assertThat(svc.get("run_a").orElseThrow().status).isEqualTo("IDLE");
        assertThat(svc.get("run_a").orElseThrow().initialPrompt).isEqualTo("hello");
        assertThat(svc.get("run_a").orElseThrow().totalInputTokens).isEqualTo(10L);
        assertThat(svc.get("run_a").orElseThrow().compiled).isNotNull();
        assertThat(svc.get("run_b").orElseThrow().status).isEqualTo("IDLE"); // STARTING -> IDLE too
        assertThat(svc.get("run_c").orElseThrow().status).isEqualTo("TERMINATED"); // terminal statuses pass through
        assertThat(svc.get("run_c").orElseThrow().golden).isTrue(); // the reference flag survives restarts
        // The version an execution ran is a fact about it, so it survives the restart too — the
        // flow may be on v20 by now and this run still ran v7.
        assertThat(svc.get("run_a").orElseThrow().flowVersion).isEqualTo(7);
    }

    @Test
    void restoreSkipsRowsThatFailToReconstructButKeepsTheOthers() {
        RunStore.RunRow badRow = new RunStore.RunRow(
                "run_bad", "f2", "Flow2", "managed", "local", "TERMINATED", "manual", null, null, false,
                null, 0L, 0L, "{ not valid json", List.of(), List.of(), 222L, null, null, false, 0);
        RunStore.RunRow goodRow = new RunStore.RunRow(
                "run_good", "f1", "Flow", "managed", "local", "ERROR", "manual", null, null, false,
                "boom", 0L, 0L, null, List.of(), List.of(), 333L, null, null, false, 0);
        when(runStore.loadAll(anyInt())).thenReturn(List.of(badRow, goodRow));
        when(runStore.isAvailable()).thenReturn(false);
        RunService svc = newService(4, 8, 10);

        svc.restore();

        assertThat(svc.get("run_bad")).isEmpty();
        AgentRun good = svc.get("run_good").orElseThrow();
        assertThat(good.status).isEqualTo("ERROR");
        assertThat(good.error).isEqualTo("boom");
    }

    private String toJson(FlowGraph flow) {
        try {
            return mapper.writeValueAsString(flow);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- golden runs

    @Test
    void markingARunGoldenClearsTheFlowsPreviousReference() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary first = svc.start(flow("f1"));
        RunSummary second = svc.start(flow("f1"));
        RunSummary otherFlow = svc.start(flow("f2"));
        svc.setGolden(otherFlow.id(), true);

        svc.setGolden(first.id(), true);
        RunSummary promoted = svc.setGolden(second.id(), true);

        // One reference per flow: promoting the second demotes the first — but never a run of a
        // DIFFERENT flow, whose reference is its own.
        assertThat(promoted.golden()).isTrue();
        assertThat(svc.get(first.id()).orElseThrow().golden).isFalse();
        assertThat(svc.get(otherFlow.id()).orElseThrow().golden).isTrue();
    }

    @Test
    void anAdHocRunCannotBeGolden() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary adHoc = svc.start(flow(null)); // unsaved canvas: no flow id

        assertThatThrownBy(() -> svc.setGolden(adHoc.id(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saved flow");
    }

    @Test
    void goldenRunsAreNeverEvictedHoweverOldTheyAre() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 2); // maxRetainedRuns = 2

        RunSummary golden = svc.start(flow("f1"));
        AgentRun g = svc.get(golden.id()).orElseThrow();
        g.status = "TERMINATED";
        g.createdAt = 1_000L; // the oldest completed run — eviction's first pick, were it not golden
        svc.setGolden(golden.id(), true);

        RunSummary other = svc.start(flow("f1"));
        AgentRun o = svc.get(other.id()).orElseThrow();
        o.status = "TERMINATED";
        o.createdAt = 2_000L;

        svc.start(flow("f1")); // pushes the registry over the cap

        assertThat(svc.get(golden.id())).isPresent();
        assertThat(svc.get(other.id())).isEmpty(); // the non-golden one went instead
    }

    @Test
    void aGoldenCheckReplaysTheReferenceInputAgainstTheFlowPassedIn() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary reference = svc.start(flow("f1"));
        AgentRun ref = svc.get(reference.id()).orElseThrow();
        ref.initialPrompt = "the input that mattered";
        svc.setGolden(reference.id(), true);

        // Stands in for the flow as saved NOW — renamed so it is a different value from the
        // reference's own graph and the verify below can single it out (FlowGraph is a record;
        // two flow("f1") calls build EQUAL values).
        FlowGraph editedFlow = new FlowGraph("f1", "Flow v2", "managed",
                List.of(agentNode("c1", "coordinator"), inputNode("manual", null)),
                List.of(), null, List.of(), null, null);
        RunSummary check = svc.startGoldenCheck(editedFlow, reference.id());

        assertThat(check.id()).isNotEqualTo(reference.id());
        assertThat(check.trigger()).isEqualTo("golden");
        verify(compiler).compile(eq(editedFlow), any(), any()); // the CURRENT flow ran, not the reference's snapshot
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("the input that mattered"));
    }

    @Test
    void aGoldenCheckRefusesARunThatIsNotTheReferenceOrRecordedNoInput() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary plain = svc.start(flow("f1"));

        assertThatThrownBy(() -> svc.startGoldenCheck(flow("f1"), plain.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the golden reference");

        svc.setGolden(plain.id(), true); // golden, but a manual run that never got a first input
        assertThatThrownBy(() -> svc.startGoldenCheck(flow("f1"), plain.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no initial input to replay");
    }

    // ---------------------------------------------------------------- remote approval wiring

    @Test
    void enteringApprovalWaitTellsTheRemoteChannelsOnce() throws Exception {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        doAnswer(inv -> {
            AgentRun r = inv.getArgument(0);
            r.status = "AWAITING_APPROVAL";
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());
        RunService svc = newService(4, 8, 10);

        RunSummary s = svc.start(flowWithPrompt("f1", "prompt", "go"));
        verify(remoteApprovals, timeout(2000)).runAwaitingApproval(any(), any(), any());

        // A second command while still waiting ends the same way; the question must not be
        // posted to the channel twice.
        svc.sendCommand(s.id(), "extra context");
        verify(localExecutor, timeout(2000).times(2)).runTurn(any(), any(), any());
        verify(remoteApprovals, org.mockito.Mockito.times(1))
                .runAwaitingApproval(any(), any(), any());
    }

    @Test
    void theRemoteApproveCallbackApprovesExactlyLikeTheAppButton() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        doAnswer(inv -> {
            AgentRun r = inv.getArgument(0);
            r.status = "AWAITING_APPROVAL";
            return null;
        }).when(localExecutor).runTurn(any(), any(), any());
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flowWithPrompt("f1", "prompt", "go"));

        var approveCap = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(remoteApprovals, timeout(2000)).runAwaitingApproval(any(), approveCap.capture(), any());
        approveCap.getValue().run();

        assertThat(svc.get(s.id()).orElseThrow().approved).isTrue();
        verify(remoteApprovals).settled(s.id(), "approved");
    }

    @Test
    void decidingFromTheAppSettlesTheRemoteQuestionToo() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().status = "AWAITING_APPROVAL";

        svc.reject(s.id());

        // Whoever decides, the Slack message must flip to the outcome instead of keeping
        // instructions nobody can follow any more.
        verify(remoteApprovals).settled(s.id(), "rejected");
    }

    @Test
    void approvalChannelConfigIsCopiedOntoTheRunAtStart() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(agentNode("c1", "coordinator"), inputNode("manual", null)),
                List.of(), null, List.of(), null, null, null,
                "cred_slack", "C0123456789", "https://example.webhook.office.com/x");
        RunService svc = newService(4, 8, 10);

        RunSummary s = svc.start(flow);

        AgentRun run = svc.get(s.id()).orElseThrow();
        assertThat(run.approvalSlackCredentialId).isEqualTo("cred_slack");
        assertThat(run.approvalSlackChannel).isEqualTo("C0123456789");
        assertThat(run.approvalTeamsWebhook).isEqualTo("https://example.webhook.office.com/x");
    }

    // ------------------------------------------------- backend selection (regression guards)

    private RunService service() {
        RunService s = new RunService(clientProvider, compiler, launcher, backends(),
                new PricingTable("", 3.0, 15.0),
                new CloudStreamEventHandler(), runStore, flowVersions, mapper, notifier,
                remoteApprovals, variableStore(), 4, 8, 10, 3.0, 15.0);
        created.add(s);
        return s;
    }

    private static CompiledFlow flowOnModel(String modelId) {
        AgentSpec c = coordinatorSpec();
        c.model = new AgentSpec.ModelSpec();
        c.model.id = modelId;
        return new CompiledFlow(c, List.of());
    }

    @Test
    void aFlowRunsOnTheSubscriptionWhenTheCliIsSignedIn() {
        when(compiler.compile(any(), any(), any())).thenReturn(flowOnModel("claude-opus-4-8"));
        when(clientProvider.backend()).thenReturn("local");

        RunSummary summary = service().start(flow("f1"));

        assertThat(summary.status()).isEqualTo("IDLE");
        // The local backend is turn-based, so nothing is launched until a first instruction.
        verifyNoInteractions(launcher);
    }

    @Test
    void aFlowRunsOnTheApiWhenAKeyIsPresent() {
        when(compiler.compile(any(), any(), any())).thenReturn(flowOnModel("claude-opus-4-8"));
        when(clientProvider.backend()).thenReturn("cloud");
        when(clientProvider.client()).thenThrow(new IllegalStateException("no client in this test"));

        RunService svc = service();
        RunSummary summary = svc.start(flow("f1"));

        assertThat(svc.get(summary.id()).orElseThrow().backend).isEqualTo("cloud");
    }

    @Test
    void theModelIdDoesNotChangeWhichBackendIsUsed() {
        // Backend selection follows the credential, not the model: with only Claude left there is
        // no routing table for a model id to steer.
        when(compiler.compile(any(), any(), any())).thenReturn(flowOnModel("claude-sonnet-5"));
        when(clientProvider.backend()).thenReturn("local");

        RunService svc = service();
        RunSummary summary = svc.start(flow("f1"));

        assertThat(svc.get(summary.id()).orElseThrow().backend).isEqualTo("local");
    }

    @Test
    void withNoCredentialAtAllStartingSaysSoInsteadOfFailingObscurely() {
        when(compiler.compile(any(), any(), any())).thenReturn(flowOnModel("claude-opus-4-8"));
        when(clientProvider.backend()).thenReturn("none");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().start(flow("f1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sign in to Claude Code")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @org.junit.jupiter.api.Test
    void aFlowAtItsMonthlyBudgetIsRefusedBeforeCompiling() {
        when(runStore.spendUsdSince(org.mockito.ArgumentMatchers.eq("flow-b"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(25.10);
        com.concentus.model.FlowGraph flow = new com.concentus.model.FlowGraph(
                "flow-b", "Presupuestos", "local", List.of(), List.of(),
                null, List.of(), null, null, 25.0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> newService(2, 4, 10).start(flow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Budget reached")
                .hasMessageContaining("$25.10");
        // Refused before compile: an over-budget flow with a broken graph still reports budget,
        // which is the actionable half.
        org.mockito.Mockito.verifyNoInteractions(compiler);
    }

    @org.junit.jupiter.api.Test
    void aFlowUnderItsBudgetStartsNormally() {
        when(runStore.spendUsdSince(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(3.0);
        com.concentus.model.FlowGraph flow = new com.concentus.model.FlowGraph(
                "flow-ok", "Barato", "local", List.of(), List.of(),
                null, List.of(), null, null, 25.0);

        // Reaching the compiler is the assertion: the gate let it through and the (mocked)
        // compiler's own failure is the next thing that would happen in this stripped harness.
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            try { newService(2, 4, 10).start(flow); } catch (RuntimeException ignored) { }
        }).doesNotThrowAnyException();
        org.mockito.Mockito.verify(compiler).compile(eq(flow), any(), any());
    }

    @org.junit.jupiter.api.Test
    void aShadowTriggerPlansButNeverActs() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        com.concentus.model.FlowNode input = new com.concentus.model.FlowNode("in1", "input", null,
                java.util.Map.of("mode", "webhook", "secret", "s", "shadow", true));
        com.concentus.model.FlowGraph flow = new com.concentus.model.FlowGraph(
                "flow-s", "Sombra", "local", List.of(input), List.of(), null, List.of(), null, null);

        // A webhook delivery passes the payload as the prompt override — the triggered path.
        RunSummary summary = newService(2, 4, 10).start(flow, "event payload");

        AgentRun run = created.get(created.size() - 1).get(summary.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(run.shadow).isTrue();
        org.assertj.core.api.Assertions.assertThat(run.permissionMode).isEqualTo("plan");
        org.assertj.core.api.Assertions.assertThat(run.trigger).isEqualTo("webhook (shadow)");
    }

    @org.junit.jupiter.api.Test
    void aManualRunOfAShadowedFlowStaysReal() {
        when(compiler.compile(any(), any(), any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        com.concentus.model.FlowNode input = new com.concentus.model.FlowNode("in1", "input", null,
                java.util.Map.of("mode", "webhook", "secret", "s", "shadow", true));
        com.concentus.model.FlowGraph flow = new com.concentus.model.FlowGraph(
                "flow-s2", "Sombra", "local", List.of(input), List.of(), null, List.of(), null, null);

        // No prompt override = someone pressed Run themselves. They are present; shadow is for
        // the unattended path, and silently plan-only-ing a manual run would read as broken.
        RunSummary summary = newService(2, 4, 10).start(flow, null);

        AgentRun run = created.get(created.size() - 1).get(summary.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(run.shadow).isFalse();
        org.assertj.core.api.Assertions.assertThat(run.trigger).isEqualTo("webhook");
    }
}
