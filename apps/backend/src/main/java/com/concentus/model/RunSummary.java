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
                         String startedBy) {

    /** The shape before executions were credited to a person, kept for the callers that predate it. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public RunSummary(String id, String flowId, String flowName,
                      String status, long createdAt, String sessionId,
                      List<String> agentIds, String error, String trigger,
                      long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                      boolean golden, int flowVersion) {
        this(id, flowId, flowName, status, createdAt, sessionId, agentIds, error, trigger,
                totalInputTokens, totalOutputTokens, estimatedCostUsd, golden, flowVersion, null);
    }
}
