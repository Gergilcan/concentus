package com.concentus.model;

import java.util.Map;

/**
 * How a flow starts, read from its {@code input} node. One flow has at most one input node.
 *
 * <ul>
 *   <li>{@code manual}  — run starts idle; the user sends the first message.</li>
 *   <li>{@code prompt}  — the run auto-starts with {@link #prompt} as the initial input.</li>
 *   <li>{@code cron}    — as {@code prompt}, and also runs automatically on {@link #cron}.</li>
 *   <li>{@code webhook} — an external POST (e.g. a Linear event) starts a run; the payload is the
 *       input, prefixed by {@link #prompt}. Protected by {@link #secret}.</li>
 * </ul>
 */
public record TriggerSpec(String mode, String prompt, String cron, String secret) {

    public static TriggerSpec from(FlowGraph flow) {
        for (FlowNode n : flow.nodesOrEmpty()) {
            if ("input".equalsIgnoreCase(n.type())) {
                Map<String, Object> d = n.dataOrEmpty();
                return new TriggerSpec(
                        str(d, "mode", "manual"),
                        str(d, "prompt", ""),
                        str(d, "cron", ""),
                        str(d, "secret", ""));
            }
        }
        return new TriggerSpec("manual", "", "", "");
    }

    /** The run should immediately fire {@link #prompt} as its first turn (Run button / prompt & cron modes). */
    public boolean autoStart() {
        return ("prompt".equalsIgnoreCase(mode) || "cron".equalsIgnoreCase(mode))
                && prompt != null && !prompt.isBlank();
    }

    /** The flow should be registered on a cron schedule. */
    public boolean scheduled() {
        return "cron".equalsIgnoreCase(mode) && cron != null && !cron.isBlank();
    }

    /** The flow is triggered by an inbound webhook. */
    public boolean webhook() {
        return "webhook".equalsIgnoreCase(mode);
    }

    private static String str(Map<String, Object> d, String key, String fallback) {
        Object v = d.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }
}
