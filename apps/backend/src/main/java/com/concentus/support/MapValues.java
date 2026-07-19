package com.concentus.support;

import java.util.Map;

/**
 * Small helpers for pulling loosely-typed values (as produced by Jackson's default
 * {@code Map<String, Object>} deserialization) out of a node's {@code data} map, with a
 * fallback when the key is missing or blank.
 */
public final class MapValues {

    private MapValues() {
    }

    public static String str(Map<String, Object> d, String key, String fallback) {
        Object v = d.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }

    public static long lng(Map<String, Object> d, String key, long fallback) {
        Object v = d.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String str && !str.isBlank()) {
            try { return Long.parseLong(str.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }
}
