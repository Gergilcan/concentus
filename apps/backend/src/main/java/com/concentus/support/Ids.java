package com.concentus.support;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared helpers for generating and validating the short, filesystem/URL-safe resource ids used
 * throughout the app (runs, flows, library agents, MCP defs, …).
 */
public final class Ids {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Ids() {
    }

    /** A new id: {@code prefix} followed by {@code randomChars} hex characters from a random UUID. */
    public static String generate(String prefix, int randomChars) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, randomChars);
    }

    /**
     * Validates that {@code id} is safe to use as a filename/path segment (letters, digits, dash,
     * underscore; 1-64 chars), throwing {@link IllegalArgumentException} with
     * {@code errorPrefix + id} otherwise.
     */
    public static String sanitize(String id, String errorPrefix) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(errorPrefix + id);
        }
        return id;
    }
}
