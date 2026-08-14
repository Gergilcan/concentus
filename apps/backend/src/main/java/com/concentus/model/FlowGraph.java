package com.concentus.model;

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
 * @param folder        dashboard folder this flow lives in; null/blank means the root. One level
 *                      deep on purpose — the bundled starters land in "Samples" so a first launch
 *                      is an invitation, not a wall.
 * @param goldenAutoRun when true, saving this flow immediately replays its golden reference's
 *                      input against the new graph. Off unless asked for, and deliberately: every
 *                      such check is a real agent run with a real bill, and a setting that spends
 *                      money on every save must be chosen, not inherited.
 */
public record FlowGraph(String id, String name, String mode,
                        List<FlowNode> nodes, List<FlowEdge> edges,
                        Boolean enabled, List<String> tags, Boolean favorite,
                        String notifyWebhook, Double budgetUsd,
                        String approvalSlackCredentialId, String approvalSlackChannel,
                        String approvalTeamsWebhook, Map<String, String> variables,
                        String folder, Boolean goldenAutoRun) {

    // Every convenience constructor below carries @JsonIgnore, and it is load-bearing: Jackson's
    // creator detection, faced with several record constructors, can pick a SECONDARY one to
    // deserialise with — which silently drops every component that constructor predates. That is
    // not hypothetical: reading flows back from the store lost `folder` on arrival, and had been
    // losing `variables` the same way. @JsonIgnore takes the shortcuts out of the running; only
    // the canonical constructor deserialises.

    /** The pre-golden-auto-run shape, kept so the many existing constructions stay valid. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd,
                     String approvalSlackCredentialId, String approvalSlackChannel,
                     String approvalTeamsWebhook, Map<String, String> variables,
                     String folder) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                approvalSlackCredentialId, approvalSlackChannel, approvalTeamsWebhook, variables,
                folder, null);
    }

    /** The pre-folders shape, kept so the many existing constructions stay valid. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd,
                     String approvalSlackCredentialId, String approvalSlackChannel,
                     String approvalTeamsWebhook, Map<String, String> variables) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                approvalSlackCredentialId, approvalSlackChannel, approvalTeamsWebhook, variables,
                null, null);
    }

    /** The pre-variables shape, kept so the many existing constructions stay valid. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd,
                     String approvalSlackCredentialId, String approvalSlackChannel,
                     String approvalTeamsWebhook) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                approvalSlackCredentialId, approvalSlackChannel, approvalTeamsWebhook, null, null,
                null);
    }

    /**
     * This flow, filed under {@code folder} — how the seeder sends starters to "Samples".
     * Canonical constructor, for the same reason {@link #withId} uses it.
     */
    public FlowGraph withFolder(String newFolder) {
        return new FlowGraph(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook,
                budgetUsd, approvalSlackCredentialId, approvalSlackChannel, approvalTeamsWebhook,
                variables, newFolder, goldenAutoRun);
    }

    /** The pre-remote-approval shape, kept so the many existing constructions stay valid. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public FlowGraph(String id, String name, String mode,
                     List<FlowNode> nodes, List<FlowEdge> edges,
                     Boolean enabled, List<String> tags, Boolean favorite,
                     String notifyWebhook, Double budgetUsd) {
        this(id, name, mode, nodes, edges, enabled, tags, favorite, notifyWebhook, budgetUsd,
                null, null, null, null);
    }

    /** The pre-budget shape, kept for the same reason. */
    @com.fasterxml.jackson.annotation.JsonIgnore
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
    // NOT bean-getter names (isEnabled/isFavorite), and that is load-bearing: a bean-style getter
    // merges with the record component of the same property name, and its @JsonIgnore took the
    // component down with it — a paused flow came back unpaused, a pinned one unpinned. A name
    // Jackson does not recognise as a getter cannot collide with anything.
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public boolean favoriteOrDefault() {
        return favorite != null && favorite;
    }

    public String modeOrDefault() {
        return (mode == null || mode.isBlank()) ? "managed" : mode;
    }

    /**
     * Returns a copy with the given id (records are immutable). MUST call the canonical
     * constructor: every save goes through here (JsonStore stamps the id on write), so a stale
     * shortcut silently strips whatever it predates from EVERY stored flow. It has now done so
     * twice — the pre-budget shape dropped budgets on version restore, and the pre-variables
     * shape dropped {@code variables} and {@code folder} on save.
     */
    public FlowGraph withId(String newId) {
        return new FlowGraph(newId, name, mode, nodes, edges, enabled, tags, favorite,
                notifyWebhook, budgetUsd, approvalSlackCredentialId, approvalSlackChannel,
                approvalTeamsWebhook, variables, folder, goldenAutoRun);
    }

    /** Whether saving this flow should immediately re-run its golden reference. Off by default. */
    // Not isGoldenAutoRun(): a bean-style getter merges with the record component of the same name
    // and takes it down with it. See enabledOrDefault above — this cost a release once already.
    public boolean goldenAutoRunOrDefault() {
        return goldenAutoRun != null && goldenAutoRun;
    }
}
