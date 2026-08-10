package com.concentus.model;

import java.util.List;

/**
 * Two executions side by side: the golden reference and a candidate. Each side carries what the
 * comparison view shows — headline numbers (in the summary), per-node steps (priced), and the
 * run's final answer. Raw facts only: which differences matter is the reader's judgement, so
 * nothing here computes verdicts or deltas.
 */
public record RunComparison(Side reference, Side candidate) {

    public record Side(RunSummary run, List<NodeExec> nodes, String finalOutput) {
    }
}
