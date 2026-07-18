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

    /** Output tokens are exact (from the model's usage); input tokens are an approximate sum. */
    public volatile long inputTokens;
    public volatile long outputTokens;

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
