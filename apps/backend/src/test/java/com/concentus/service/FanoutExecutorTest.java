package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fan-out as a whole, against fake processes: real threads, real workspaces on disk, real
 * stream-json parsing — only the {@code claude} binary is simulated, because a unit test cannot
 * spend tokens. What is asserted is exactly what the single-session path could never give:
 * isolation (each worker sees only its own instructions), parallel capture per node, timeout
 * enforcement, and a combined report that names failures instead of hiding them.
 */
class FanoutExecutorTest {

    @TempDir
    Path dataDir;

    // ------------------------------------------------------------------ fixtures

    /** A worker's happy-path stream: one text message, then a final result with usage. */
    private static String okStream(String text, String result) {
        return """
                {"type":"system","subtype":"init","model":"claude-sonnet-5"}
                {"type":"assistant","message":{"usage":{"input_tokens":10,"output_tokens":5},"content":[{"type":"text","text":"%s"}]}}
                {"type":"result","is_error":false,"result":"%s","usage":{"input_tokens":10,"output_tokens":5}}
                """.formatted(text, result);
    }

    private final com.concentus.store.FacadeProfileStore profiles =
            org.mockito.Mockito.mock(com.concentus.store.FacadeProfileStore.class);

    private FanoutExecutor executor(FanoutExecutor.ProcessStarter starter, int timeoutSeconds,
                                    int retries) {
        return new FanoutExecutor(new LocalClaudeSupport("claude"),
                new RagContextInjector(null, null), new ContextFolderResolver(""),
                new com.fasterxml.jackson.databind.ObjectMapper(), profiles,
                dataDir.toString(), "bypassPermissions", 8734, 4, timeoutSeconds, retries, starter);
    }

