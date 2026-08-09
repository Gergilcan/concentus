package com.concentus.support;

/** Tiny text helpers shared across packages that each used to rewrite them. */
public final class Texts {

    private Texts() {
    }

    /**
     * Clamps a body to a readable length for a log or console line.
     *
     * <p>One implementation instead of the three private copies (two at 300 chars, one at 400)
     * that {@code McpClient}, {@code McpOAuth} and {@code LocalModelClient} each kept — the
     * truncation marker and null-handling had already started to drift.
     */
    public static String brief(String body, int max) {
        if (body == null) return "";
        String t = body.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
