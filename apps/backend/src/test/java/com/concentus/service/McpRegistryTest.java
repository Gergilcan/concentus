package com.concentus.service;

import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpRegistry#add}, {@link McpRegistry#login} and {@link McpRegistry#remove}
 * — specifically their input-validation short-circuits, which return without ever spawning a
 * process. (Name-charset validation itself is covered by {@link McpRegistryNameTest}.)
 *
 * <p>These deliberately stop short of the "CLI resolves and args look fine" branch: that branch
 * spawns a real OS process (or, for {@code login}, a visible terminal window), which would be
 * non-deterministic and environment-dependent in a unit test.
 */
class McpRegistryTest {

    private static McpRegistry registryWithNoCli() {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.empty());
        return new McpRegistry(support);
    }

    private static McpRegistry registryWithCli() {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.of("claude-stub"));
        return new McpRegistry(support);
    }

    // ---------------------------------------------------------------- CLI not found

    @Test
    void listReturnsEmptyWhenClaudeCliIsNotFound() {
        assertThat(registryWithNoCli().list()).isEmpty();
    }

    @Test
    void addReportsCliNotFoundWithoutSpawningAProcess() {
        assertThat(registryWithNoCli().add("github", "https://example.com/mcp", null))
                .isEqualTo("claude CLI not found");
    }

    @Test
    void loginReportsCliNotFoundWithoutSpawningATerminal() {
        assertThat(registryWithNoCli().login("github")).isEqualTo("claude CLI not found");
    }

    @Test
    void removeReportsCliNotFoundWithoutSpawningAProcess() {
        assertThat(registryWithNoCli().remove("github")).isEqualTo("claude CLI not found");
    }

    // ---------------------------------------------------------------- add(): missing name/url

    @Test
    void addRejectsANullName() {
        assertThat(registryWithCli().add(null, "https://example.com/mcp", null)).isEqualTo("missing name/url");
    }

    @Test
    void addRejectsABlankName() {
        assertThat(registryWithCli().add("   ", "https://example.com/mcp", null)).isEqualTo("missing name/url");
    }

    @Test
    void addRejectsANullUrl() {
        assertThat(registryWithCli().add("github", null, null)).isEqualTo("missing name/url");
    }

    @Test
    void addRejectsABlankUrl() {
        assertThat(registryWithCli().add("github", "   ", null)).isEqualTo("missing name/url");
    }

    // ---------------------------------------------------------------- login(): missing/unsafe name

    @Test
    void loginRejectsANullName() {
        assertThat(registryWithCli().login(null)).isEqualTo("missing name");
    }

    @Test
    void loginRejectsABlankName() {
        assertThat(registryWithCli().login("   ")).isEqualTo("missing name");
    }

    @Test
    void loginRejectsAnUnsafeNameBeforeLaunchingAnyTerminal() {
        // Defence in depth: even though the controller is expected to reject this first, the
        // registry itself must never build a terminal/script command around an unsafe name.
        assertThat(registryWithCli().login("evil\" & calc & \"")).isEqualTo("invalid server name");
    }

    // ---------------------------------------------------------------- remove(): missing name

    @Test
    void removeRejectsANullName() {
        assertThat(registryWithCli().remove(null)).isEqualTo("missing name");
    }

    @Test
    void removeRejectsABlankName() {
        assertThat(registryWithCli().remove("   ")).isEqualTo("missing name");
    }
}
