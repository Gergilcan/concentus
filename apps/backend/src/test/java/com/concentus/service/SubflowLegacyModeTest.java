package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flows saved before the drawing decided when a sub-flow runs.
 *
 * <p>A released version wrote a {@code mode} field onto every flow node people drew. Reading those
 * nodes by direction instead would silently change what their flows do — so the field keeps
 * deciding wherever it is present, and these pin down the three ways that could have gone wrong.
 */
class SubflowLegacyModeTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    private static FlowGraph flow(FlowNode sub, List<FlowEdge> extraEdges) {
        List<FlowEdge> edges = new java.util.ArrayList<>(List.of(new FlowEdge("e1", "in-1", "agent-1")));
        edges.addAll(extraEdges);
        return new FlowGraph("parent", "Parent",
                List.of(node("in-1", "input", null, Map.of("mode", "manual")),
                        node("agent-1", "agent", "coordinator", Map.of("name", "Lead")),
                        sub),
                edges, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The correct way to draw a hand-off before the direction rule: mode "after", wired to nothing. */
    @Test
    void an_after_node_wired_to_nothing_still_hands_off_instead_of_refusing_to_compile() {
        FlowGraph graph = flow(node("sub-1", "flow", null,
                Map.of("flowId", "flow_child", "mode", "after")), List.of());

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.afterFlows()).extracting(s -> s.flowId).containsExactly("flow_child");
        assertThat(compiled.coordinator().subflows).isEmpty();
    }

    // Drawn agent → node, which the direction rule now reads as a hand-off. The field says tool,
    // and a tool it stays: the agent asks for it or it does not run.
    @Test
    void a_tool_node_stays_a_tool_whichever_way_it_was_drawn() {
        for (FlowEdge drawn : List.of(new FlowEdge("e2", "agent-1", "sub-1"),
                new FlowEdge("e2", "sub-1", "agent-1"))) {
            FlowGraph graph = flow(node("sub-1", "flow", null,
                    Map.of("flowId", "flow_child", "mode", "tool")), List.of(drawn));

            CompiledFlow compiled = COMPILER.compile(graph);

            assertThat(compiled.afterFlows()).isEmpty();
            assertThat(compiled.coordinator().subflows).extracting(s -> s.flowId)
                    .containsExactly("flow_child");
        }
    }

    // The expensive mistake: a tool the agent used to choose to call would start running on its
    // own at the beginning of every run, spending money on a flow nobody asked for.
    @Test
    void a_legacy_tool_is_never_run_before_the_agent() {
        FlowGraph graph = flow(node("sub-1", "flow", null,
                Map.of("flowId", "flow_child", "mode", "tool")),
                List.of(new FlowEdge("e2", "sub-1", "agent-1")));

        assertThat(COMPILER.compile(graph).coordinator().subflows).allMatch(s -> !s.preRun);
    }

    // A node drawn since carries no mode, so the drawing decides — feeding the agent means it
    // runs first and its answer arrives as context.
    @Test
    void a_node_drawn_since_runs_before_the_agent_it_feeds() {
        FlowGraph graph = flow(node("sub-1", "flow", null, Map.of("flowId", "flow_child")),
                List.of(new FlowEdge("e2", "sub-1", "agent-1")));

        assertThat(COMPILER.compile(graph).coordinator().subflows).allMatch(s -> s.preRun);
    }
}
