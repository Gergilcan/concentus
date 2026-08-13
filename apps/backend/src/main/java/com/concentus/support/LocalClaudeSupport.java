package com.concentus.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the local {@code claude} CLI (Claude Code) and reports whether it can be used
 * to run agents on the user's Claude subscription login.
 */
@Component
public class LocalClaudeSupport {

    private final String configured;

    public LocalClaudeSupport(@Value("${local.claude-command:}") String configured) {
        this.configured = configured;
    }

    /** The command to invoke, if resolvable. */
    public Optional<String> command() {
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured);
        }
        String home = System.getProperty("user.home", "");
        // Both spellings of the installer's own binary, .exe first for Windows. Probed before the
        // PATH fallback below, so a real install outranks whatever `claude` resolves to.
        for (String name : new String[] {"claude.exe", "claude"}) {
            Path installed = Path.of(home, ".local", "bin", name);
            if (Files.isRegularFile(installed)) {
                return Optional.of(installed.toString());
            }
        }
        // Fall back to PATH resolution.
        return Optional.of("claude");
    }

    /**
     * True when there is a Claude Code login on disk.
     *
     * <p>Only the login is checked: {@link #command()} always yields something (bare
     * {@code "claude"}, for PATH to resolve), so asking whether a command exists could never
     * answer no. Whether that command actually runs is settled by running it.
     */
    public boolean available() {
        String home = System.getProperty("user.home", "");
        return Files.exists(Path.of(home, ".claude.json")) || Files.exists(Path.of(home, ".claude"));
    }
}
