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
        if (t.length() <= max) return t;
        // Surrogate-safe: cutting at a fixed index can split an emoji into a lone surrogate that
        // some JSON writers refuse to encode. Step back one when the cut lands mid-pair.
        int cut = max;
        if (Character.isHighSurrogate(t.charAt(cut - 1))) cut--;
        return t.substring(0, cut) + "…";
    }

    /**
     * A base URL with any trailing slashes removed, so callers can append {@code "/path"} without
     * producing a double slash. Null becomes empty, because a missing base URL is "not configured"
     * rather than an error at the point of trimming.
     *
     * <p>Same reason {@code brief} lives here: every client that joins a configured base to a fixed
     * path had written this expression itself, and a server address that works with one of them and
     * 404s on another is an unpleasant thing to debug.
     */
    public static String trimTrailingSlashes(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }
}
