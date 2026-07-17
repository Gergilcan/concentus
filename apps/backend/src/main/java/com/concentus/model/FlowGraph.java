package com.concentus.model;

import java.util.List;

/**
 * A saved flow: a multi-agent orchestration graph.
 *
 * @param id    stable id (assigned on first save)
 * @param name  display name
 * @param mode  "managed" (multi-agent execution) | "local"
 * @param nodes agent + capability nodes
 * @param edges delegation + capability attachments
 */
public record FlowGraph(String id, String name, String mode,
                        List<FlowNode> nodes, List<FlowEdge> edges) {

    public List<FlowNode> nodesOrEmpty() {
        return nodes == null ? List.of() : nodes;
    }

    public List<FlowEdge> edgesOrEmpty() {
        return edges == null ? List.of() : edges;
    }

    public String modeOrDefault() {
        return (mode == null || mode.isBlank()) ? "managed" : mode;
    }

    /** Returns a copy with the given id (records are immutable). */
    public FlowGraph withId(String newId) {
        return new FlowGraph(newId, name, mode, nodes, edges);
    }
}
