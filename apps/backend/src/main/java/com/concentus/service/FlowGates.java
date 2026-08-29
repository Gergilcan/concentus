package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;

/**
 * Decision and iteration drawn on the canvas, between an agent and what it hands off to.
 *
 * <p>Both used to live inside a prompt: "if the report found nothing, do not send it", "for each
 * repository in the list, open an issue". An instruction is a request, not a rule — an agent
 * having a bad day sends the empty report anyway, and nothing on the canvas said it should not.
 * A condition node reads the same output afterwards and decides by measurement, which is the
 * difference between usually and always.
 *
 * <p>Gates are read from the graph at the moment a hand-off fires rather than compiled into the
 * flow. They act on text the run has already produced, so there is nothing to compile: the whole
 * decision is "given this output, does this branch run, and how many times".
 */
public final class FlowGates {

    private FlowGates() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How many runs a single for-each may start, unless the node lowers it. */
    private static final int DEFAULT_LIMIT = 25;

    /**
     * What a branch should do with the output that reached it.
     *
     * @param prompts one per run to start — empty means the branch does not run at all
     * @param reason  why, in words a person reading the run log can act on; null when nothing
     *                gated the branch and it simply fired
     */
    public record Decision(List<String> prompts, String reason) {

        public boolean fires() {
            return !prompts.isEmpty();
        }
    }

    /**
     * Applies whatever condition and for-each nodes are drawn between an agent and {@code nodeId}.
     *
     * <p>An ungated branch fires once with the output unchanged — which is what every hand-off
     * drawn before this existed does. The gates are additive: a flow that draws none behaves
     * exactly as it did.
     */
    public static Decision decide(FlowGraph flow, String nodeId, String output) {
        String text = output == null ? "" : output;
        List<String> prompts = new ArrayList<>(List.of(text));
        String reason = null;

        for (GateHop hop : gatesInto(flow, nodeId)) {
            FlowNode gate = hop.gate();
            Map<String, Object> data = gate.dataOrEmpty();
            String label = str(data, "label", gate.id());
            if ("condition".equalsIgnoreCase(gate.type())) {
                // Judged on the text that reached the gate, so after a for-each it judges one item:
                // "for each repository, and only the private ones" reads left to right and works.
                // A branch on the gate's else output keeps what the test rejected — the same rule,
                // read from the other side, which is what an else is.
                List<String> kept = new ArrayList<>();
                for (String p : prompts) {
                    if (passes(data, p) != hop.inverted()) kept.add(p);
                }
                if (kept.size() != prompts.size()) {
                    String side = hop.inverted() ? " (else branch)" : "";
                    reason = kept.isEmpty()
                            ? "'" + label + "'" + side + " did not pass, so this branch did not run."
                            : "'" + label + "'" + side + " let " + kept.size() + " of "
                                    + prompts.size() + " through.";
                }
                prompts = kept;
            } else if ("foreach".equalsIgnoreCase(gate.type())) {
                List<String> items = new ArrayList<>();
                for (String p : prompts) items.addAll(split(data, p));
                int limit = (int) Math.max(1, lng(data, "limit", DEFAULT_LIMIT));
                if (items.size() > limit) {
                    // Truncated out loud. A list that quietly lost its tail is a flow that quietly
                    // does part of the job, and the part it skipped is the part nobody can see.
                    reason = "'" + label + "' found " + items.size() + " items and ran the first "
                            + limit + "; raise its limit to run them all.";
                    items = new ArrayList<>(items.subList(0, limit));
                } else {
                    reason = "'" + label + "' split the output into " + items.size() + " item(s).";
                }
                prompts = items;
            }
            if (prompts.isEmpty()) break;
        }
        return new Decision(List.copyOf(prompts), reason);
    }

    /**
     * Which output of which block a branch hangs off.
     *
     * <p>{@code handle} is the block's output as the wire names it — null for the main one, or one
     * of the {@link FlowEdge} names. {@code sourceId} is the block, looking through any gates drawn
     * in between: a condition between an agent and its recovery branch decides whether the branch
     * fires, not which output it belongs to nor whose. Both null when nothing feeds the branch.
     *
     * <p>Not bean-named ({@code isMain}) for the reason FlowEdge spells out: a record with a
     * getter-shaped method grows a JSON property.
     */
    public record Origin(String sourceId, String handle) {

        public boolean onMain() {
            return handle == null;
        }

        public boolean is(String handle) {
            return handle.equals(this.handle);
        }
    }

    /**
     * The block and the output the wire reaching {@code nodeId} left from.
     *
     * <p>Walks through gates the same way {@link #sourceThroughGates} does. A gate's own
     * {@code else} edge is the gate's answer, not the block's output, so it is stepped over: the
     * block's output is whatever the edge INTO the first gate says.
     *
     * <p>A flow drawn before outputs had names has null on every edge, which reads as the main
     * output. That is the right answer: those hand-offs run when the flow succeeds, exactly as
     * they always did.
     */
    public static Origin originOf(FlowGraph flow, String nodeId) {
        Map<String, FlowNode> byId = byId(flow);
        Set<String> seen = new LinkedHashSet<>();
        String current = nodeId;
        while (current != null && seen.add(current)) {
            String next = null;
            for (FlowEdge e : flow.edgesOrEmpty()) {
                if (!current.equals(e.target())) continue;
                FlowNode source = byId.get(e.source());
                if (source == null || !isGate(source.type())) {
                    String handle = e.onMainOutput() ? null : e.sourceHandle();
                    return new Origin(source == null ? e.source() : source.id(), handle);
                }
                next = source.id();
                break;
            }
            current = next;
        }
        return new Origin(null, null);
    }

