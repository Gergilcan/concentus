package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

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
 * @param budgetUsd     optional monthly spend ceiling; at or past it, new runs are refused
 * @param approvalSlackCredentialId stored credential holding a Slack bot token; with a channel,
 *                      approval requests post to Slack and a ✅/❌ reaction decides them remotely
 * @param approvalSlackChannel Slack channel id (or public-channel name) the requests post to
 * @param approvalTeamsWebhook Teams incoming-webhook URL notified on approval requests
 *                      (notification only — Teams has no local-first way to carry the answer back)
 * @param variables     this flow's {@code {{NAME}}} values — overrides of the organization's
 *                      variables plus any of its own. Saved with the flow, so a flow remembers
 *                      the values it runs with.
 */
public record FlowGraph(String id, String name, String mode,
                        List<FlowNode> nodes, List<FlowEdge> edges,
                        Boolean enabled, List<String> tags, Boolean favorite,
                        String notifyWebhook, Double budgetUsd,
                        String approvalSlackCredentialId, String approvalSlackChannel,
                        String approvalTeamsWebhook, Map<String, String> variables) {

    /** The pre-variables shape, kept so the many existing constructions stay valid. */
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd,
                     String approvalSlackCredentialId, String approvalSlackChannel,
                     String approvalTeamsWebhook) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                approvalSlackCredentialId, approvalSlackChannel, approvalTeamsWebhook, null);
    }

    /** The pre-remote-approval shape, kept so the many existing constructions stay valid. */
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                null, null, null, null);
    }

    /** The pre-budget shape, kept for the same reason. */
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, null);
    }

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

    /**
     * Returns a copy with the given id (records are immutable). Copies every component — this
     * used to call the pre-budget constructor, so restoring a flow version silently dropped its
     * monthly budget.
     */
    public FlowGraph withId(String newId) {
        return new FlowGraph(newId, name, mode, nodes, edges, enabled, tags, favorite,
                notifyWebhook, budgetUsd, approvalSlackCredentialId, approvalSlackChannel,
                approvalTeamsWebhook);
    }
}
