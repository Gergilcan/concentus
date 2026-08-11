package com.concentus.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * A boolean. Accepts a real boolean or the string form a hand-edited flow may carry; anything
     * unrecognised falls back rather than silently reading as false, so a typo in a checkbox
     * doesn't quietly disable a condition.
     */
    public static boolean bool(Map<String, Object> d, String key, boolean fallback) {
        Object v = d.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) {
            String t = s.trim();
            if (t.equalsIgnoreCase("true")) return true;
            if (t.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    /**
     * A string→string map (an MCP server's env, say). Entry order is kept — env blocks read
     * better in the order they were written — and null values become empty strings rather than
     * the literal "null" a bare String.valueOf would mint.
     */
    public static Map<String, String> strMap(Map<String, Object> d, String key) {
        Object v = d.get(key);
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                String name = String.valueOf(e.getKey()).trim();
                if (name.isBlank()) continue;
                out.put(name, e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
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
