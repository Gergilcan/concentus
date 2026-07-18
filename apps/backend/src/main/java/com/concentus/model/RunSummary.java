package com.concentus.model;

import java.util.List;

/** Lightweight view of a run for lists and status polling. */
public record RunSummary(String id, String flowId, String flowName, String mode,
                         String status, long createdAt, String sessionId,
                         List<String> agentIds, String error, String trigger,
                         long totalInputTokens, long totalOutputTokens, double estimatedCostUsd) {
}
