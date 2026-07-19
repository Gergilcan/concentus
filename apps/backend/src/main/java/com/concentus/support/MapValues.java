package com.concentus.support;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * A list of non-blank strings. Accepts either a real list or a single string (which a
     * hand-edited or imported flow may carry), and drops blanks so a trailing empty row in the
     * editor doesn't become an entry.
     */
    public static List<String> strList(Map<String, Object> d, String key) {
        Object v = d.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isBlank()) out.add(s);
            }
        } else if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
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
