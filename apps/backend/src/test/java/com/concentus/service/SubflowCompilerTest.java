package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A flow that runs another flow.
 *
 * <p>Two shapes, because the two answer different questions. Wired to an agent, a flow node is a
 * capability — the agent decides whether calling it is worth it, the way it decides about any
 * other tool. Left terminal, it is a hand-off: when this run finishes, that one starts, no
 * judgement involved. Modelling both as one node type keeps the graph honest about what they share
 * — which flow, and whether to wait for it.
 */
class SubflowCompilerTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    private static FlowGraph flowWith(FlowNode... nodes) {
        return new FlowGraph("parent", "Parent", "local", List.of(nodes),
                List.of(new FlowEdge("e1", "in-1", "agent-1"),
                        new FlowEdge("e2", "sub-1", "agent-1")),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static FlowNode input() {
        return node("in-1", "input", null, Map.of("mode", "manual"));
    }

    private static FlowNode coordinator() {
        return node("agent-1", "agent", "coordinator",
                Map.of("name", "Lead", "systemPrompt", "Lead the work."));
    }

    @Test
    void a_flow_node_wired_to_an_agent_becomes_one_of_its_capabilities() {
        FlowGraph flow = flowWith(input(), coordinator(),
                node("sub-1", "flow", null, Map.of(
                        "label", "Weekly report",
                        "flowId", "flow_child",
                        "mode", "tool",
                        "waitForResult", true)));

        CompiledFlow compiled = COMPILER.compile(flow);

        assertThat(compiled.coordinator().subflows).hasSize(1);
        AgentSpec.SubflowSpec spec = compiled.coordinator().subflows.get(0);
        assertThat(spec.flowId).isEqualTo("flow_child");
        assertThat(spec.label).isEqualTo("Weekly report");
        assertThat(spec.waitForResult).isTrue();
        // A capability is not a hand-off: nothing fires on its own when the run ends.
        assertThat(compiled.afterFlows()).isEmpty();
    }

    @Test
    void a_terminal_flow_node_is_a_hand_off_rather_than_a_tool() {
        FlowGraph flow = flowWith(input(), coordinator(),
                node("sub-1", "flow", null, Map.of(
                        "label", "Notify the team",
                        "flowId", "flow_child",
                        "mode", "after",
                        "waitForResult", false)));

        CompiledFlow compiled = COMPILER.compile(flow);

        assertThat(compiled.afterFlows()).hasSize(1);
        assertThat(compiled.afterFlows().get(0).flowId).isEqualTo("flow_child");
        assertThat(compiled.afterFlows().get(0).waitForResult).isFalse();
        // And it is not offered as a tool: the agent cannot fire a hand-off early.
        assertThat(compiled.coordinator().subflows).isEmpty();
    }

    @Test
    void waiting_for_the_child_is_the_default_because_the_answer_is_usually_the_point() {
        FlowGraph flow = flowWith(input(), coordinator(),
                node("sub-1", "flow", null, Map.of("flowId", "flow_child", "mode", "tool")));

        assertThat(COMPILER.compile(flow).coordinator().subflows.get(0).waitForResult).isTrue();
    }

    @Test
    void a_flow_node_with_no_flow_chosen_yet_is_refused_rather_than_compiled() {
        FlowGraph flow = flowWith(input(), coordinator(),
                node("sub-1", "flow", null, Map.of("label", "Unfinished", "mode", "tool")));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> COMPILER.compile(flow)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unfinished");
    }

    @Test
    void a_flow_that_runs_itself_is_refused_at_compile_time() {
        // The run-time guard catches the indirect case; this catches the one anybody can draw by
        // accident, and catches it while they are looking at the canvas.
        FlowGraph flow = flowWith(input(), coordinator(),
                node("sub-1", "flow", null, Map.of("label", "Me", "flowId", "parent", "mode", "tool")));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> COMPILER.compile(flow)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }
}
