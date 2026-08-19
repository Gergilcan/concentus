package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Where a flow node is wired decides when it runs.
 *
 * <p>It used to be a field on the node, which asked the canvas to say twice what it already showed
 * once: a box drawn feeding the agent, and then a dropdown insisting it feeds the agent. Direction
 * is the honest answer — the same way an input node feeds and nothing feeds it.
 */
class SubflowWiringTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    private static FlowGraph flow(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("parent", "Parent", "local", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<FlowNode> base(FlowNode... extra) {
        List<FlowNode> nodes = new java.util.ArrayList<>(List.of(
                node("in-1", "input", null, Map.of("mode", "manual")),
                node("agent-1", "agent", "coordinator",
                        Map.of("name", "Lead", "systemPrompt", "Lead the work."))));
        nodes.addAll(List.of(extra));
        return nodes;
    }

    private static FlowEdge edge(String id, String from, String to) {
        return new FlowEdge(id, from, to);
    }

    @Test
    void a_flow_wired_into_an_agent_runs_before_it() {
        FlowGraph graph = flow(
                base(node("sub-1", "flow", null, Map.of("label", "Datos", "flowId", "flow_child"))),
                List.of(edge("e1", "in-1", "agent-1"), edge("e2", "sub-1", "agent-1")));

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.coordinator().subflows).hasSize(1);
        assertThat(compiled.coordinator().subflows.get(0).flowId).isEqualTo("flow_child");
        assertThat(compiled.afterFlows()).isEmpty();
    }

    @Test
    void a_flow_wired_out_of_an_agent_runs_after_it() {
        FlowGraph graph = flow(
                base(node("sub-1", "flow", null, Map.of("label", "Aviso", "flowId", "flow_child"))),
                List.of(edge("e1", "in-1", "agent-1"), edge("e2", "agent-1", "sub-1")));

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.afterFlows()).hasSize(1);
        assertThat(compiled.afterFlows().get(0).label).isEqualTo("Aviso");
        // And it is not a capability: a hand-off is not something the agent may fire early.
        assertThat(compiled.coordinator().subflows).isEmpty();
    }

    @Test
    void the_same_flow_can_be_drawn_on_both_sides() {
        FlowGraph graph = flow(
                base(node("before", "flow", null, Map.of("label", "Datos", "flowId", "flow_child")),
                        node("after", "flow", null, Map.of("label", "Aviso", "flowId", "flow_child"))),
                List.of(edge("e1", "in-1", "agent-1"),
                        edge("e2", "before", "agent-1"),
                        edge("e3", "agent-1", "after")));

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.coordinator().subflows).hasSize(1);
        assertThat(compiled.afterFlows()).hasSize(1);
    }

    @Test
    void a_flow_node_wired_to_nothing_is_refused_rather_than_ignored() {
        // Silently doing nothing is the worst of the three options: the box is on the canvas, so
        // somebody believes it runs.
        FlowGraph graph = flow(
                base(node("sub-1", "flow", null, Map.of("label", "Huérfano", "flowId", "flow_child"))),
                List.of(edge("e1", "in-1", "agent-1")));

        assertThat(catchThrowable(() -> COMPILER.compile(graph)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Huérfano");
    }

    @Test
    void an_mcp_server_drawn_backwards_still_works_because_thousands_of_flows_are_drawn_that_way() {
        // The canvas now refuses to DRAW this, but the shipped templates and every flow saved
        // before today have it: an agent connected to its MCP rather than the other way round.
        // Refusing it here would break them at run time, which is not a trade anybody asked for.
        FlowGraph graph = flow(
                base(node("mcp-1", "mcp", null, Map.of("name", "holded", "url", "https://mcp.example.com/mcp"))),
                List.of(edge("e1", "in-1", "agent-1"), edge("e2", "agent-1", "mcp-1")));

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.coordinator().mcpServers).hasSize(1);
    }
}
