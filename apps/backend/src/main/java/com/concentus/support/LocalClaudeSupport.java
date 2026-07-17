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
        Path installed = Path.of(home, ".local", "bin", "claude.exe");
        if (Files.isRegularFile(installed)) {
            return Optional.of(installed.toString());
        }
        Path installedNix = Path.of(home, ".local", "bin", "claude");
        if (Files.isRegularFile(installedNix)) {
            return Optional.of(installedNix.toString());
        }
        // Fall back to PATH resolution.
        return Optional.of("claude");
    }

    /** True when the CLI is present and there is a Claude Code login on disk. */
    public boolean available() {
        String home = System.getProperty("user.home", "");
        boolean loggedIn = Files.exists(Path.of(home, ".claude.json")) || Files.exists(Path.of(home, ".claude"));
        return loggedIn && command().isPresent();
    }
}
