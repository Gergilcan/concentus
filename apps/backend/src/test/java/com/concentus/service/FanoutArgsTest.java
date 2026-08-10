package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of a worker's {@code claude} command line.
 *
 * <p>Pinned for the same reasons as the coordinator's ({@link LocalClaudeArgsTest}) plus the two
 * things a worker must never get: the Task tool (workers must not spawn workers) and a wider
 * permission mode than the flow's own.
 */
class FanoutArgsTest {

    private static FanoutExecutor executor() {
        return new FanoutExecutor(new LocalClaudeSupport("claude"),
                new RagContextInjector(null, null), new ContextFolderResolver(""),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "data", "bypassPermissions", 2, 900, 1, (args, dir) -> {
                    throw new UnsupportedOperationException("arg tests never spawn");
                });
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        AgentSpec worker = spec();
        run.compiled = new CompiledFlow(coord, List.of(worker));
        return run;
    }

    private static AgentSpec spec() {
        AgentSpec s = new AgentSpec();
        s.name = "Worker A";
        s.cliName = "worker-a";
        s.nodeId = "n1";
        s.model.id = "claude-sonnet-5";
        return s;
    }

    private static List<String> args(String prompt, boolean promptOnStdin) {
        return executor().buildWorkerArgs("claude", run(), spec(), Path.of("wd"), List.of(),
                "session-9", prompt, promptOnStdin);
    }

    @Test
    void aWorkerCanNeverDelegate() {
        assertThat(args("hola", false)).containsSequence("--disallowedTools", "Task");
    }

    @Test
    void aWorkerRunsItsOwnModelNotTheCoordinators() {
        assertThat(args("hola", false)).containsSequence("--model", "sonnet");
    }

    @Test
    void aWorkerIsConfinedToItsOwnMcpConfig() {
        List<String> a = args("hola", false);

        int cfg = a.indexOf("--mcp-config");
        assertThat(cfg).isGreaterThanOrEqualTo(0);
        assertThat(a.get(cfg + 1)).endsWith("mcp-config.json").contains("wd");
        assertThat(a).contains("--strict-mcp-config");
    }

    @Test
    void aLargePromptGoesInOnStdinWithTheBareFlagLast() {
        List<String> a = args("x".repeat(50_000), true);

        assertThat(a.getLast()).isEqualTo("-p");
        assertThat(a).filteredOn("-p"::equals).hasSize(1);
        assertThat(a).noneSatisfy(s -> assertThat(s).hasSizeGreaterThan(1000));
    }

    @Test
    void anApprovalFlowsWorkersPlanRatherThanAct() {
        AgentRun run = run();
        run.permissionMode = LocalClaudeExecutor.APPROVAL_MODE;

        List<String> a = executor().buildWorkerArgs("claude", run, spec(), Path.of("wd"),
                List.of(), "s", "hola", false);

        assertThat(a).containsSequence("--permission-mode", "plan");
    }

    @Test
    void eachWorkerGetsItsOwnFreshSession() {
        assertThat(args("hola", false)).containsSequence("--session-id", "session-9")
                .doesNotContain("--resume");
    }

    @Test
    void contextDirectoriesAreGrantedPerWorker() {
        List<String> a = executor().buildWorkerArgs("claude", run(), spec(), Path.of("wd"),
                List.of(Path.of("/a"), Path.of("/b")), "s", "hola", false);

        assertThat(a).filteredOn("--add-dir"::equals).hasSize(2);
    }
}
