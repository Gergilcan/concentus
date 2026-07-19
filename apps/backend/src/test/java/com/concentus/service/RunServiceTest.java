package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
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
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<RunService> created = new ArrayList<>();

    @AfterEach
    void shutdownAll() {
        created.forEach(RunService::shutdown);
    }

    private RunService newService(int maxConcurrent, int queueCapacity, int maxRetainedRuns) {
        RunService s = new RunService(clientProvider, compiler, launcher, localExecutor, runStore, mapper,
                notifier, maxConcurrent, queueCapacity, maxRetainedRuns, 3.0, 15.0);
        created.add(s);
        return s;
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("none");
        RunService svc = newService(4, 8, 10);

        assertThatThrownBy(() -> svc.start(flow("f1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not signed in");
    }

    // ---------------------------------------------------------------- start(): local backend

    @Test
    void startLocalWithoutAutoStartLeavesRunIdleAndNeverTouchesTheExecutor() {
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);

        RunSummary summary = svc.start(flowWithPrompt("f1", "prompt", "go"));

        assertThat(summary.trigger()).isEqualTo("prompt");
        verify(localExecutor, timeout(2000)).runTurn(any(), any(), eq("go"));
    }

    // ---------------------------------------------------------------- start(): cloud backend

    @Test
    void cloudBackendFailureToObtainAClientMarksTheRunErrorAndNotifies() throws Exception {
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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

    // ---------------------------------------------------------------- retry()

    @Test
    void retryStartsANewRunFromTheStoredFlowSnapshotWithoutTouchingTheOriginal() {
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));
        svc.get(s.id()).orElseThrow().compiled = null;

        assertThatThrownBy(() -> svc.sendCommand(s.id(), "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready yet");
    }

    @Test
    void sendCommandSetsInitialPromptOnlyOnTheFirstCommand() throws Exception {
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
    void hasActiveRunReflectsOnlyNonTerminalRunsForThatFlow() {
        when(compiler.compile(any())).thenReturn(compiledFlow());
        when(clientProvider.backend()).thenReturn("local");
        RunService svc = newService(4, 8, 10);
        RunSummary s = svc.start(flow("f1"));

        assertThat(svc.hasActiveRun("f1")).isTrue();
        assertThat(svc.hasActiveRun("other-flow")).isFalse();
        assertThat(svc.hasActiveRun(null)).isFalse();

        svc.get(s.id()).orElseThrow().status = "TERMINATED";
        assertThat(svc.hasActiveRun("f1")).isFalse();
    }

    @Test
    void flowOfReturnsTheStoredSnapshotAndEmptyWhenMissingOrCorrupt() {
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
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
        when(compiler.compile(any())).thenReturn(compiledFlow());
        String validFlowJson = toJson(flow("f1"));
        RunStore.RunRow runningRow = new RunStore.RunRow(
                "run_a", "f1", "Flow", "managed", "local", "RUNNING", "manual", "sess1", null, false,
                null, 10L, 20L, validFlowJson, List.of(), List.of(), 111L, "hello", null);
        RunStore.RunRow startingRow = new RunStore.RunRow(
                "run_b", "f1", "Flow", "managed", "local", "STARTING", "manual", null, null, false,
                null, 0L, 0L, null, List.of(), List.of(), 222L, null, null);
        RunStore.RunRow terminatedRow = new RunStore.RunRow(
                "run_c", "f1", "Flow", "managed", "local", "TERMINATED", "manual", null, null, false,
                null, 0L, 0L, null, List.of(), List.of(), 333L, null, null);
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
    }

    @Test
    void restoreSkipsRowsThatFailToReconstructButKeepsTheOthers() {
        RunStore.RunRow badRow = new RunStore.RunRow(
                "run_bad", "f2", "Flow2", "managed", "local", "TERMINATED", "manual", null, null, false,
                null, 0L, 0L, "{ not valid json", List.of(), List.of(), 222L, null, null);
        RunStore.RunRow goodRow = new RunStore.RunRow(
                "run_good", "f1", "Flow", "managed", "local", "ERROR", "manual", null, null, false,
                "boom", 0L, 0L, null, List.of(), List.of(), 333L, null, null);
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
}
