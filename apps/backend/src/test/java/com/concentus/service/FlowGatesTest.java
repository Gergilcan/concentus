package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a condition node and a for-each node do to a branch, given what the agent before them said.
 *
 * <p>The point of drawing these instead of writing them into a prompt is that the answer is the
 * same every time, so most of what follows pins down the boring cases: empty output, prose where
 * JSON was promised, a list longer than anyone meant to start.
 */
class FlowGatesTest {

    private static FlowNode node(String id, String type, Map<String, Object> data) {
        return new FlowNode(id, type, null, data);
    }

    /** agent → [gates in order] → hand-off. */
    private static FlowGraph chain(List<FlowNode> gates) {
        List<FlowNode> nodes = new ArrayList<>();
        nodes.add(node("agent-1", "agent", Map.of("name", "Reporter")));
        nodes.addAll(gates);
        nodes.add(node("hand-off", "flow", Map.of("flowId", "flow_2", "mode", "after")));

        List<FlowEdge> edges = new ArrayList<>();
        String previous = "agent-1";
        for (FlowNode gate : gates) {
            edges.add(new FlowEdge("e_" + gate.id(), previous, gate.id()));
            previous = gate.id();
        }
        edges.add(new FlowEdge("e_end", previous, "hand-off"));
        return new FlowGraph("flow_1", "Reporting", nodes, edges,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static FlowGates.Decision decide(List<FlowNode> gates, String output) {
        return FlowGates.decide(chain(gates), "hand-off", output);
    }

    @Test
    void a_branch_with_no_gates_fires_once_with_the_output_untouched() {
        FlowGates.Decision decision = decide(List.of(), "the report");

        assertThat(decision.fires()).isTrue();
        assertThat(decision.prompts()).containsExactly("the report");
        assertThat(decision.reason()).isNull();
    }

    @Test
    void a_condition_that_does_not_pass_stops_the_branch() {
        FlowGates.Decision decision = decide(
                List.of(node("if-1", "condition", Map.of("label", "Found anything?",
                        "test", "contains", "value", "ERROR"))),
                "All clear, nothing to report.");

        assertThat(decision.fires()).isFalse();
        assertThat(decision.reason()).contains("Found anything?").contains("did not pass");
    }

    @Test
    void a_condition_that_passes_leaves_the_output_alone() {
        FlowGates.Decision decision = decide(
                List.of(node("if-1", "condition", Map.of("test", "contains", "value", "ERROR"))),
                "3 errors found");

        assertThat(decision.prompts()).containsExactly("3 errors found");
    }

    // The check people reach for first, and the one they forget to configure.
    @Test
    void the_default_condition_is_that_there_is_an_answer_at_all() {
        assertThat(decide(List.of(node("if-1", "condition", Map.of())), "   ").fires()).isFalse();
        assertThat(decide(List.of(node("if-1", "condition", Map.of())), "something").fires()).isTrue();
    }

    @Test
    void matching_is_case_insensitive_unless_the_node_says_otherwise() {
        List<FlowNode> loose = List.of(node("if-1", "condition",
                Map.of("test", "contains", "value", "error")));
        List<FlowNode> strict = List.of(node("if-1", "condition",
                Map.of("test", "contains", "value", "error", "caseSensitive", true)));

        assertThat(decide(loose, "ERROR in build").fires()).isTrue();
        assertThat(decide(strict, "ERROR in build").fires()).isFalse();
    }

    // Refusing to hand off is the safe reading of "I cannot tell whether this should hand off".
    @Test
    void a_broken_regular_expression_fails_the_gate_rather_than_the_run() {
        FlowGates.Decision decision = decide(
                List.of(node("if-1", "condition", Map.of("test", "matches", "value", "[unclosed"))),
                "anything at all");

        assertThat(decision.fires()).isFalse();
    }

    @Test
    void a_for_each_runs_the_branch_once_per_line() {
        FlowGates.Decision decision = decide(
                List.of(node("each-1", "foreach", Map.of("label", "Per repo"))),
                "- concentus\n- website\n\n2. runner\n");

        assertThat(decision.prompts()).containsExactly("concentus", "website", "runner");
        assertThat(decision.reason()).contains("3 item(s)");
    }

    @Test
    void a_for_each_reads_a_json_array_when_asked_to() {
        FlowGates.Decision decision = decide(
                List.of(node("each-1", "foreach", Map.of("source", "json"))),
                "Here they are: [\"alpha\", \"beta\"]");

        assertThat(decision.prompts()).containsExactly("alpha", "beta");
    }

    // An agent told to answer in JSON often writes a sentence and nothing else. Falling back to
    // lines beats starting nothing at all and calling it a decision.
    @Test
    void json_that_never_arrived_falls_back_to_lines() {
        FlowGates.Decision decision = decide(
                List.of(node("each-1", "foreach", Map.of("source", "json"))),
                "alpha\nbeta");

        assertThat(decision.prompts()).containsExactly("alpha", "beta");
    }

    @Test
    void a_list_longer_than_the_limit_is_cut_and_says_so() {
        FlowGates.Decision decision = decide(
                List.of(node("each-1", "foreach", Map.of("label", "Per row", "limit", 2))),
                "one\ntwo\nthree\nfour");

        assertThat(decision.prompts()).containsExactly("one", "two");
        assertThat(decision.reason()).contains("found 4 items").contains("first 2");
    }

    @Test
    void an_empty_list_starts_nothing() {
        FlowGates.Decision decision = decide(List.of(node("each-1", "foreach", Map.of())), "   \n  ");

        assertThat(decision.fires()).isFalse();
    }

    // "For each repository, and only the private ones" — read left to right, the condition judges
    // one item rather than the whole list it came from.
    @Test
    void a_condition_after_a_for_each_filters_the_items() {
        FlowGates.Decision decision = decide(
                List.of(node("each-1", "foreach", Map.of()),
                        node("if-1", "condition", Map.of("test", "contains", "value", "private"))),
                "repo-a (private)\nrepo-b (public)\nrepo-c (private)");

        assertThat(decision.prompts()).containsExactly("repo-a (private)", "repo-c (private)");
        assertThat(decision.reason()).contains("2 of 3");
    }

    @Test
    void gates_on_the_way_to_another_branch_do_not_apply_to_this_one() {
        FlowGraph flow = new FlowGraph("flow_1", "Two branches",
                List.of(node("agent-1", "agent", Map.of("name", "Reporter")),
                        node("if-1", "condition", Map.of("test", "contains", "value", "nope")),
                        node("gated", "flow", Map.of("flowId", "flow_2", "mode", "after")),
                        node("open", "flow", Map.of("flowId", "flow_3", "mode", "after"))),
                List.of(new FlowEdge("e1", "agent-1", "if-1"),
                        new FlowEdge("e2", "if-1", "gated"),
                        new FlowEdge("e3", "agent-1", "open")),
                null, null, null, null, null, null, null, null, null, null, null);

        assertThat(FlowGates.decide(flow, "gated", "the report").fires()).isFalse();
        assertThat(FlowGates.decide(flow, "open", "the report").fires()).isTrue();
    }
}