    /**
     * What feeds this node, looking through any gates drawn in between; null when nothing does.
     *
     * <p>For anything asking "is this hand-off wired to an agent", a gate must be transparent. The
     * wire agent → condition → flow is one wire with a rule on it, and reading it as two would
     * make drawing the rule detach the branch from the agent it belongs to.
     */
    public static String sourceThroughGates(FlowGraph flow, String nodeId) {
        Map<String, FlowNode> byId = byId(flow);
        Set<String> seen = new LinkedHashSet<>();
        String current = nodeId;
        while (current != null && seen.add(current)) {
            String next = null;
            for (FlowEdge e : flow.edgesOrEmpty()) {
                if (!current.equals(e.target())) continue;
                FlowNode source = byId.get(e.source());
                if (source == null) continue;
                if (!isGate(source.type())) return source.id();
                next = source.id();
                break;
            }
            current = next;
        }
        return null;
    }

    /**
     * A gate on the way to a branch, and which of its outputs the branch hangs from.
     *
     * <p>{@code inverted} is a condition wired from its <b>else</b> output: the branch runs when
     * the test does NOT hold. On a for-each the flag is meaningless and stays false.
     */
    record GateHop(FlowNode gate, boolean inverted) {
    }

    /**
     * The gates on the way into a node, in drawn order — the one nearest the agent first.
     *
     * <p>Walked backwards from the target and then reversed, because a chain is drawn forwards but
     * only its far end is known here. Each step takes the first gate feeding the current node: a
     * gate with two gates feeding it is a drawing with two answers, and picking one silently beats
     * neither of the alternatives, so the walk stays deterministic and the canvas stays honest by
     * not offering that shape.
     */
    private static List<GateHop> gatesInto(FlowGraph flow, String nodeId) {
        Map<String, FlowNode> byId = byId(flow);
        List<GateHop> backwards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = nodeId;
        while (current != null && seen.add(current)) {
            String previous = null;
            for (FlowEdge e : flow.edgesOrEmpty()) {
                if (!current.equals(e.target())) continue;
                FlowNode source = byId.get(e.source());
                if (source != null && isGate(source.type())) {
                    previous = source.id();
                    // The edge OUT of the gate into this hop carries the answer: main means "when
                    // it holds", else means "when it does not".
                    backwards.add(new GateHop(source, e.is(FlowEdge.ELSE)));
                    break;
                }
            }
            current = previous;
        }
        Collections.reverse(backwards);
        return backwards;
    }

    private static boolean isGate(String type) {
        return "condition".equalsIgnoreCase(type) || "foreach".equalsIgnoreCase(type);
    }

    /** The flow's nodes by id, for the walks here and in {@link RunReplay}. */
    static Map<String, FlowNode> byId(FlowGraph flow) {
        Map<String, FlowNode> byId = new LinkedHashMap<>();
        for (FlowNode n : flow.nodesOrEmpty()) byId.put(n.id(), n);
        return byId;
    }

    /** Whether one piece of text passes a condition node. */
    private static boolean passes(Map<String, Object> data, String text) {
        String test = str(data, "test", "not_empty");
        String value = str(data, "value", "");
        boolean caseSensitive = bool(data, "caseSensitive", false);
        String haystack = caseSensitive ? text : text.toLowerCase();
        String needle = caseSensitive ? value : value.toLowerCase();
        return switch (test.toLowerCase()) {
            case "contains" -> haystack.contains(needle);
            case "not_contains" -> !haystack.contains(needle);
            case "equals" -> haystack.trim().equals(needle.trim());
            case "matches" -> matches(value, text, caseSensitive);
            // An agent that answered nothing has decided nothing, so the default gate is "there is
            // an answer at all" — the check people reach for first and the one they forget.
            default -> !text.isBlank();
        };
    }

    /**
     * A regular expression, applied to the whole output.
     *
     * <p>A broken pattern fails the gate rather than the run. Refusing to hand off is the safe
     * reading of "I cannot tell whether this should hand off", and the caller says which gate.
     */
    private static boolean matches(String pattern, String text, boolean caseSensitive) {
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            return Pattern.compile(pattern, flags | Pattern.DOTALL).matcher(text).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * The items a for-each node reads out of one output.
     *
     * <p>JSON first when asked for it, because a list an agent was told to return as JSON is the
     * reliable shape; lines are the fallback for everything else, with the bullet or numbering an
     * agent writes anyway stripped off. Blank lines are dropped — an empty item would start a run
     * with no instruction, and a run with no instruction improvises.
     */
    private static List<String> split(Map<String, Object> data, String text) {
        String source = str(data, "source", "lines");
        if ("json".equalsIgnoreCase(source)) {
            List<String> items = fromJson(text);
            if (items != null) return items;
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String item = line.strip().replaceFirst("^([-*]|\\d+[.)])\\s+", "").strip();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    /**
     * A JSON array anywhere in the output, one string per element. Null when there is none: an
     * agent asked for JSON often writes a sentence first, and falling back to lines beats failing
     * on prose.
     */
    private static List<String> fromJson(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode array = JSON.readTree(text.substring(start, end + 1));
            if (!array.isArray()) return null;
            List<String> out = new ArrayList<>();
            for (JsonNode item : array) {
                String value = item.isTextual() ? item.asText() : item.toString();
                if (!value.isBlank()) out.add(value);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
