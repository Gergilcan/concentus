package com.concentus.model;

import java.util.List;

/**
 * Per-node execution state for a run, plus the run's totals.
 *
 * @param totalCostUsd sum of the per-node estimates, so the run figure and the block figures are
 *                     derived the same way instead of by two different calculations.
 */
public record NodeExecReport(List<NodeExec> nodes, long totalInputTokens, long totalOutputTokens,
                             double totalCostUsd) {
}
