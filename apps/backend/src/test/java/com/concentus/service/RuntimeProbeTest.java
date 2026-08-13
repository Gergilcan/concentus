package com.concentus.service;

import com.concentus.model.RuntimeCheck;
import com.concentus.model.RuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuntimeProbe}: the command -> runtime heuristic, the caching that keeps a
 * panel's renders from spawning processes, and what an absent runtime reports. The host probe is
 * replaced by a function, so nothing here launches a process.
 */
class RuntimeProbeTest {

    /** A machine where the given commands answer --version and nothing else does. */
    private static RuntimeProbe machineWith(Map<String, String> versions) {
        return new RuntimeProbe(versions::get);
    }

    // ---------------------------------------------------------------- launcher heuristic

    @Test
    void readsTheLauncherFromTheFirstWordOfTheCommand() {
        assertThat(RuntimeProbe.launcherOf("npx -y mcp-server-gsc")).isEqualTo("npx");
        assertThat(RuntimeProbe.launcherOf("uvx mailchimp-mcp")).isEqualTo("uvx");
    }

    @Test
    void normalisesAPastedAbsolutePathAndItsWindowsSuffix() {
        // A README's snippet and a Windows shim must resolve to the same launcher as the bare name,
        // or the person who pasted the full path gets told nothing is wrong when something is.
        assertThat(RuntimeProbe.launcherOf("\"C:\\Program Files\\nodejs\\npx.CMD\" -y thing")).isEqualTo("npx");
        assertThat(RuntimeProbe.launcherOf("C:\\tools\\nodejs\\npx.cmd -y thing")).isEqualTo("npx");
        assertThat(RuntimeProbe.launcherOf("/usr/local/bin/python3 -m server")).isEqualTo("python3");
    }

    @Test
    void anUnquotedPathWithSpacesFallsBackToUnmanagedRatherThanGuessing() {
        // Genuinely ambiguous — "C:\Program Files\nodejs\npx.cmd -y x" cannot be told apart from a
        // command with arguments. Reading the first word yields an unknown launcher, so nothing is
        // claimed about a server that may well run.
        RuntimeCheck check = machineWith(Map.of()).check("C:\\Program Files\\nodejs\\npx.cmd -y x", false);

        assertThat(check.satisfied()).isTrue();
        assertThat(check.runtime()).isNull();
    }

    // ---------------------------------------------------------------- check()

    @Test
    void aCommandWhoseRuntimeIsInstalledIsSatisfied() {
        RuntimeProbe probe = machineWith(Map.of("npm", "10.8.2"));

        RuntimeCheck check = probe.check("npx -y mcp-server-gsc", false);

        assertThat(check.satisfied()).isTrue();
        assertThat(check.runtime().id()).isEqualTo("npm");
        assertThat(check.runtime().version()).isEqualTo("10.8.2");
    }

    @Test
    void aCommandWhoseRuntimeIsMissingIsNotSatisfiedAndNamesWhatIsMissing() {
        RuntimeProbe probe = machineWith(Map.of());

        RuntimeCheck check = probe.check("uvx mailchimp-mcp", false);

        assertThat(check.satisfied()).isFalse();
        assertThat(check.runtime().id()).isEqualTo("uv");
        assertThat(check.runtime().found()).isFalse();
        assertThat(check.runtime().version()).isEmpty();
    }

    @Test
    void anUnknownLauncherIsUnmanagedRatherThanMissing() {
        // A binary the user put on their PATH themselves. This app cannot install it and has no
        // reason to think it is absent — saying "missing" would condemn a server that runs fine.
        RuntimeProbe probe = machineWith(Map.of());

        RuntimeCheck check = probe.check("my-own-mcp-server --stdio", false);

        assertThat(check.satisfied()).isTrue();
        assertThat(check.runtime()).isNull();
    }

    @Test
    void anEmptyCommandNeedsNothing() {
        // A remote MCP server launches no process; there is nothing to have installed.
        assertThat(machineWith(Map.of()).check("", false).satisfied()).isTrue();
        assertThat(machineWith(Map.of()).check(null, false).satisfied()).isTrue();
    }

    // ---------------------------------------------------------------- all() + caching

    @Test
    void listsEveryKnownRuntimeWithItsInstalledState() {
        RuntimeProbe probe = machineWith(Map.of("node", "v22.11.0", "npm", "10.8.2"));

        List<RuntimeStatus> all = probe.all(false);

        assertThat(all).extracting(RuntimeStatus::id)
                .containsExactly("node", "npm", "pnpm", "python", "pipx", "uv");
        assertThat(all).filteredOn(RuntimeStatus::found).extracting(RuntimeStatus::id)
                .containsExactly("node", "npm");
        // Every entry says what needs it, so the UI never shows a bare tool name with no reason.
        assertThat(all).allSatisfy(s -> assertThat(s.neededFor()).isNotBlank());
    }

    @Test
    void repeatedChecksReuseTheCachedProbeInsteadOfSpawningAgain() {
        AtomicInteger probes = new AtomicInteger();
        RuntimeProbe probe = new RuntimeProbe(command -> {
            probes.incrementAndGet();
            return "10.8.2";
        });

        probe.check("npx a", false);
        probe.check("npm run b", false);

        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void refreshBypassesTheCacheSoAJustFinishedInstallIsSeen() {
        AtomicInteger probes = new AtomicInteger();
        RuntimeProbe probe = new RuntimeProbe(command -> probes.incrementAndGet() > 1 ? "10.8.2" : null);

        assertThat(probe.check("npx a", false).satisfied()).isFalse();
        assertThat(probe.check("npx a", true).satisfied()).isTrue();
        assertThat(probes.get()).isEqualTo(2);
    }
}
