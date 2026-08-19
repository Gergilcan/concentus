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
                new RagContextInjector(null, null), new PreRunSubflows(null), new ContextFolderResolver(""),
                new com.fasterxml.jackson.databind.ObjectMapper(), profiles, null, null, null,
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
        return runWith(merger, null, workers);
    }

    private static AgentRun runWith(AgentSpec merger, AgentSpec verifier, AgentSpec... workers) {
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.nodeId = "c1";
        coord.name = "Coordinator";
        coord.execution = "fanout";
        run.compiled = new CompiledFlow(coord, List.of(workers), merger, verifier);
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
        // The retry is counted on the node — it is what the graph metrics report as unhealth.
        assertThat(exec(run, "n1").retries).isEqualTo(1);
        assertThat(exec(run, "n2").retries).isZero();
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
        // A solo coordinator is the one doing the work, so under the auto rule it may act while
        // planning — but delegation stays denied whatever the node says: a planner that could
        // fan out again is an unbounded tree, not a configuration.
        // Skill too: this flow assigned none, so the tool that would reach the machine's own goes.
        assertThat(spawned.get(0)).containsSequence("--disallowedTools", "Task,Skill");
        assertThat(String.join(" ", spawned.get(0))).doesNotContain("Bash");
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
    void plannerAccessDerivesFromWiringAndTheNodeCanForceEitherShape() {
        // Auto: workers wired → hands off; solo → it is the one working, may act.
        AgentSpec worker = spec("n1", "W", "w", "");
        assertThat(FanoutExecutor.plannerReadOnly(run(worker).compiled)).isTrue();
        assertThat(FanoutExecutor.plannerReadOnly(run().compiled)).isFalse();

        // Forced, in both directions, regardless of wiring.
        AgentRun forcedRo = run();
        forcedRo.compiled.coordinator().coordinatorAccess = "read-only";
        assertThat(FanoutExecutor.plannerReadOnly(forcedRo.compiled)).isTrue();
        AgentRun forcedAct = run(worker);
        forcedAct.compiled.coordinator().coordinatorAccess = "may-act";
        assertThat(FanoutExecutor.plannerReadOnly(forcedAct.compiled)).isFalse();
    }

    @Test
    void aForcedReadOnlyPlannerLosesEverythingButReading() {
        AgentRun run = run();
        run.compiled.coordinator().coordinatorAccess = "read-only";

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            run.submittedPlan = new com.concentus.model.WorkPlan("g", List.of(
                    new com.concentus.model.WorkPlan.WorkItem("a", null, "do it",
                            null, null, null, null, null, "", null)));
            return new FakeProcess(okStream("Plan listo", "Plan listo"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(spawned.get(0)).containsSequence("--disallowedTools",
                "Task,Bash,Write,Edit,NotebookEdit,Skill");
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

    // No profile is not a lock: a worker is only ever offered the servers drawn into its own node,
    // so withholding them protected nothing the wiring did not already protect — while producing
    // runs that read no data, reported the source as unreachable, and billed for the attempt.
    @Test
    void aWorkerWithMcpButNoProfileReachesWhatIsWiredToIt() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec.McpServerSpec mcp = new AgentSpec.McpServerSpec();
        mcp.name = "holded";
        mcp.url = "https://mcp.example.com/mcp";
        a.mcpServers.add(mcp);
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        // Still the facade endpoint and never the real MCP URL: the enforcement point does not
        // move, so assigning a profile later narrows a worker already running the right way.
        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).contains("concentus-facade").contains("/workers/n1/tools");
        assertThat(mcpConfig).doesNotContain("mcp.example.com");
        assertThat(run.workerFacadeProfiles.get("n1").readOnly()).isFalse();
        assertThat(run.workerFacadeProfiles.get("n1").dryRunEnabled()).isFalse();
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("no facade profile")
                        .contains("nothing filtered"));
    }

    // The mirror of the bug above: a worker told its writes might be simulated, when nothing
    // simulates them, reports a change it really made as a proposal — and the next run repeats it.
    @Test
    void aWorkerWithNoProfileIsNotToldItsWritesMayBeSimulated() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec.McpServerSpec mcp = new AgentSpec.McpServerSpec();
        mcp.name = "holded";
        mcp.url = "https://mcp.example.com/mcp";
        a.mcpServers.add(mcp);
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String claudeMd = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "CLAUDE.md")));
        assertThat(claudeMd).doesNotContain("DRY RUN");
    }

    // A local server is a process on this machine: there is nothing for the backend to proxy, so
    // the facade listed nothing and the worker reported the account as unreachable. The whole
    // Google stack of a real flow — ads, search console, analytics — is stdio.
    /** A local MCP server as a flow draws one: a command this machine runs, and no URL. */
    private static AgentSpec.McpServerSpec stdioServer(String name, String command, String pkg) {
        AgentSpec.McpServerSpec m = new AgentSpec.McpServerSpec();
        m.name = name;
        m.command = command;
        m.args = List.of("-y", pkg);
        return m;
    }

    private void withProfile(com.concentus.model.FacadeProfile profile) {
        org.mockito.Mockito.when(profiles.get(profile.id()))
                .thenReturn(java.util.Optional.of(profile));
    }

    @Test
    void aWorkerLaunchesItsLocalMcpServersItselfBecauseNothingCanProxyThem() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        a.mcpServers.add(stdioServer("google-ads", "npx", "mcp-google-ads"));
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).contains("google-ads").contains("stdio").contains("mcp-google-ads");
        // No facade entry: an endpoint that can only answer "no tools" is a server the worker
        // spends a turn discovering is empty.
        assertThat(mcpConfig).doesNotContain("concentus-facade");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("launches 1 local MCP server"));
    }

    @Test
    void remoteServersStillGoThroughTheFacadeAlongsideTheLocalOnes() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        a.mcpServers.add(stdioServer("google-ads", "npx", "mcp-google-ads"));
        AgentSpec.McpServerSpec remote = new AgentSpec.McpServerSpec();
        remote.name = "resend";
        remote.url = "https://mcp.resend.com/mcp";
        a.mcpServers.add(remote);
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).contains("concentus-facade").contains("google-ads");
        assertThat(mcpConfig).doesNotContain("mcp.resend.com");
    }

    // The gate has to mean something: a profile the backend cannot enforce on a server it never
    // sees would be a label, and the difference would only show up in what the worker changed.
    @Test
    void aRestrictingProfileWithholdsTheLocalServersAndSaysWhy() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        a.facadeProfileId = "fprof_1";
        a.mcpServers.add(stdioServer("google-ads", "npx", "mcp-google-ads"));
        AgentRun run = run(a);

        withProfile(new com.concentus.model.FacadeProfile("fprof_1", "solo lectura", "",
                List.of(), true, Boolean.FALSE));
        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).doesNotContain("mcp-google-ads");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("NOT given to worker")
                        .contains("does not proxy"));
    }

    @Test
    void aWorkerWithNoMcpWiredGetsNoFacadeAtAll() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentRun run = run(a);

        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(run, run.compiled, "go");

        String mcpConfig = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "workers", "worker-a", "mcp-config.json")));
        assertThat(mcpConfig).isEqualTo("{\"mcpServers\":{}}");
        assertThat(run.workerFacadeProfiles).isEmpty();
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
        assertThat(spawned.get(0)).containsSequence("--disallowedTools", "Task,Bash,Skill");
        assertThat(spawned.get(1)).containsSequence("--disallowedTools", "Task,Skill");
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
    void usageTotalsSurviveConcurrentWorkers() throws Exception {
        // The regression CI caught for real: two workers each reported 10 input tokens and the
        // run totalled 10, because `volatile +=` is a read-modify-write that loses updates.
        AgentRun run = run();
        int threads = 8, perThread = 1_000;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) run.accrueUsage(1, 2, 3, 4);
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(run.totalInputTokens).isEqualTo((long) threads * perThread);
        assertThat(run.totalOutputTokens).isEqualTo(2L * threads * perThread);
        assertThat(run.cacheReadTokens).isEqualTo(3L * threads * perThread);
        assertThat(run.cacheWriteTokens).isEqualTo(4L * threads * perThread);
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

    // ------------------------------------------------------------------ verifier step

    @Test
    void theVerifierJudgesBetweenWorkersAndMergeAndARejectionNeverReachesTheMerge() throws Exception {
        AgentSpec a = spec("n1", "Worker A", "worker-a", "");
        AgentSpec b = spec("n2", "Worker B", "worker-b", "");
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "Reject unverified numbers.");
        AgentSpec merger = spec("m1", "Merge", "merge", "");
        AgentRun run = runWith(merger, verifier, a, b);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            String dir = workdir.toString();
            if (dir.endsWith("verifier")) {
                // What the real CLI does through the verdict endpoint, minus the HTTP hop.
                run.submittedVerdict = new com.concentus.model.WorkVerdict("B invents", List.of(
                        new com.concentus.model.WorkVerdict.Item("n1", "accept", null),
                        new com.concentus.model.WorkVerdict.Item("n2", "reject", "invented numbers")));
                return new FakeProcess(okStream("Veredicto emitido", "Veredicto emitido"), 0);
            }
            if (dir.endsWith("merge")) {
                return new FakeProcess(okStream("Resultado final", "Resultado final"), 0);
            }
            return new FakeProcess(okStream(
                    "Informe " + workdir.getFileName(), "Informe " + workdir.getFileName()), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "Revisa el cambio");

        // Order: two workers, then the verifier, then the merge.
        assertThat(spawned).hasSize(4);
        // The verifier runs read-only — judging is its whole job.
        assertThat(spawned.get(2)).containsSequence("--disallowedTools",
                "Task,Bash,Write,Edit,NotebookEdit,Skill");
        // Its only MCP server is the verdict endpoint, under the verifier's OWN token.
        String verifierMcp = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "verifier", "mcp-config.json")));
        assertThat(verifierMcp).contains("/api/runs/run-1/verdict")
                .contains(run.verdictToken).doesNotContain("/plan");
        String verifierMd = Files.readString(
                dataDir.resolve(Path.of("local", "run-1", "verifier", "CLAUDE.md")));
        assertThat(verifierMd).contains("REJECT").contains("Reject unverified numbers.");

        // The kill has teeth: the merge sees A's report, and of B only the rejection.
        String mergePrompt = String.join(" ", spawned.get(3));
        assertThat(mergePrompt).contains("Informe worker-a")
                .doesNotContain("Informe worker-b");
        assertThat(mergePrompt).contains("rejected by the verifier: invented numbers");

        // Both verdicts marked on the boxes; status stays truthful (B DID finish its work).
        assertThat(exec(run, "n1").verdict).isEqualTo("accepted");
        assertThat(exec(run, "n2").verdict).isEqualTo("rejected");
        assertThat(exec(run, "n2").verdictReason).isEqualTo("invented numbers");
        assertThat(exec(run, "n2").status).isEqualTo("passed");
        assertThat(exec(run, "v1").status).isEqualTo("passed");
        assertThat(run.status).isEqualTo("IDLE");
        assertThat(run.finalOutput()).contains("Resultado final");

        // The graph metrics carry the kill rate and the fan-out shape.
        com.concentus.model.GraphMetrics m = run.graphMetrics();
        assertThat(m).isNotNull();
        assertThat(m.workers()).isEqualTo(2);
        assertThat(m.verdicts()).isEqualTo(2);
        assertThat(m.workersRejected()).isEqualTo(1);
        assertThat(m.workersFailed()).isZero();
        // Timings, asserted only where they are genuinely invariant.
        //
        // `sumWorkerMs >= wallMs` reads like an invariant and is not. Wall spans the FIRST
        // worker's start to the LAST one's end, so every gap between workers — pool ramp-up,
        // scheduling, the launch of the second process — falls inside wall while belonging to no
        // worker's own duration. It holds only while the work dominates those gaps, which is true
        // of a real run and false here: these workers are fakes that return instantly, so the gaps
        // ARE the window. It passed on Windows by luck and failed on a Linux runner with sum=2,
        // wall=3, which is the assertion being wrong rather than the metric.
        //
        // What the window really contains is each individual worker's run, and the sum really does
        // contain each worker's duration. Both hold no matter how the scheduler behaves.
        long longestWorker = Math.max(elapsed(exec(run, "n1")), elapsed(exec(run, "n2")));
        assertThat(m.wallMs()).isGreaterThanOrEqualTo(longestWorker);
        assertThat(m.sumWorkerMs()).isGreaterThanOrEqualTo(longestWorker);
    }

    /** How long one block actually ran, from its own record. */
    private static long elapsed(com.concentus.model.NodeExec node) {
        return node.endedAt - node.startedAt;
    }

    @Test
    void aVerifierThatNeverSubmitsAVerdictFailsTheRunAsUnverified() {
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "");
        AgentRun run = runWith(null, verifier, worker);

        FanoutExecutor.ProcessStarter starter = (args, workdir) ->
                new FakeProcess(okStream("todo bien supongo", "todo bien supongo"), 0);

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("without submitting a verdict").contains("UNVERIFIED");
        assertThat(exec(run, "v1").status).isEqualTo("failed");
        // The workers' work is not lost: the combined report still sits on the coordinator.
        assertThat(exec(run, "c1").output).contains("todo bien supongo");
        assertThat(exec(run, "n1").status).isEqualTo("passed");
    }

    @Test
    void whenTheVerifierRejectsEverythingTheRunFailsAndTheMergeNeverRuns() {
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "");
        AgentSpec merger = spec("m1", "Merge", "merge", "");
        AgentRun run = runWith(merger, verifier, worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            if (workdir.toString().endsWith("verifier")) {
                run.submittedVerdict = new com.concentus.model.WorkVerdict(null, List.of(
                        new com.concentus.model.WorkVerdict.Item("n1", "reject", "off-task")));
                return new FakeProcess(okStream("Nada sobrevive", "Nada sobrevive"), 0);
            }
            return new FakeProcess(okStream("Informe A", "Informe A"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(spawned).hasSize(2); // worker + verifier — the merge never spawned
        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("rejected every worker");
        assertThat(exec(run, "n1").verdict).isEqualTo("rejected");
    }

    // ------------------------------------------------------------------ cost router

    /** The model each spawned process was told to use, in spawn order. */
    private static List<String> modelsOf(List<List<String>> spawned) {
        return spawned.stream().map(args -> args.get(args.indexOf("--model") + 1)).toList();
    }

    @Test
    void aRejectedWorkerIsRetriedOnItsEscalationModelAndJudgedAgain() {
        // Cheap first, made safe: the saving is only real because something CHECKED the cheap
        // answer, and the stronger model is paid for exactly when that check refused.
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        worker.model.id = "claude-haiku-4-5-20251001";
        worker.fallbackModelId = "claude-opus-4-8";
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "");
        AgentSpec merger = spec("m1", "Merge", "merge", "");
        AgentRun run = runWith(merger, verifier, worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger verdicts = new java.util.concurrent.atomic.AtomicInteger();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            if (workdir.toString().endsWith("verifier")) {
                // Rejects the first output, accepts what the stronger model produced.
                boolean first = verdicts.getAndIncrement() == 0;
                run.submittedVerdict = new com.concentus.model.WorkVerdict(null, List.of(
                        new com.concentus.model.WorkVerdict.Item("n1", first ? "reject" : "accept",
                                first ? "invented numbers" : null)));
                return new FakeProcess(okStream("Veredicto", "Veredicto"), 0);
            }
            if (workdir.toString().endsWith("merge")) {
                return new FakeProcess(okStream("Resultado final", "Resultado final"), 0);
            }
            return new FakeProcess(okStream("Informe", "Informe"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        // worker (cheap) → verifier → worker (escalated) → verifier → merge.
        assertThat(spawned).hasSize(5);
        assertThat(modelsOf(spawned).get(0)).contains("haiku");
        assertThat(modelsOf(spawned).get(2)).contains("opus");
        // Judged again: an escalated output reaching the merge unverified would break the one
        // invariant the verifier exists to hold.
        assertThat(verdicts.get()).isEqualTo(2);
        assertThat(exec(run, "n1").verdict).isEqualTo("accepted");
        assertThat(exec(run, "n1").retries).isEqualTo(1);
        assertThat(exec(run, "n1").verdictReason).contains("Retried on claude-opus-4-8");
        assertThat(run.status).isEqualTo("IDLE");
    }

    @Test
    void anEscalatedOutputTheVerifierRejectsAgainStaysRejected() {
        // One escalation, not a loop: a second rejection is an answer, not an invitation to keep
        // spending on bigger models.
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        worker.fallbackModelId = "claude-opus-4-8";
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "");
        AgentRun run = runWith(null, verifier, worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            if (workdir.toString().endsWith("verifier")) {
                run.submittedVerdict = new com.concentus.model.WorkVerdict(null, List.of(
                        new com.concentus.model.WorkVerdict.Item("n1", "reject", "still wrong")));
                return new FakeProcess(okStream("Veredicto", "Veredicto"), 0);
            }
            return new FakeProcess(okStream("Informe", "Informe"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(spawned).hasSize(4); // worker, verifier, escalated worker, verifier
        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("rejected every worker");
        assertThat(exec(run, "n1").retries).isEqualTo(1);
    }

    @Test
    void aWorkerWithoutAnEscalationModelIsNeverRetried() {
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        AgentSpec verifier = spec("v1", "Verifier", "verifier", "");
        AgentRun run = runWith(null, verifier, worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            spawned.add(args);
            if (workdir.toString().endsWith("verifier")) {
                run.submittedVerdict = new com.concentus.model.WorkVerdict(null, List.of(
                        new com.concentus.model.WorkVerdict.Item("n1", "reject", "off-task")));
                return new FakeProcess(okStream("Veredicto", "Veredicto"), 0);
            }
            return new FakeProcess(okStream("Informe", "Informe"), 0);
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(spawned).hasSize(2); // worker + verifier, nothing more
        assertThat(exec(run, "n1").retries).isZero();
    }

    @Test
    void withNoVerifierThereIsNothingToEscalateOn() {
        // A worker nobody judged has no signal saying its answer was wrong. Re-running it "in
        // case" would spend more, not less — which is the opposite of this feature.
        AgentSpec worker = spec("n1", "Worker A", "worker-a", "");
        worker.model.id = "claude-haiku-4-5-20251001";
        worker.fallbackModelId = "claude-opus-4-8";
        AgentRun run = run(worker);

        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        executor((args, workdir) -> {
            spawned.add(args);
            return new FakeProcess(okStream("Informe", "Informe"), 0);
        }, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(spawned).hasSize(1);
        assertThat(modelsOf(spawned).get(0)).contains("haiku");
        assertThat(exec(run, "n1").retries).isZero();
    }

    @Test
    void graphMetricsExistOnlyForRunsThatFannedOut() {
        // A single-session flow has no graph to measure — the report must carry nothing, not
        // a strip of zeros.
        AgentRun plain = new AgentRun("run-2", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.nodeId = "c1";
        AgentSpec sub = new AgentSpec();
        sub.nodeId = "s1";
        plain.compiled = new CompiledFlow(coord, List.of(sub));
        plain.nodeExec("c1", "agent", "Coord");
        plain.nodeExec("s1", "agent", "Sub");
        assertThat(plain.graphMetrics()).isNull();

        // The same shape under fanout execution measures its workers.
        AgentRun fanned = run(spec("n1", "W", "w", ""));
        executor((args, dir) -> new FakeProcess(okStream("hola", "Informe"), 0), 900, 0)
                .runTurn(fanned, fanned.compiled, "go");
        com.concentus.model.GraphMetrics m = fanned.graphMetrics();
        assertThat(m).isNotNull();
        assertThat(m.workers()).isEqualTo(1);
        assertThat(m.verdicts()).isZero(); // no verifier ran, and the metrics say so
    }
}
