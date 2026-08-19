package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One block of a flow, cut out as a flow of its own so it can be run again on its own.
 *
 * <p>Tuning a sub-agent's prompt used to cost a whole execution: the only way to see what the
 * third block does with a different instruction was to run the first two again, pay for them
 * again, and wait. A slice is that block plus the capabilities wired to it, handed the input the
 * block actually received last time — so the same work costs one block instead of a flow.
 *
 * <p>Two shapes, and the difference is what "from here" means. {@code downstream = false} is the
 * block alone, for prompt work. {@code downstream = true} keeps everything the block delegates to,
 * which is what continuing a failed run from the point it broke means: the agents below it are
 * part of its job, not later steps in a queue.
 *
 * <p>The delegation walk is borrowed from {@link FlowCompiler} rather than reimplemented. A slice
 * that disagreed with the compiler about who delegates to whom would run a different flow from the
 * one drawn, which is the one thing a re-run must never do.
 */
public final class BlockSlice {

    private BlockSlice() {
    }

    /** Node types that are capabilities — they attach to an agent and never run on their own. */
    private static final Set<String> CAPABILITIES = Set.of("mcp", "repo", "sql", "knowledge", "api");

    /**
     * The flow that runs {@code nodeId} alone.
     *
     * @param downstream also keep the agents this one delegates to, transitively
     * @throws IllegalArgumentException if the node is not an agent of this flow — capability nodes
     *                                  have nothing to execute, and saying so beats a run that
     *                                  starts and immediately finds no agent to be.
     */
    public static FlowGraph of(FlowGraph flow, String nodeId, boolean downstream) {
        return of(flow, nodeId, downstream, null);
    }

    /**
     * As above, with the block run on a different model.
     *
     * <p>Same input, same instructions, one thing changed — which is the only shape in which the
     * answer to "would the cheaper model do this just as well" means anything. Asking it used to
     * cost a whole execution AND an edit to the flow, so the honest way to ask was expensive
     * enough that people guessed instead.
     *
     * <p>Only the block itself is re-modelled: the agents it delegates to keep theirs. This
     * compares one box against itself, and moving four models at once would compare two flows.
     */
    public static FlowGraph of(FlowGraph flow, String nodeId, boolean downstream, String model) {
        FlowNode target = null;
        for (FlowNode n : flow.nodesOrEmpty()) {
            if (n.id().equals(nodeId)) target = n;
        }
        if (target == null) {
            throw new IllegalArgumentException("This run's flow has no block '" + nodeId + "'.");
        }
        if (!"agent".equalsIgnoreCase(target.type())) {
            throw new IllegalArgumentException("Only agent blocks can be run on their own; '"
                    + label(target) + "' is a " + target.type() + " block, which is a capability an "
                    + "agent uses rather than something that runs by itself.");
        }

        Set<String> keptAgents = new LinkedHashSet<>();
        keptAgents.add(nodeId);
        if (downstream) keptAgents.addAll(descendants(flow, nodeId));

        List<FlowNode> nodes = new ArrayList<>();
        // The target leads its own slice. Role matters: the compiler needs exactly one coordinator,
        // and the block being re-run is the one giving orders here even when it takes them in the
        // flow it was cut from.
        nodes.add(new FlowNode(target.id(), target.type(), "coordinator",
                model == null || model.isBlank() ? target.data() : withModel(target, model)));
        for (FlowNode n : flow.nodesOrEmpty()) {
            if (n.id().equals(nodeId)) continue;
            if (keptAgents.contains(n.id())) {
                nodes.add(new FlowNode(n.id(), n.type(), "subagent", n.data()));
            } else if (CAPABILITIES.contains(lower(n.type())) && wiredToAny(flow, n.id(), keptAgents)) {
                nodes.add(n);
            }
        }

        // Only edges whose both ends survived. An edge to a dropped node would name something the
        // sliced flow does not contain, and the compiler reads edges to decide who can use what.
        Set<String> keptIds = new LinkedHashSet<>();
        for (FlowNode n : nodes) keptIds.add(n.id());
        List<FlowEdge> edges = new ArrayList<>();
        for (FlowEdge e : flow.edgesOrEmpty()) {
            if (keptIds.contains(e.source()) && keptIds.contains(e.target())) edges.add(e);
        }

        // No input node, and that is deliberate rather than an omission: the trigger belongs to the
        // flow, not to a block of it. A slice carrying a cron would schedule itself, and one
        // carrying a webhook secret would answer the internet. The pinned input arrives as the
        // run's initial prompt instead.
        //
        // No flow nodes either — a hand-off fires when the FLOW finishes, and re-running one block
        // is not the flow finishing. Chaining onward from a block someone is still tuning would
        // send real work downstream on the strength of a draft.
        return new FlowGraph(null, sliceName(flow, target), flow.modeOrDefault(), nodes, edges,
                Boolean.TRUE, List.of(), Boolean.FALSE, null, flow.budgetUsd(),
                flow.approvalSlackCredentialId(), flow.approvalSlackChannel(),
                flow.approvalTeamsWebhook(), flow.variables(), null, Boolean.FALSE);
    }

    /** The block's data with one field replaced. The flow on disk is never touched. */
    private static Map<String, Object> withModel(FlowNode target, String model) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(target.dataOrEmpty());
        data.put("model", model.trim());
        return data;
    }

    /** What to call a block in a message, given the flow it belongs to. Falls back to its id. */
    public static String labelOf(FlowGraph flow, String nodeId) {
        for (FlowNode n : flow.nodesOrEmpty()) {
            if (n.id().equals(nodeId)) return label(n);
        }
        return nodeId;
    }

    /** Every agent below this one in the flow's delegation tree. */
    private static Set<String> descendants(FlowGraph flow, String nodeId) {
        Map<String, String> delegatorOf = FlowCompiler.delegatorsOf(flow);
        Set<String> out = new LinkedHashSet<>();
        // Walked child-upwards: for each agent, climb to the root and keep it if the target is on
        // the way. Cheaper to get right than a downward walk over a map keyed the other way, and
        // the climb terminates because the walk that built the map visits each node once.
        for (String candidate : delegatorOf.keySet()) {
            String parent = delegatorOf.get(candidate);
            int guard = delegatorOf.size() + 1;
            while (parent != null && guard-- > 0) {
                if (parent.equals(nodeId)) {
                    out.add(candidate);
                    break;
                }
                parent = delegatorOf.get(parent);
            }
        }
        return out;
    }

    private static boolean wiredToAny(FlowGraph flow, String nodeId, Set<String> others) {
        for (FlowEdge e : flow.edgesOrEmpty()) {
            if (nodeId.equals(e.source()) && others.contains(e.target())) return true;
            if (nodeId.equals(e.target()) && others.contains(e.source())) return true;
        }
        return false;
    }

    private static String sliceName(FlowGraph flow, FlowNode target) {
        return flow.name() + " · " + label(target);
    }

    private static String label(FlowNode node) {
        Object name = node.dataOrEmpty().get("name");
        if (name instanceof String s && !s.isBlank()) return s;
        Object label = node.dataOrEmpty().get("label");
        if (label instanceof String s && !s.isBlank()) return s;
        return node.id();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
