package com.concentus.service;

import com.concentus.model.RuntimeInstallPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuntimeInstaller}: that the command shown is the command run, that a
 * platform with no safe installer says so instead of inventing one, and that a failure comes back
 * with the installer's own words. Nothing here launches a process.
 */
class RuntimeInstallerTest {

    private final List<List<String>> ran = new ArrayList<>();

    private RuntimeInstaller installer(int exit, String output) {
        return new RuntimeInstaller((argv, timeout) -> {
            ran.add(argv);
            return new CliProcess.Result(exit, output);
        });
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    // ---------------------------------------------------------------- the plan

    @Test
    void everyManagedRuntimeHasAPlanThatEitherRunsSomethingOrSaysWhyNot() {
        RuntimeInstaller installer = installer(0, "");

        for (String id : List.of("node", "npm", "pnpm", "python", "pipx", "uv", "tesseract")) {
            RuntimeInstallPlan plan = installer.plan(id);
            assertThat(plan.runtime()).isEqualTo(id);
            // Exactly one of the two: a command to run, or a reason there is none. Never both
            // empty, which would leave the UI with a button that does nothing and no explanation.
            assertThat(plan.command() != null || plan.reason() != null).as(id).isTrue();
        }
    }

    @Test
    void uvInstallsFromItsOfficialScriptOnEveryPlatform() {
        RuntimeInstallPlan plan = installer(0, "").plan("uv");

        assertThat(plan.command()).contains("astral.sh/uv/install");
        assertThat(plan.reason()).isNull();
    }

    @Test
    void aRuntimeThisAppDoesNotManageIsRefusedRatherThanGuessedAt() {
        RuntimeInstallPlan plan = installer(0, "").plan("rustup");

        assertThat(plan.command()).isNull();
        assertThat(plan.reason()).contains("does not manage");
    }

    // ---------------------------------------------------------------- installing

    @Test
    void runsExactlyTheCommandItShowed() {
        // The command in the plan is what the UI puts in front of the user. Running a different
        // one would make that display a lie.
        RuntimeInstaller installer = installer(0, "installed");
        RuntimeInstallPlan plan = installer.plan("uv");

        RuntimeInstaller.RuntimeInstallResult result = installer.install("uv");

        assertThat(result.ok()).isTrue();
        assertThat(result.command()).isEqualTo(plan.command());
        assertThat(ran).hasSize(1);
        assertThat(ran.get(0)).contains(plan.command());
        // Through a shell, because these are published one-liners with pipes in them.
        assertThat(ran.get(0).get(0)).isEqualTo(windows() ? "powershell.exe" : "/bin/bash");
    }

    @Test
    void aFailedInstallComesBackWithTheInstallersOwnWords() {
        // "exit 1" tells nobody anything; "winget is not recognized" tells them what to do.
        RuntimeInstaller installer = installer(1, "'winget' is not recognized");

        RuntimeInstaller.RuntimeInstallResult result = installer.install("uv");

        assertThat(result.ok()).isFalse();
        assertThat(result.output()).contains("winget");
    }

    @Test
    void nothingIsRunForARuntimeWithNoInstallerHere() {
        RuntimeInstaller installer = installer(0, "");

        RuntimeInstaller.RuntimeInstallResult result = installer.install("rustup");

        assertThat(result.ok()).isFalse();
        assertThat(result.output()).contains("does not manage");
        assertThat(ran).isEmpty();
    }

    @Test
    void aTorrentOfOutputIsTrimmedToItsEnd() {
        // The tail is where an installer says what went wrong; the head is its banner.
        RuntimeInstaller installer = installer(0, "x".repeat(9000) + "DONE");

        String output = installer.install("uv").output();

        assertThat(output).hasSizeLessThan(4200).endsWith("DONE").startsWith("…");
    }
}
