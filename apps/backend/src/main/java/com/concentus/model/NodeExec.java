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

    /**
     * Context-window occupancy after this node's latest message: the whole prompt the model just
     * read (fresh + cached input) plus what it wrote. Overwritten on every message rather than
     * summed — the cached history is re-read each turn, so a running sum would count the same
     * conversation once per turn and overstate wildly. 0 until the first usage report arrives.
     */
    public volatile long contextTokens;
    /**
     * The context this node STARTED with: its first message's prompt — system prompt, tool
     * schemas, the task it was handed — before it wrote a single token. The difference with
     * {@link #contextTokens} is exactly what the run's conversation (its answers, tool results)
     * added to the window, which is the number that says who is filling the context up.
     */
    public volatile long contextStartTokens;
    /** The model's context-window size, filled in at report time; null when unknown. */
    public volatile Long contextWindow;

    /** Extra process launches after a failed attempt (fan-out only; 0 elsewhere). */
    public volatile int retries;
    /**
     * The adversarial verifier's judgment on this worker's output: {@code accepted} or
     * {@code rejected}. Null when no verifier ran. Deliberately separate from {@link #status}:
     * a rejected worker DID finish its work — the verifier killing its output is a second fact,
     * and folding it into "failed" would misreport what happened.
     */
    public volatile String verdict;
    /** The verifier's stated reason, when rejected. */
    public volatile String verdictReason;

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
