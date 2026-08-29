package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;

import java.util.ArrayList;
import java.util.List;

/**
 * What a branch wired to a block's second output is handed.
 *
 * <p>Text, deliberately: the branch is another flow, and a flow's input is a prompt. The first
 * line of each payload is the one a condition gate is most likely drawn on, so it stays short and
 * stable; everything after it is what a person would otherwise have to open the run to find —
 * the block's own console, the worker's output, the verifier's reason.
 */
final class BranchPayloads {

    private BranchPayloads() {
    }

    /**
     * The failure of one block, for the branch on that block's error output.
     *
     * <p>First line: {@code "<block> failed: <error>"} — byte-for-byte what the branch received
     * before the log travelled with it. When the block's own box carries no failure (the run
     * failed for a reason nobody pinned on a block), the run's wording is used, exactly as the
     * run-level branch always said it.
     */
    static String errorOf(AgentRun run, String nodeId) {
        NodeExec exec = run.nodeExecOrNull(nodeId);
        String label = exec != null ? labelOf(exec, nodeId) : specLabel(run, nodeId);
        String runError = run.error == null || run.error.isBlank() ? "The run failed." : run.error;

        String first;
        if (exec != null && "failed".equals(exec.status)) {
            first = label + " failed: " + (exec.error == null || exec.error.isBlank() ? runError : exec.error);
        } else {
            String where = run.failedNodeLabel();
            first = where == null ? runError : where + " failed: " + runError;
        }

        StringBuilder sb = new StringBuilder(first);
        String log = run.logOf(nodeId);
        if (!log.isEmpty()) {
            sb.append("\n\n## Log — ").append(label).append('\n').append(log);
        }
        return sb.toString();
    }

    /**
     * Every worker's fate under the verifier, for the branch on the verifier's rejected output.
     *
     * <p>Rejected workers first — they are why the branch fired — then the accepted ones, then any
     * that crashed before a verdict existed, so the reader has the whole picture and not only the
     * bad half of it. Each carries its output and its own console, because "why was this
     * rejected" is answered by what it wrote and what it did, not by the reason alone.
     */
    static String verificationReport(AgentRun run) {
        CompiledFlow flow = run.compiled;
        List<AgentSpec> workers = flow == null ? List.of() : flow.subAgents();
        AgentSpec verifier = flow == null ? null : flow.verifier();

        List<NodeExec> rejected = new ArrayList<>();
        List<NodeExec> accepted = new ArrayList<>();
        List<NodeExec> crashed = new ArrayList<>();
        for (AgentSpec w : workers) {
            NodeExec exec = run.nodeExecOrNull(w.nodeId);
            if (exec == null) continue;
            if ("rejected".equals(exec.verdict)) rejected.add(exec);
            else if ("accepted".equals(exec.verdict)) accepted.add(exec);
            else if ("failed".equals(exec.status)) crashed.add(exec);
        }
        int judged = rejected.size() + accepted.size() + crashed.size();

        StringBuilder sb = new StringBuilder();
        sb.append("# Verification report — ").append(run.flowName == null ? run.flowId : run.flowName).append('\n');
        if (run.lastVerdictSummary != null && !run.lastVerdictSummary.isBlank()) {
            sb.append(run.lastVerdictSummary.trim()).append('\n');
        }
        sb.append("Rejected ").append(rejected.size()).append(" of ").append(judged).append(" worker(s).\n");

        for (NodeExec e : rejected) {
            sb.append("\n## ✖ ").append(labelOf(e, e.nodeId)).append(" — REJECTED\n");
            sb.append("Reason: ").append(e.verdictReason == null ? "(no reason recorded)" : e.verdictReason).append('\n');
            appendOutputAndLog(sb, run, e);
        }
        for (NodeExec e : accepted) {
            sb.append("\n## ✔ ").append(labelOf(e, e.nodeId)).append(" — accepted\n");
            appendOutputAndLog(sb, run, e);
        }
        for (NodeExec e : crashed) {
            sb.append("\n## ✖ ").append(labelOf(e, e.nodeId)).append(" — failed\n");
            sb.append("Error: ").append(e.error == null ? "(no error text recorded)" : e.error).append('\n');
            appendOutputAndLog(sb, run, e);
        }

        if (verifier != null) {
            String log = run.logOf(verifier.nodeId);
            sb.append("\n## Verifier log — ").append(verifier.name == null ? verifier.nodeId : verifier.name).append('\n');
            sb.append(log.isEmpty() ? "(nothing logged)" : log).append('\n');
        }
        return sb.toString();
    }

    private static void appendOutputAndLog(StringBuilder sb, AgentRun run, NodeExec e) {
        sb.append("### Output\n");
        sb.append(e.output == null || e.output.isBlank() ? "(no output)" : e.output.trim()).append('\n');
        sb.append("### Log\n");
        String log = run.logOf(e.nodeId);
        sb.append(log.isEmpty() ? "(nothing logged)" : log).append('\n');
    }

    private static String labelOf(NodeExec exec, String nodeId) {
        if (exec != null && exec.label != null && !exec.label.isBlank()) return exec.label;
        return nodeId;
    }

    /** The block's configured name, for a block that never got a box in this run. */
    private static String specLabel(AgentRun run, String nodeId) {
        CompiledFlow flow = run.compiled;
        if (flow != null && nodeId != null) {
            List<AgentSpec> all = new ArrayList<>();
            if (flow.coordinator() != null) all.add(flow.coordinator());
            all.addAll(flow.subAgents());
            if (flow.merger() != null) all.add(flow.merger());
            if (flow.verifier() != null) all.add(flow.verifier());
            for (AgentSpec s : all) {
                if (nodeId.equals(s.nodeId) && s.name != null && !s.name.isBlank()) return s.name;
            }
        }
        return nodeId == null ? "The run" : nodeId;
    }
}
