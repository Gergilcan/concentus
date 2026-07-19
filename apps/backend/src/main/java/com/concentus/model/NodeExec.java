package com.concentus.model;

import java.util.List;

/**
 * Live execution state for one canvas node during a run: its status, the input it received, the
 * output it produced (as text/markdown, or a table for SQL), token usage, and any error. Serialized
 * to the UI so each box can show Input/Output tabs and a pass/fail badge.
 */
public class NodeExec {

    public String nodeId;
    /** agent | sql | mcp */
    public String kind;
    public String label;
    /** pending | running | passed | failed */
    public volatile String status = "pending";

    public volatile String input;
    public volatile String output;
    /** text | markdown | table */
    public volatile String format = "text";

    // Tabular output (SQL/RAG).
    public volatile List<String> columns;
    public volatile List<List<String>> rows;

    public volatile String error;

    /**
     * Fresh (uncached) input tokens. Cached prompt tokens are counted separately below — folding
     * them in here made a resumed session, which re-reads its whole history from cache each turn,
     * look enormously more expensive than it is.
     */
    public volatile long inputTokens;
    public volatile long outputTokens;
    /** Prompt tokens served from cache (~0.1x input price) and written to it (~1.25x). */
    public volatile long cacheReadTokens;
    public volatile long cacheWriteTokens;
    /** Model this block ran on — its rate, not a flow-wide one, prices this block. */
    public volatile String model;
    /** USD estimate for this block, filled in when the report is built. */
    public volatile Double estimatedCostUsd;

    public volatile long startedAt;
    public volatile long endedAt;

    public synchronized void appendOutput(String s) {
        if (s == null || s.isEmpty()) return;
        output = (output == null ? "" : output) + s;
        if (!"table".equals(format)) format = "markdown";
    }

    public synchronized void appendInput(String s) {
        if (s == null || s.isEmpty()) return;
        input = (input == null || input.isBlank()) ? s : input + "\n\n---\n\n" + s;
    }
}
