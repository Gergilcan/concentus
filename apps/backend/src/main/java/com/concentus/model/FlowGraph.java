package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * A saved flow: a multi-agent orchestration graph.
 *
 * @param id            stable id (assigned on first save)
 * @param name          display name
 * @param mode          "managed" (multi-agent execution) | "local"
 * @param nodes         agent + capability nodes
 * @param edges         delegation + capability attachments
 * @param enabled       false pauses scheduled (cron) execution without deleting the trigger
 * @param tags          free-form labels used to organize and filter flows
 * @param favorite      pinned to the top of the flow list
 * @param notifyWebhook optional URL POSTed when a run of this flow fails (Slack-compatible)
 */
public record FlowGraph(String id, String name, String mode,
                        List<FlowNode> nodes, List<FlowEdge> edges,
                        Boolean enabled, List<String> tags, Boolean favorite,
                        String notifyWebhook) {

    public List<FlowNode> nodesOrEmpty() {
        return nodes == null ? List.of() : nodes;
    }

    public List<FlowEdge> edgesOrEmpty() {
        return edges == null ? List.of() : edges;
    }

    public List<String> tagsOrEmpty() {
        return tags == null ? List.of() : tags;
    }

    /** Flows are enabled unless explicitly disabled (flows saved before this existed have none). */
    @JsonIgnore
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @JsonIgnore
    public boolean isFavorite() {
        return favorite != null && favorite;
    }

    public String modeOrDefault() {
        return (mode == null || mode.isBlank()) ? "managed" : mode;
    }

    /** Returns a copy with the given id (records are immutable). */
    public FlowGraph withId(String newId) {
        return new FlowGraph(newId, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook);
    }
}
