package com.concentus.model;

/**
 * A single output event streamed to the UI.
 *
 * @param type  "system" | "status" | "agent_message" | "tool_use" | "error"
 * @param text  human-readable payload
 * @param agent originating agent/thread label (may be null)
 * @param ts    epoch millis
 */
public record RunEvent(String type, String text, String agent, long ts) {

    public static RunEvent of(String type, String text) {
        return new RunEvent(type, text, null, System.currentTimeMillis());
    }

    public static RunEvent of(String type, String text, String agent) {
        return new RunEvent(type, text, agent, System.currentTimeMillis());
    }
}
