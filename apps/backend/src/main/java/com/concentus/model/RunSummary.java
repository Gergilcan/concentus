package com.concentus.model;

import java.util.List;

/**
 * Lightweight view of a run for lists and status polling.
 *
 * <p>{@code flowVersion} is the flow revision this execution ran, or 0 when the flow had no
 * history at launch (unsaved flow, or version history unavailable).
 */
public record RunSummary(String id, String flowId, String flowName, String mode,
                         String status, long createdAt, String sessionId,
                         List<String> agentIds, String error, String trigger,
                         long totalInputTokens, long totalOutputTokens, double estimatedCostUsd,
                         boolean golden, int flowVersion) {
}
