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
        return executor(null);
    }

    private static LocalClaudeExecutor executor(PluginRegistry plugins) {
        return new LocalClaudeExecutor(null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null, null, null,
                plugins, com.concentus.config.Settings.none(),
                "bypassPermissions", "data", true, true);
    }

    /** A registry with a fixed installed list — the CLI is never shelled from a unit test. */
    private static PluginRegistry fixedPlugins() {
        return new PluginRegistry(new com.concentus.support.LocalClaudeSupport("claude"),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public java.util.List<PluginInfo> list() {
                return java.util.List.of(
                        new PluginInfo("caveman@caveman", "1", "user", true),
                        new PluginInfo("other@mkt", "1", "user", true));
            }
        };
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun("run-1", "flow-1", "Flow");
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        run.compiled = new CompiledFlow(coord, List.of());
        run.localSessionId = "session-1";
        return run;
    }

    private static List<String> args(String prompt, boolean promptOnStdin) {
        return executor().buildArgs("claude", run(), Path.of("."), true, prompt, List.of(),
                promptOnStdin, null);
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
        List<String> args = executor().buildArgs("claude", run(), Path.of("."), false, "hi", List.of(), false, null);

        assertThat(args).containsSequence("--resume", "session-1")
                .doesNotContain("--session-id");
    }

    @Test
    void contextDirectoriesAreGrantedIndividually() {
        List<String> args = executor().buildArgs("claude", run(), Path.of("."), true, "hi",
                List.of("/a", "/b"), false, null);

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

        List<String> a = executor().buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false, null);

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

        List<String> a = executor().buildArgs("claude", run, Path.of("."), false, "go", List.of(), false, null);

        int i = a.indexOf("--permission-mode");
        org.assertj.core.api.Assertions.assertThat(a.get(i + 1)).isEqualTo("bypassPermissions");
    }

    @Test
    void theSettingsFlagCarriesAPathAndNeverTheJsonItself() {
        Path settings = Path.of("wd", "settings.json");

        List<String> a = executor(fixedPlugins())
                .buildArgs("claude", run(), Path.of("."), true, "hola", List.of(), false, settings);

        int i = a.indexOf("--settings");
        assertThat(i).isGreaterThanOrEqualTo(0);
        assertThat(a.get(i + 1)).isEqualTo(settings.toString());
        // The regression this replaced: a JSON argument does not survive ProcessBuilder on
        // Windows. The CLI answered "Invalid JSON provided to --settings" and exited 1 before the
        // run began — every turn, once an empty selection started producing settings too.
        assertThat(a).noneSatisfy(arg -> assertThat(arg).contains("enabledPlugins"));
    }

    @Test
    void withNoSettingsFileThereIsNoFlag() {
        List<String> a = executor(fixedPlugins())
                .buildArgs("claude", run(), Path.of("."), true, "hola", List.of(), false, null);

        assertThat(a).doesNotContain("--settings");
    }

    @Test
    void aFlowThatAssignedNoSkillLosesTheSkillTool() {
        // The workspace copy only covers the assigned ones; the machine's personal skills are
        // found in the user's home, which the run shares. Taking the tool away is what makes
        // "none assigned" mean none.
        List<String> a = executor(null)
                .buildArgs("claude", run(), Path.of("."), true, "hola", List.of(), false, null);

        int i = a.indexOf("--disallowedTools");
        assertThat(i).isGreaterThanOrEqualTo(0);
        assertThat(a.get(i + 1)).isEqualTo("Skill");
    }

    @Test
    void oneAssignedSkillAnywhereKeepsTheToolForTheSession() {
        // Coordinator and sub-agents share one CLI process, so this is necessarily flow-wide.
        AgentRun run = run();
        AgentSpec.SkillSpec skill = new AgentSpec.SkillSpec();
        skill.type = "custom";
        skill.id = "skill_1";
        run.compiled.coordinator().skills = List.of(skill);

        List<String> a = executor(null)
                .buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false, null);

        assertThat(a).doesNotContain("--disallowedTools");
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

    // ---- the organization's permission ceiling (organization policy) ----

    @org.junit.jupiter.api.Test
    void theCeilingClampsTheDeploymentDefaultAndSaysSoOnce() {
        AgentRun run = run();
        run.maxPermissionMode = "acceptEdits";

        List<String> first = executor().buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false, null);
        List<String> second = executor().buildArgs("claude", run, Path.of("."), false, "sigue", List.of(), false, null);

        // The deployment says bypassPermissions; the organization says no higher than acceptEdits.
        org.assertj.core.api.Assertions.assertThat(first.get(first.indexOf("--permission-mode") + 1))
                .isEqualTo("acceptEdits");
        org.assertj.core.api.Assertions.assertThat(second.get(second.indexOf("--permission-mode") + 1))
                .isEqualTo("acceptEdits");
        // Said once, not once per turn.
        org.assertj.core.api.Assertions.assertThat(run.bufferedEvents())
                .filteredOn(e -> e.text().contains("above the organization's ceiling 'acceptEdits'"))
                .hasSize(1);
    }

    @org.junit.jupiter.api.Test
    void aModeUnderTheCeilingIsLeftAloneAndNothingIsSaid() {
        AgentRun run = run();
        run.permissionMode = "plan";
        run.maxPermissionMode = "acceptEdits";

        List<String> a = executor().buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false, null);

        org.assertj.core.api.Assertions.assertThat(a.get(a.indexOf("--permission-mode") + 1)).isEqualTo("plan");
        org.assertj.core.api.Assertions.assertThat(run.bufferedEvents())
                .noneMatch(e -> e.text().contains("ceiling"));
    }

    @org.junit.jupiter.api.Test
    void anApprovedRunActsNoHigherThanTheCeilingEither() {
        AgentRun run = run();
        run.permissionMode = LocalClaudeExecutor.APPROVAL_MODE;
        run.approved = true;
        run.maxPermissionMode = "default";

        List<String> a = executor().buildArgs("claude", run, Path.of("."), false, "go", List.of(), false, null);

        // Approval used to mean bypassPermissions; under a ceiling it means "as much as allowed".
        org.assertj.core.api.Assertions.assertThat(a.get(a.indexOf("--permission-mode") + 1)).isEqualTo("default");
    }

    @org.junit.jupiter.api.Test
    void noCeilingMeansExactlyWhatItMeantBefore() {
        AgentRun run = run();
        run.maxPermissionMode = "";

        List<String> a = executor().buildArgs("claude", run, Path.of("."), true, "hola", List.of(), false, null);

        org.assertj.core.api.Assertions.assertThat(a.get(a.indexOf("--permission-mode") + 1))
                .isEqualTo("bypassPermissions");
    }
}