    private static AgentSpec spec(String nodeId, String name, String cliName, String prompt) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        s.cliName = cliName;
        s.systemPrompt = prompt;
        return s;
    }

    private static AgentRun run(AgentSpec... workers) {
        return runWithMerger(null, workers);
    }

    private static AgentRun runWithMerger(AgentSpec merger, AgentSpec... workers) {
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.nodeId = "c1";
        coord.name = "Coordinator";
        coord.execution = "fanout";
        run.compiled = new CompiledFlow(coord, List.of(workers), merger);
        return run;
    }

    private static NodeExec exec(AgentRun run, String nodeId) {
        return run.nodeExecList().stream().filter(n -> nodeId.equals(n.nodeId)).findFirst()
                .orElse(null);
    }

    /** A finished process whose whole life is a canned stdout and an exit code. */
    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final int exit;

        FakeProcess(String output, int exit) {
            this.stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.exit = exit;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }
        @Override public int exitValue() { return exit; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return false; }
    }

    /** A hung process: stdout produces nothing until {@code destroy()} ends it. */
    private static final class HungProcess extends Process {
        private final CountDownLatch killed = new CountDownLatch(1);
        volatile boolean destroyed;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }

        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    try {
                        killed.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return -1; // EOF once killed — exactly how a real dead child reads
                }
            };
        }

        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        @Override public int waitFor() throws InterruptedException {
            killed.await();
            return 143;
        }

        @Override public int exitValue() { return 143; }

        @Override public void destroy() {
            destroyed = true;
            killed.countDown();
        }

        @Override public boolean isAlive() { return killed.getCount() > 0; }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoWorkersRunAsTwoIsolatedProcessesAndTheReportCombinesBoth() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "You review backend code.");
        AgentSpec b = spec("n2", "Worker B", "worker-b", "You review frontend code.");
        AgentRun run = run(a, b);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        Map<String, FakeProcess> byFolder = new ConcurrentHashMap<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            String folder = workdir.getFileName().toString();
            FakeProcess p = new FakeProcess(
                    okStream("Hola de " + folder, "Informe " + folder), 0);
            byFolder.put(folder, p);
            return p;
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "Revisa el cambio");

        assertThat(spawned).hasSize(2);
        // Each worker got its own workspace with ONLY its own instructions — the isolation the
        // single shared session could never provide.
        String claudeA = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "CLAUDE.md")));
        assertThat(claudeA).contains("You review backend code.")
                .doesNotContain("You review frontend code.");
        String mcp = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcp).isEqualTo("{\"mcpServers\":{}}");

        assertThat(exec(run, "n1").output).contains("Hola de worker-a");
        assertThat(exec(run, "n1").status).isEqualTo("passed");
        assertThat(exec(run, "n2").status).isEqualTo("passed");
        // Tokens: assistant usage per node, result usage into the run's totals.
        assertThat(exec(run, "n1").inputTokens).isEqualTo(10);
        assertThat(run.totalInputTokens).isEqualTo(20);
        assertThat(run.totalOutputTokens).isEqualTo(10);

        NodeExec coord = exec(run, "c1");
        assertThat(coord.output).contains("Informe worker-a").contains("Informe worker-b");
        assertThat(coord.status).isEqualTo("passed");
        assertThat(run.status).isEqualTo("IDLE");
        assertThat(run.finalOutput()).contains("Informe worker-a");
    }

    @Test
    void aWorkerThatFailsToSpawnIsRetriedThenReportedByName() {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec b = spec("n2", "Worker B", "worker-b", "");
        AgentRun run = run(a, b);

        AtomicInteger aAttempts = new AtomicInteger();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            if (workdir.toString().contains("worker-a")) {
                aAttempts.incrementAndGet();
                throw new IOException("boom");
            }
            return new FakeProcess(okStream("hola", "Informe B"), 0);
        };

        executor(starter, 900, 1).runTurn(run, run.compiled, "go");

        assertThat(aAttempts.get()).isEqualTo(2); // first try + the one configured retry
        assertThat(exec(run, "n1").status).isEqualTo("failed");
        assertThat(exec(run, "n1").error).contains("boom");
        // One worker's death does not sink the turn: the report names it and the rest is real.
        assertThat(run.status).isEqualTo("IDLE");
        assertThat(exec(run, "c1").output).contains("Worker A — FAILED").contains("Informe B");
    }

    @Test
    void whenEveryWorkerFailsTheRunFails() {
        AgentRun run = run(spec("n1", "Worker A", "worker-a", ""));
        FanoutExecutor.ProcessStarter starter =
                (args, workdir) -> new FakeProcess("{\"type\":\"result\",\"is_error\":true,"
                        + "\"result\":\"credit exhausted\"}", 1);

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(exec(run, "n1").status).isEqualTo("failed");
        assertThat(exec(run, "n1").error).contains("credit exhausted");
    }

    @Test
    void aHungWorkerIsKilledAtTheTimeoutAndNotRetried() {
        AgentRun run = run(spec("n1", "Worker A", "worker-a", ""));
        HungProcess hung = new HungProcess();
        AtomicInteger attempts = new AtomicInteger();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            attempts.incrementAndGet();
            return hung;
        };

        executor(starter, 1, 3).runTurn(run, run.compiled, "go");

        assertThat(hung.destroyed).isTrue();
        assertThat(attempts.get()).isEqualTo(1); // a retried timeout is just a doubled timeout
        assertThat(exec(run, "n1").status).isEqualTo("failed");
        assertThat(exec(run, "n1").error).contains("timed out");
    }

    @Test
    void stopKillsEveryLiveWorker() throws Exception {
        AgentRun run = run(spec("n1", "Worker A", "worker-a", ""));
        HungProcess hung = new HungProcess();
        CountDownLatch started = new CountDownLatch(1);
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            started.countDown();
            return hung;
        };
        FanoutExecutor executor = executor(starter, 900, 0);

        Thread turn = new Thread(() -> executor.runTurn(run, run.compiled, "go"));
        turn.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        run.status = "TERMINATED"; // what LocalClaudeExecutor.stop() sets
        executor.stopWorkers(run);
        turn.join(5_000);

        assertThat(turn.isAlive()).isFalse();
        assertThat(hung.destroyed).isTrue();
    }

    @Test
    void withNoDrawnSubAgentsTheCoordinatorPlansAndItemsBecomeWorkers() throws Exception {
        AgentRun run = run(); // coordinator only — the plan is the coordinator's to submit
        org.mockito.Mockito.when(profiles.get("fprof_1")).thenReturn(java.util.Optional.of(
                new com.concentus.model.FacadeProfile("fprof_1", "reader", "", List.of(), true, null)));

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            if (workdir.toString().contains("coordinator")) {
                // What the real CLI does through the plan endpoint, minus the HTTP hop.
                run.submittedPlan = new com.concentus.model.WorkPlan("split it", List.of(
                        new com.concentus.model.WorkPlan.WorkItem("a", "Backend half", "do the backend",
                                List.of("only backend facts"), null, null, "claude-sonnet-5",
                                "reader", "fprof_1", null),
                        new com.concentus.model.WorkPlan.WorkItem("b", null, "do the frontend",
                                null, null, null, null, null, "", null)));
                return new FakeProcess(okStream("Plan listo", "Plan listo"), 0);
            }
            return new FakeProcess(okStream("hecho", "hecho"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "Haz el cambio grande");

        assertThat(spawned).hasSize(3); // planner + two plan-born workers
        // The planner is read-only and can only ever plan: no shell, no edits, no delegation.
        assertThat(spawned.get(0)).containsSequence("--disallowedTools",
                "Task,Bash,Write,Edit,NotebookEdit");
        String planMcp = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "coordinator", "mcp-config.json")));
        assertThat(planMcp).contains("/api/runs/run-1/plan").doesNotContain("/tools");

        // Each worker ran ITS OWN prompt, not the turn's text.
        assertThat(String.join(" ", spawned.get(1)) + String.join(" ", spawned.get(2)))
                .contains("do the backend").contains("do the frontend")
                .doesNotContain("Haz el cambio grande");

        // Synthetic boxes: node ids carry the worker: prefix, kind says worker, model attributed.
        NodeExec a = exec(run, "worker:a");
        assertThat(a.kind).isEqualTo("worker");
        assertThat(a.label).isEqualTo("Backend half");
        assertThat(a.model).isEqualTo("claude-sonnet-5");
        assertThat(a.status).isEqualTo("passed");
        assertThat(exec(run, "worker:b").status).isEqualTo("passed");
        // Item context became the worker's CLAUDE.md; the facade profile froze onto the run.
        String claudeA = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "w-a", "CLAUDE.md")));
        assertThat(claudeA).contains("only backend facts");
        assertThat(run.workerFacadeProfiles).isEmpty(); // no MCP servers wired → no facade at all
        assertThat(run.status).isEqualTo("IDLE");
    }

    @Test
    void aCoordinatorThatNeverSubmitsAPlanFailsTheTurnPlainly() {
        AgentRun run = run();
        FanoutExecutor.ProcessStarter starter = (args, workdir) ->
                new FakeProcess(okStream("no sé qué hacer", "no sé qué hacer"), 0);

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("without submitting a plan").contains("no sé qué hacer");
        assertThat(exec(run, "c1").status).isEqualTo("failed");
    }

    @Test
    void aWorkerWithMcpAndAProfileGetsItsFacadeAndOnlyItsFacade() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec.McpServerSpec mcp = new AgentSpec.McpServerSpec();
        mcp.name = "holded";
        mcp.url = "https://mcp.example.com/mcp";
        a.mcpServers.add(mcp);
        a.facadeProfileId = "fprof_1";
        org.mockito.Mockito.when(profiles.get("fprof_1")).thenReturn(java.util.Optional.of(
                new com.concentus.model.FacadeProfile("fprof_1", "reader", "", List.of("contact"),
                        true, null)));
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        // The ONLY server the worker sees is its own facade endpoint — never the real MCP URL.
        assertThat(mcpConfig).contains("/api/runs/run-1/workers/n1/tools")
                .doesNotContain("mcp.example.com");
        assertThat(mcpConfig).contains(run.workerToolTokens.get("n1"));
        assertThat(run.workerFacadeProfiles.get("n1").name()).isEqualTo("reader");
        String claudeMd = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "CLAUDE.md")));
        assertThat(claudeMd).contains("DRY RUN");
    }

    @Test
    void aWorkerWithMcpButNoProfileGetsNoMcpAtAllAndIsToldWhy() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec.McpServerSpec mcp = new AgentSpec.McpServerSpec();
        mcp.name = "holded";
        mcp.url = "https://mcp.example.com/mcp";
        a.mcpServers.add(mcp);
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).isEqualTo("{\"mcpServers\":{}}");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("no facade profile"));
    }

    @Test
    void theMergeStepRunsAfterTheWorkersAndSpeaksLast() throws Exception {
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        AgentSpec merger = spec("m1", "Merge", "merge", "Prefer the stricter reading.");
        AgentRun run = runWithMerger(merger, worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        // Like a real CLI stream, the result event repeats the final assistant text.
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            return workdir.toString().contains("merge")
                    ? new FakeProcess(okStream("Resultado final fusionado", "Resultado final fusionado"), 0)
                    : new FakeProcess(okStream("Informe A", "Informe A"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "Revisa el cambio");

        assertThat(spawned).hasSize(2);
        // The worker lost Bash; the merge kept it (its job is running the checks).
        assertThat(spawned.get(0)).containsSequence("--disallowedTools", "Task,Bash");
        assertThat(spawned.get(1)).containsSequence("--disallowedTools", "Task");
        // The merge's prompt carries the worker's report; its CLAUDE.md carries its instructions.
        String mergePrompt = String.join(" ", spawned.get(1));
        assertThat(mergePrompt).contains("Informe A").contains("Worker A");
        String mergeMd = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "merge", "CLAUDE.md")));
        assertThat(mergeMd).contains("merge step").contains("Prefer the stricter reading.");

        assertThat(exec(run, "m1").status).isEqualTo("passed");
        assertThat(exec(run, "m1").output).contains("Resultado final fusionado");
        assertThat(run.status).isEqualTo("IDLE");
        // The run's last word is the merge's, not the raw combined report.
        assertThat(run.finalOutput()).contains("Resultado final fusionado");
    }

    @Test
    void aFailedMergeFailsTheRunButKeepsTheWorkersReport() {
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        AgentSpec merger = spec("m1", "Merge", "merge", "");
        AgentRun run = runWithMerger(merger, worker);

        FanoutExecutor.ProcessStarter starter = (args, workdir) ->
                workdir.toString().contains("merge")
                        ? new FakeProcess("{\"type\":\"result\",\"is_error\":true,\"result\":\"merge blew up\"}", 1)
                        : new FakeProcess(okStream("hola", "Informe A"), 0);

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("merge step failed");
        assertThat(exec(run, "m1").status).isEqualTo("failed");
        // The workers' work is not lost: the combined report still sits on the coordinator.
        assertThat(exec(run, "c1").output).contains("Informe A");
        assertThat(exec(run, "n1").status).isEqualTo("passed");
    }

    @Test
    void theFanoutFlagOnlyEngagesWhenTheCoordinatorNamesIt() {
        AgentSpec coord = new AgentSpec();
        coord.execution = "fanout";
        coord.validate();
        assertThat(coord.execution).isEqualTo("fanout");

        // A typo must never switch a flow onto the experimental path.
        AgentSpec typo = new AgentSpec();
        typo.execution = "fan-out";
        typo.validate();
        assertThat(typo.execution).isEmpty();
        assertThat(new CompiledFlow(typo, List.of()).fanout()).isFalse();
    }
}
