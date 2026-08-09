package com.concentus.service;

import com.concentus.config.AgentSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the {@code claude} command line.
 *
 * <p>Worth pinning because both failure modes are silent-ish. A prompt passed as an argument blows
 * the platform's command-line limit once an email is involved — Windows reports only
 * {@code CreateProcess error=206}, naming nothing. And {@code -p} takes an <em>optional</em> value,
 * so a {@code -p} placed before another flag can swallow that flag as the prompt, producing a run
 * that starts cleanly and does the wrong thing.
 */
class LocalClaudeArgsTest {

    private static LocalClaudeExecutor executor() {
        return new LocalClaudeExecutor(null, null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null,
                "bypassPermissions", "data", true, true);
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        run.compiled = new CompiledFlow(coord, List.of());
        run.localSessionId = "session-1";
        return run;
    }

    private static List<String> args(String prompt, boolean promptOnStdin) {
        return executor().buildArgs("claude", run(), Path.of("."), true, prompt, List.of(), promptOnStdin);
    }

    @Test
    void aShortPromptIsPassedAsAnArgument() {
        List<String> args = args("hello", false);

        assertThat(args).containsSequence("-p", "hello");
    }

    @Test
    void aPipedPromptIsNotOnTheCommandLineAtAll() {
        // The whole point: the text must not reach argv, or the limit is hit regardless.
        List<String> args = args("x".repeat(50_000), true);

        assertThat(args).noneSatisfy(a -> assertThat(a).hasSizeGreaterThan(1000));
    }

    @Test
    void aPipedPromptPutsTheBareFlagLast() {
        // -p takes an optional value, so anything after it could be read as the prompt. Nothing
        // after it means the CLI can only read stdin.
        List<String> args = args("x".repeat(50_000), true);

        assertThat(args.getLast()).isEqualTo("-p");
        assertThat(args).filteredOn("-p"::equals).hasSize(1);
    }

    @Test
    void thePipedFormStillCarriesTheRestOfTheInvocation() {
        List<String> args = args("x".repeat(50_000), true);

        assertThat(args).containsSequence("--output-format", "stream-json")
                .containsSequence("--permission-mode", "bypassPermissions")
                .containsSequence("--session-id", "session-1");
    }

    @Test
    void aResumedTurnResumesRatherThanStartingANewSession() {
        List<String> args = executor().buildArgs("claude", run(), Path.of("."), false, "hi", List.of(), false);

        assertThat(args).containsSequence("--resume", "session-1")
                .doesNotContain("--session-id");
    }

    @Test
    void contextDirectoriesAreGrantedIndividually() {
        List<String> args = executor().buildArgs("claude", run(), Path.of("."), true, "hi",
                List.of(Path.of("/a"), Path.of("/b")), false);

        assertThat(args).filteredOn("--add-dir"::equals).hasSize(2);
    }

    @org.junit.jupiter.api.Test
    void runsAreConfinedToTheFlowsMcpServers() {
        List<String> a = args("hola", false);

        // --strict-mcp-config junto a un --mcp-config del propio run: sin el primero, el CLI
        // sumaría la lista personal del usuario a la del flujo, que es la exposición que se cierra.
        int cfg = a.indexOf("--mcp-config");
        org.assertj.core.api.Assertions.assertThat(cfg).isGreaterThanOrEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(a.get(cfg + 1)).endsWith("mcp-config.json");
        org.assertj.core.api.Assertions.assertThat(a).contains("--strict-mcp-config");
    }

    @org.junit.jupiter.api.Test
    void approvalModeRunsThePlanningTurnWithNoPermissionToAct() {
        AgentRun run = run();
        run.permissionMode = LocalClaudeExecutor.APPROVAL_MODE;

        List<String> a = executor().buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false);

        // 'approval' is ours, not the CLI's — passing it through verbatim would be rejected as an
        // unknown mode, and passing bypassPermissions would let the agent act before anyone agreed.
        int i = a.indexOf("--permission-mode");
        org.assertj.core.api.Assertions.assertThat(a.get(i + 1)).isEqualTo("plan");
    }

    @org.junit.jupiter.api.Test
    void onceApprovedTheSameRunIsAllowedToAct() {
        AgentRun run = run();
        run.permissionMode = LocalClaudeExecutor.APPROVAL_MODE;
        run.approved = true;

        List<String> a = executor().buildArgs("claude", run, Path.of("."), false, "go", List.of(), false);

        int i = a.indexOf("--permission-mode");
        org.assertj.core.api.Assertions.assertThat(a.get(i + 1)).isEqualTo("bypassPermissions");
    }

    @org.junit.jupiter.api.Test
    void awaitingApprovalIsTrueOnlyBeforeSomeoneApproves() {
        AgentRun run = run();
        run.permissionMode = LocalClaudeExecutor.APPROVAL_MODE;
        org.assertj.core.api.Assertions.assertThat(LocalClaudeExecutor.awaitingApproval(run)).isTrue();
        run.approved = true;
        org.assertj.core.api.Assertions.assertThat(LocalClaudeExecutor.awaitingApproval(run)).isFalse();

        AgentRun normal = run();
        normal.permissionMode = "bypassPermissions";
        org.assertj.core.api.Assertions.assertThat(LocalClaudeExecutor.awaitingApproval(normal)).isFalse();
    }
}
