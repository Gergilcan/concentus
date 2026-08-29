package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.NodeExec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.concentus.support.MapValues.str;

/**
 * Where a finished run's path would diverge if the same thing happened against the flow as it is
 * drawn <em>today</em>.
 *
 * <p>Golden runs compare outputs; this compares decisions. A run records what every block received
 * and produced, and the flow's gates decide branches from exactly that text — so the recorded
 * outputs can be walked through the <em>current</em> graph without running anything: a condition
 * whose test was edited flips a branch, a deleted block leaves recorded work with nowhere to go, a
 * new block would fire on output that already exists. Each is a divergence a person debugging
 * "why does it behave differently now" wants pointed at, not deduced.
 *
 * <p>Honest about its limits: this replays <b>routing</b>, not agents. Where a decision needs an
 * output that was never recorded (the block never ran, or the block is new), the answer is
 * {@code unknown} rather than a guess — a replay that invents outputs would paint certainty it
 * does not have.
 */
public final class RunReplay {

    private RunReplay() {
    }

    /** Kinds that hang under an agent as capabilities rather than standing on the run's path. */
    private static final Set<String> CAPABILITY_TYPES = Set.of("mcp", "sql", "knowledge", "api", "repo");

    /**
     * Kinds drawn for the people reading the canvas — a sticky note, a frame around blocks. The
     * run never touches them, so a replay has nothing to say about them; listed, a note would
     * read as "skipped then, would run now" and count as a divergence in a flow nobody changed.
     */
    private static final Set<String> ANNOTATION_TYPES = Set.of("note", "group");

    private static boolean annotation(FlowNode node) {
        return node.type() != null && ANNOTATION_TYPES.contains(node.type().toLowerCase());
    }

    /** What happened / would happen to one block. States are a closed vocabulary the UI paints. */
    public record ReplayNode(String nodeId, String label, String type,
                             String then, String now, boolean divergent, String reason) {
    }

    public record ReplayReport(String runId, int divergences, List<ReplayNode> nodes) {
    }

    public static ReplayReport compare(AgentRun run, FlowGraph then, FlowGraph now) {
        Map<String, NodeExec> execs = new LinkedHashMap<>();
        for (NodeExec e : run.nodeExecList()) execs.put(e.nodeId, e);

        Map<String, FlowNode> thenById = byId(then);
        Map<String, FlowNode> nowById = byId(now);

        Walk walk = new Walk(now, nowById, execs);
        List<ReplayNode> out = new ArrayList<>();
        int divergences = 0;

        for (FlowNode node : now.nodesOrEmpty()) {
            if (annotation(node)) continue;
            String thenState = thenState(node.id(), thenById, execs);
            String nowState = walk.state(node.id());
            String reason = walk.reason(node.id());
            boolean divergent = divergent(thenState, nowState);
            if (divergent) divergences++;
            out.add(new ReplayNode(node.id(), labelOf(node, execs), node.type(),
                    thenState, nowState, divergent, reason));
        }

        // Blocks the run executed that the current flow no longer has. Recorded work with nowhere
        // to go is the loudest divergence there is.
        for (FlowNode node : then.nodesOrEmpty()) {
            if (nowById.containsKey(node.id()) || annotation(node)) continue;
            String thenState = thenState(node.id(), thenById, execs);
            boolean divergent = "ran".equals(thenState) || "failed".equals(thenState);
            if (divergent) divergences++;
            out.add(new ReplayNode(node.id(), labelOf(node, execs), node.type(),
                    thenState, "gone", divergent,
                    divergent ? "This block ran, and the flow no longer has it." : null));
        }

        return new ReplayReport(run.id, divergences, out);
    }

    private static boolean divergent(String thenState, String nowState) {
        boolean ranThen = "ran".equals(thenState) || "failed".equals(thenState);
        if (ranThen && "would-skip".equals(nowState)) return true;
        if ("skipped".equals(thenState) && "would-run".equals(nowState)) return true;
        // A new block that would fire on this run's recorded output is a difference in what the
        // flow would do, which is exactly the question.
        return "absent".equals(thenState) && "would-run".equals(nowState);
    }

    private static String thenState(String nodeId, Map<String, FlowNode> thenById,
                                    Map<String, NodeExec> execs) {
        if (!thenById.containsKey(nodeId)) return "absent";
        NodeExec exec = execs.get(nodeId);
        if (exec == null || "pending".equals(exec.status)) return "skipped";
        return "failed".equals(exec.status) ? "failed" : "ran";
    }

    private static String labelOf(FlowNode node, Map<String, NodeExec> execs) {
        NodeExec exec = execs.get(node.id());
        if (exec != null && exec.label != null && !exec.label.isBlank()) return exec.label;
        Map<String, Object> data = node.dataOrEmpty();
        String name = str(data, "name", null);
        if (name != null && !name.isBlank()) return name;
        String label = str(data, "label", null);
        return label != null && !label.isBlank() ? label : node.id();
    }

    private static Map<String, FlowNode> byId(FlowGraph flow) {
        Map<String, FlowNode> out = new LinkedHashMap<>();
        for (FlowNode n : flow.nodesOrEmpty()) out.put(n.id(), n);
        return out;
    }

