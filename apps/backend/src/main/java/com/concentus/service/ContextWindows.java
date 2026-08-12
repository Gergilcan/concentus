package com.concentus.service;

import java.util.Locale;

/**
 * Context-window sizes per model, so the UI can say how full a node's context is — the same
 * figure Claude Code's /context shows, derived here from each message's usage report.
 *
 * <p>Only models whose window is actually known get a number. A self-hosted model's window is
 * whatever the server was started with (Ollama defaults to 2048 and silently truncates), so
 * guessing one would look authoritative and be wrong — unknown stays null and the UI shows the
 * raw token count without a percentage.
 */
final class ContextWindows {

    /** Every current Claude model ships a 200k context window. */
    private static final long CLAUDE_WINDOW = 200_000L;

    private ContextWindows() {
    }

    static Long windowFor(String model) {
        if (model == null || model.isBlank()) return null;
        return model.toLowerCase(Locale.ROOT).contains("claude") ? CLAUDE_WINDOW : null;
    }
}
