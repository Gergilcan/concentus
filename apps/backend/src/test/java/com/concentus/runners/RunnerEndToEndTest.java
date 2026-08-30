package com.concentus.runners;

import com.concentus.git.GitWorkspace;
import com.concentus.git.RepoExpander;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.runners.agent.AgentRuntime;
import com.concentus.runners.agent.RunnerAgent;
import com.concentus.service.AgentRun;
import com.concentus.service.ContextFolderResolver;
import com.concentus.service.ProcessCeiling;
import com.concentus.service.RunService;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole thing, once: a real hub with its database, a real agent in the same JVM connected
 * over the real socket with a fake {@code claude} that speaks stream-json, and a flow set to that
 * runner. The run completes with the fake's answer in its events and the runner named on it — and
 * a second agent presenting a revoked token is turned away at the door.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class RunnerEndToEndTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
        registry.add("app.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        // Nothing this test does needs a mail poll, a folder watch or the health probe's database check.
        registry.add("mail.triggers-enabled", () -> "false");
        registry.add("watch.triggers-enabled", () -> "false");
        registry.add("management.health.db.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    RunnerStore store;
    @Autowired
    RunnerRegistry registry;
    @Autowired
    RunService runs;

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** A claude that answers --version and otherwise says {@code answer} in stream-json. */
    private static Path fakeClaude(Path dir, String answer) throws Exception {
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"fake\",\"tools\":[]}";
        String message = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + answer
                + "\"}],\"usage\":{\"input_tokens\":3,\"output_tokens\":5}}}";
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"" + answer
                + "\",\"usage\":{\"input_tokens\":3,\"output_tokens\":5}}";
        Files.createDirectories(dir);
        if (windows()) {
            Path script = dir.resolve("fake-claude.cmd");
            Files.writeString(script, "@echo off\r\n"
                    + "if \"%~1\"==\"--version\" (\r\n  echo fake-claude 1.0.0\r\n  exit /b 0\r\n)\r\n"
                    + "echo " + init + "\r\necho " + message + "\r\necho " + result + "\r\n", StandardCharsets.US_ASCII);
            return script;
        }
        Path script = dir.resolve("fake-claude.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'fake-claude 1.0.0'; exit 0; fi\n"
                + "printf '%s\\n' '" + init + "'\nprintf '%s\\n' '" + message + "'\nprintf '%s\\n' '" + result + "'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private RunnerAgent agent(String token, Path home, Path claude) {
        AgentRuntime runtime = new AgentRuntime(home, new LocalClaudeSupport(claude.toString()),
                new ContextFolderResolver(""), new GitWorkspace(RepoExpander.standalone(), true, 60, 0),
                ProcessCeiling.unlimited());
        return new RunnerAgent(new RunnerAgent.Config("http://127.0.0.1:" + port, token, "test box", "test"), runtime);
    }

    private static FlowGraph flowOn(String runnerId) {
        return new FlowGraph("flow_remote", "Remote hello",
                List.of(new FlowNode("c1", "agent", "coordinator", Map.of("name", "Coord")),
                        new FlowNode("in1", "input", null, Map.of("mode", "manual"))),
                List.of(), null, List.of(), null, null, null, null, null, null, null, null, null, null, runnerId);
    }

    @Test
    void a_flow_set_to_a_runner_executes_there_and_the_run_says_so() throws Exception {
        String token = RunnerTokens.mint();
        Runner runner = store.create("default", "Test box", Runner.SCOPE_ORGANIZATION, null, null,
                RunnerTokens.hash(token), "test");
        Path claude = fakeClaude(dataDir, "Hello from the runner");
        RunnerAgent agent = agent(token, dataDir.resolve("agent-home"), claude);
        try {
            agent.start();
            agent.welcomed().get(30, TimeUnit.SECONDS);
            assertThat(registry.online(runner.id())).isTrue();
            RunnerRegistry.Live live = registry.live(runner.id());
            assertThat(live.claudeVersion()).isEqualTo("fake-claude 1.0.0");
            assertThat(live.name()).isEqualTo("test box");

            RunSummary started = runs.start(flowOn(runner.id()), "say hello");
            AgentRun run = runs.get(started.id()).orElseThrow();
            long deadline = System.currentTimeMillis() + 60_000;
            while (!"COMPLETED".equals(run.status) && !"ERROR".equals(run.status) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            List<String> log = run.bufferedEvents().stream().map(e -> e.type() + ": " + e.text()).toList();
            assertThat(run.status).as(String.join("\n", log)).isEqualTo("COMPLETED");
            assertThat(run.runnerId).isEqualTo(runner.id());
            assertThat(run.runnerName).isEqualTo("Test box");
            assertThat(run.bufferedEvents()).filteredOn(e -> "agent_message".equals(e.type()))
                    .extracting(RunEvent::text).contains("Hello from the runner");
            assertThat(log).anyMatch(l -> l.contains("Runner 'Test box' — running on its Claude login"));
            assertThat(run.totalOutputTokens).isEqualTo(5);
            // The workspace was made on the runner's side, not under this backend's mirror alone.
            assertThat(Files.exists(dataDir.resolve("agent-home").resolve("runs").resolve(run.id).resolve("CLAUDE.md")
                    .getParent())).isTrue();
            assertThat(run.toSummary().runnerName()).isEqualTo("Test box");
        } finally {
            agent.stop();
        }
    }

    @Test
    void a_revoked_token_is_refused_at_the_handshake_and_the_agent_stops_for_good() throws Exception {
        String token = RunnerTokens.mint();
        Runner runner = store.create("default", "Old box", Runner.SCOPE_ORGANIZATION, null, null,
                RunnerTokens.hash(token), "test");
        store.revoke("default", runner.id(), System.currentTimeMillis());
        RunnerAgent agent = agent(token, dataDir.resolve("agent-home-2"), fakeClaude(dataDir.resolve("two"), "x"));
        try {
            agent.start();
            assertThatThrownBy(() -> agent.welcomed().get(30, TimeUnit.SECONDS)).hasMessageContaining("revoked");
            assertThat(agent.isFatal()).isTrue();
            assertThat(registry.online(runner.id())).isFalse();

            RunnerAgent unknown = agent(RunnerTokens.mint(), dataDir.resolve("agent-home-3"), fakeClaude(dataDir.resolve("three"), "x"));
            unknown.start();
            assertThatThrownBy(() -> unknown.welcomed().get(30, TimeUnit.SECONDS)).hasMessageContaining("does not know");
            unknown.stop();

            // And a flow that names it is refused before anything runs.
            assertThatThrownBy(() -> runs.start(flowOn(runner.id()), "go")).hasMessageContaining("was revoked");
        } finally {
            agent.stop();
        }
    }
}
