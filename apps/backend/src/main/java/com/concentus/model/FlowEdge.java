package com.concentus.model;

/**
 * A connection on the canvas. Direction is used for delegation (coordinator -> subagent);
 * capability attachments (mcp/repo -> agent) are treated as undirected.
 */
public record FlowEdge(String id, String source, String target) {
}
