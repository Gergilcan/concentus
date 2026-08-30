package com.concentus.model;

import java.util.List;

/**
 * Lightweight view of a run for lists and status polling.
 *
 * <p>{@code flowVersion} is the flow revision this execution ran, or 0 when the flow had no
 * history at launch (unsaved flow, or version history unavailable).
 */
public record RunSummary(String id, String flowId, String flowName,
                         String status, long createdAt, String sessionId,
                         List<String> agentIds, String error, String trigger,
                         long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                         boolean golden, int flowVersion,
                         /**
                          * The person who pressed Run. Null for a schedule, a webhook delivery or
                          * a flow started by another flow — those say what they were in
                          * {@code trigger}, and a name invented for them would be worse than a gap.
                          */
                         String startedBy,
                         /** The group the flow belonged to at launch; null for a flow the whole organization sees. */
                         String groupId,
                         /** The runner this run's CLI executed on; null for a run on this server. */
                         String runnerId,
                         /** That runner's name at launch, kept so the run says where it ran after a rename or a delete. */
                         String runnerName) {

    /** The shape before runs carried a runner, kept for the callers that predate it. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public RunSummary(String id, String flowId, String flowName,
                      String status, long createdAt, String sessionId,
                      List<String> agentIds, String error, String trigger,
                      long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                      boolean golden, int flowVersion, String startedBy, String groupId) {
        this(id, flowId, flowName, status, createdAt, sessionId, agentIds, error, trigger,
                totalInputTokens, totalOutputTokens, estimatedCostUsd, golden, flowVersion, startedBy, groupId,
                null, null);
    }

    /** The shape before runs carried a group, kept for the callers that predate it. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public RunSummary(String id, String flowId, String flowName,
                      String status, long createdAt, String sessionId,
                      List<String> agentIds, String error, String trigger,
                      long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                      boolean golden, int flowVersion, String startedBy) {
        this(id, flowId, flowName, status, createdAt, sessionId, agentIds, error, trigger,
                totalInputTokens, totalOutputTokens, estimatedCostUsd, golden, flowVersion, startedBy, null, null, null);
    }

    /** The shape before executions were credited to a person, kept for the callers that predate it. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public RunSummary(String id, String flowId, String flowName,
                      String status, long createdAt, String sessionId,
                      List<String> agentIds, String error, String trigger,
                      long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                      boolean golden, int flowVersion) {
        this(id, flowId, flowName, status, createdAt, sessionId, agentIds, error, trigger,
                totalInputTokens, totalOutputTokens, estimatedCostUsd, golden, flowVersion, null, null, null, null);
    }
}
