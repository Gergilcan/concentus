package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of a block's two outputs a branch hangs off.
 *
 * <p>The distinction decides whether a hand-off runs when the flow worked or when it did not, so
 * getting it wrong is not a cosmetic error: it is a recovery branch that never fires, or a report
 * that goes out claiming success over a failure.
 */
class ErrorPathTest {

    private static FlowNode node(String id, String type) {
        return new FlowNode(id, type, null, Map.of());
    }

    /** Only the two components this cares about; a graph carries a dozen more that do not matter here. */
    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("f1", "Flow", "managed", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aBranchOnTheMainOutputIsNotAnErrorPath() {
        var flow = graph(
                List.of(node("agent", "agent"), node("next", "flow")),
                List.of(new FlowEdge("e1", "agent", "next")));

        assertThat(FlowGates.reachedByErrorPath(flow, "next")).isFalse();
    }

    @Test
    void aBranchOnTheErrorOutputIs() {
        var flow = graph(
                List.of(node("agent", "agent"), node("recover", "flow")),
                List.of(new FlowEdge("e1", "agent", "recover", FlowEdge.ERROR)));

        assertThat(FlowGates.reachedByErrorPath(flow, "recover")).isTrue();
    }

    @Test
    void aFlowDrawnBeforeOutputsHadNamesRunsOnSuccess() {
        // Every stored edge has null there. Reading that as an error path would stop every
        // hand-off anybody has ever drawn from firing, which is the worst possible upgrade.
        var flow = graph(
                List.of(node("agent", "agent"), node("next", "flow")),
                List.of(new FlowEdge("e1", "agent", "next", null)));

        assertThat(FlowGates.reachedByErrorPath(flow, "next")).isFalse();
    }

    @Test
    void aGateBetweenThemDoesNotChangeWhichOutputItHangsOff() {
        // A condition drawn between an agent and its recovery branch decides whether the branch
        // fires, not which of the agent's outputs it belongs to.
        var flow = graph(
                List.of(node("agent", "agent"), node("if", "condition"), node("recover", "flow")),
                List.of(
                        new FlowEdge("e1", "agent", "if", FlowEdge.ERROR),
                        new FlowEdge("e2", "if", "recover")));

        assertThat(FlowGates.reachedByErrorPath(flow, "recover")).isTrue();
    }

    @Test
    void anEdgeIsMainUntilItSaysOtherwise() {
        assertThat(new FlowEdge("e", "a", "b").onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", null).onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", "").onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", FlowEdge.ERROR).onMainOutput()).isFalse();
    }
}
