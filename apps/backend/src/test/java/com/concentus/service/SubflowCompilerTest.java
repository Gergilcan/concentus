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
 * What a flow node must say before it can run, whichever side it is drawn on.
 *
 * <p>When it runs is decided by the wiring — see {@link SubflowWiringTest}. These are the two
 * mistakes worth refusing while the person is still looking at the canvas, rather than at run time
 * when the answer arrives as a failed execution.
 */
class SubflowCompilerTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    private static FlowGraph flowWith(FlowNode sub) {
        return new FlowGraph("parent", "Parent",
                List.of(node("in-1", "input", null, Map.of("mode", "manual")),
                        node("agent-1", "agent", "coordinator",
                                Map.of("name", "Lead", "systemPrompt", "Lead the work.")),
                        sub),
                List.of(new FlowEdge("e1", "in-1", "agent-1"),
                        new FlowEdge("e2", sub.id(), "agent-1")),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void waiting_for_the_child_is_the_default_because_the_answer_is_usually_the_point() {
        FlowGraph flow = flowWith(node("sub-1", "flow", null, Map.of("flowId", "flow_child")));

        assertThat(COMPILER.compile(flow).coordinator().subflows.get(0).waitForResult).isTrue();
    }

    @Test
    void not_waiting_is_carried_through_when_the_node_says_so() {
        FlowGraph flow = flowWith(node("sub-1", "flow", null,
                Map.of("flowId", "flow_child", "waitForResult", false)));

        assertThat(COMPILER.compile(flow).coordinator().subflows.get(0).waitForResult).isFalse();
    }

    @Test
    void a_flow_node_with_no_flow_chosen_yet_is_refused_rather_than_compiled() {
        FlowGraph flow = flowWith(node("sub-1", "flow", null, Map.of("label", "Unfinished")));

        assertThat(catchThrowable(() -> COMPILER.compile(flow)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unfinished");
    }

    @Test
    void a_flow_that_runs_itself_is_refused_at_compile_time() {
        // The indirect case (A runs B runs A) is caught at run time, where the whole chain is
        // known. This one is catchable while the canvas is still open.
        FlowGraph flow = flowWith(node("sub-1", "flow", null,
                Map.of("label", "Me", "flowId", "parent")));

        assertThat(catchThrowable(() -> COMPILER.compile(flow)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }
}
