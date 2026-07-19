package com.concentus.model;

/**
 * A single output event streamed to the UI.
 *
 * @param type    "system" | "status" | "agent_message" | "tool_use" | "error"
 * @param text    human-readable payload
 * @param agent   originating agent's display name (may be null)
 * @param agentId canvas node id of the originating agent (may be null). The unique key: two agents
 *                can carry the same display name, and only the node id ties a line back to the
 *                block it came from, which is what lets the UI show one agent's log on its own.
 * @param ts      epoch millis
 */
public record RunEvent(String type, String text, String agent, String agentId, long ts) {

    public static RunEvent of(String type, String text) {
        return new RunEvent(type, text, null, null, System.currentTimeMillis());
    }

    public static RunEvent of(String type, String text, String agent) {
        return new RunEvent(type, text, agent, null, System.currentTimeMillis());
    }

    public static RunEvent of(String type, String text, String agent, String agentId) {
        return new RunEvent(type, text, agent, agentId, System.currentTimeMillis());
    }
}