    /**
     * The routing walk over the current graph, memoized. Each node's answer is one of
     * {@code would-run}, {@code would-skip}, {@code unknown} — plus {@code capability} and
     * {@code gate} for the kinds that are not on the path (they hang under an agent, or they ARE
     * the routing), which are listed but never counted as divergence.
     */
    private static final class Walk {
        private final FlowGraph flow;
        private final Map<String, FlowNode> byId;
        private final Map<String, NodeExec> execs;
        private final Map<String, String> states = new LinkedHashMap<>();
        private final Map<String, String> reasons = new LinkedHashMap<>();
        private final Set<String> visiting = new LinkedHashSet<>();

        Walk(FlowGraph flow, Map<String, FlowNode> byId, Map<String, NodeExec> execs) {
            this.flow = flow;
            this.byId = byId;
            this.execs = execs;
        }

        String state(String id) {
            String cached = states.get(id);
            if (cached != null) return cached;
            if (!visiting.add(id)) return "unknown"; // a cycle decides nothing
            String s = decide(id);
            visiting.remove(id);
            states.put(id, s);
            return s;
        }

        String reason(String id) {
            state(id);
            return reasons.get(id);
        }

        private String decide(String id) {
            FlowNode node = byId.get(id);
            if (node == null) return "unknown";
            String type = node.type() == null ? "" : node.type().toLowerCase();
            if (CAPABILITY_TYPES.contains(type)) return "capability";
            if ("condition".equals(type) || "foreach".equals(type)) return "gate";

            // Roots run by definition: the trigger, and the coordinator the turn is addressed to.
            if ("input".equals(type)) return "would-run";
            if ("agent".equals(type) && "coordinator".equalsIgnoreCase(node.role() == null ? "" : node.role())) {
                return "would-run";
            }

            String srcId = handSource(id);
            if (srcId == null) return "would-run"; // nothing hands to it — it runs on its own

            String srcState = state(srcId);
            String srcLabel = labelOf(byId.get(srcId), execs);
            if ("would-skip".equals(srcState)) {
                reasons.put(id, "'" + srcLabel + "', which hands off to this, would not run.");
                return "would-skip";
            }
            if ("unknown".equals(srcState)) {
                reasons.put(id, "Whether '" + srcLabel + "' would run cannot be decided.");
                return "unknown";
            }

            NodeExec src = execs.get(srcId);
            if (src == null || "pending".equals(src.status)) {
                reasons.put(id, "'" + srcLabel + "' recorded no output in this run to decide with.");
                return "unknown";
            }

            FlowGates.Origin origin = FlowGates.originOf(flow, id);
            if (origin.is(FlowEdge.REJECTED)) {
                // The verifier's rejected output: fires on what the verifier decided about the
                // workers, not on whether the verifier itself ran clean.
                StringBuilder rejected = new StringBuilder();
                for (NodeExec e : execs.values()) {
                    if (!"rejected".equals(e.verdict)) continue;
                    rejected.append(labelOf(byId.get(e.nodeId), execs)).append(" rejected: ")
                            .append(e.verdictReason == null ? "(no reason recorded)" : e.verdictReason).append('\n');
                }
                if (rejected.isEmpty()) {
                    reasons.put(id, "Hangs off the verifier's rejected output, and nothing was rejected in this run.");
                    return "would-skip";
                }
                FlowGates.Decision d = FlowGates.decide(flow, id, rejected.toString());
                reasons.put(id, d.reason());
                return d.fires() ? "would-run" : "would-skip";
            }

            boolean srcFailed = "failed".equals(src.status);
            boolean errorEdge = origin.is(FlowEdge.ERROR);
            if (errorEdge != srcFailed) {
                reasons.put(id, errorEdge
                        ? "Hangs off the error output, and '" + srcLabel + "' did not fail in this run."
                        : "'" + srcLabel + "' failed in this run, and this hangs off its main output.");
                return "would-skip";
            }

            String text = errorEdge
                    ? srcLabel + " failed: " + (src.error == null ? "(no error text recorded)" : src.error)
                    : src.output;
            if (text == null) {
                reasons.put(id, "'" + srcLabel + "' recorded no output in this run to decide with.");
                return "unknown";
            }

            FlowGates.Decision d = FlowGates.decide(flow, id, text);
            reasons.put(id, d.reason());
            return d.fires() ? "would-run" : "would-skip";
        }

        /**
         * The node whose output decides whether {@code id} runs, looking through gates — and
         * looking past capabilities, whose edges point INTO an agent without handing anything off.
         */
        private String handSource(String id) {
            Set<String> seen = new LinkedHashSet<>();
            String current = id;
            while (current != null && seen.add(current)) {
                String next = null;
                for (FlowEdge e : flow.edgesOrEmpty()) {
                    if (!current.equals(e.target())) continue;
                    FlowNode source = byId.get(e.source());
                    if (source == null) continue;
                    String type = source.type() == null ? "" : source.type().toLowerCase();
                    if (CAPABILITY_TYPES.contains(type)) continue; // feeds, not hands off
                    if ("agent".equals(type) && !"coordinator".equalsIgnoreCase(
                            source.role() == null ? "" : source.role()) && "agent".equals(kindOf(id))) {
                        // A drawn sub-agent wired into another agent feeds it (it is the plan),
                        // rather than handing it a finished output.
                        continue;
                    }
                    if ("condition".equals(type) || "foreach".equals(type)) {
                        next = source.id();
                        break;
                    }
                    return source.id();
                }
                current = next;
            }
            return null;
        }

        private String kindOf(String id) {
            FlowNode n = byId.get(id);
            return n == null || n.type() == null ? "" : n.type().toLowerCase();
        }
    }
}
