package com.concentus.model;

import java.util.Map;

/**
 * A node on the flow canvas.
 *
 * @param id    canvas node id
 * @param type  "agent" | "mcp" | "repo"
 * @param role  for agent nodes: "coordinator" | "subagent" (null otherwise)
 * @param data  free-form per-type config (name, model, systemPrompt, url, tokenEnv, ...)
 */
public record FlowNode(String id, String type, String role, Map<String, Object> data) {

    public Map<String, Object> dataOrEmpty() {
        return data == null ? Map.of() : data;
    }
}
