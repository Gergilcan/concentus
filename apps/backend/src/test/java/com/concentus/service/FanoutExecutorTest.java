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

    private FanoutExecutor executor(FanoutExecutor.ProcessStarter starter, int timeoutSeconds,
                                    int retries) {
        return new FanoutExecutor(new LocalClaudeSupport("claude"),
                new RagContextInjector(null, null), new ContextFolderResolver(""),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                dataDir.toString(), "bypassPermissions", 4, timeoutSeconds, retries, starter);
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
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.nodeId = "c1";
        coord.name = "Coordinator";
        coord.execution = "fanout";
        run.compiled = new CompiledFlow(coord, List.of(workers));
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
    void aFanoutWithNoSubAgentsRefusesPlainly() {
        AgentRun run = run(); // coordinator only
        FanoutExecutor.ProcessStarter starter = (args, workdir) -> {
            throw new AssertionError("nothing should spawn");
        };

        executor(starter, 900, 0).runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("at least one sub-agent");
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
