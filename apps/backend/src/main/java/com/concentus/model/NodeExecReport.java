package com.concentus.model;

import java.util.List;

/** Per-node execution state for a run, plus the run's total token usage. */
public record NodeExecReport(List<NodeExec> nodes, long totalInputTokens, long totalOutputTokens) {
}
