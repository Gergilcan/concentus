package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which output of which block a branch hangs off.
 *
 * <p>The distinction decides whether a hand-off runs when the block worked, when it failed, or
 * when the verifier rejected something — and WHICH block that is. Getting it wrong is not a
 * cosmetic error: it is a recovery branch that never fires, one that fires for another block's
 * failure, or a report that goes out claiming success over a failure.
 */
class ErrorPathTest {

    private static FlowNode node(String id, String type) {
        return new FlowNode(id, type, null, Map.of());
    }

    /** Only the two components this cares about; a graph carries a dozen more that do not matter here. */
    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("f1", "Flow", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aBranchOnTheMainOutputBelongsToItsBlockAndIsNotAnErrorPath() {
        var flow = graph(
                List.of(node("agent", "agent"), node("next", "flow")),
                List.of(new FlowEdge("e1", "agent", "next")));

        FlowGates.Origin origin = FlowGates.originOf(flow, "next");

        assertThat(origin.sourceId()).isEqualTo("agent");
        assertThat(origin.onMain()).isTrue();
        assertThat(origin.is(FlowEdge.ERROR)).isFalse();
    }

    @Test
    void aBranchOnTheErrorOutputIs() {
        var flow = graph(
                List.of(node("agent", "agent"), node("recover", "flow")),
                List.of(new FlowEdge("e1", "agent", "recover", FlowEdge.ERROR)));

        FlowGates.Origin origin = FlowGates.originOf(flow, "recover");

        assertThat(origin.sourceId()).isEqualTo("agent");
        assertThat(origin.is(FlowEdge.ERROR)).isTrue();
        assertThat(origin.onMain()).isFalse();
    }

    @Test
    void aBranchOnTheVerifiersRejectedOutputIs() {
        var flow = graph(
                List.of(node("verifier", "verifier"), node("report", "flow")),
                List.of(new FlowEdge("e1", "verifier", "report", FlowEdge.REJECTED)));

        FlowGates.Origin origin = FlowGates.originOf(flow, "report");

        assertThat(origin.sourceId()).isEqualTo("verifier");
        assertThat(origin.is(FlowEdge.REJECTED)).isTrue();
        assertThat(origin.is(FlowEdge.ERROR)).isFalse();
    }

    @Test
    void aFlowDrawnBeforeOutputsHadNamesRunsOnSuccess() {
        // Every stored edge has null there. Reading that as an error path would stop every
        // hand-off anybody has ever drawn from firing, which is the worst possible upgrade.
        var flow = graph(
                List.of(node("agent", "agent"), node("next", "flow")),
                List.of(new FlowEdge("e1", "agent", "next", null), new FlowEdge("e2", "agent", "other", "")));

        assertThat(FlowGates.originOf(flow, "next").onMain()).isTrue();
        assertThat(FlowGates.originOf(flow, "other").onMain()).isTrue();
    }

    @Test
    void aGateBetweenThemDoesNotChangeWhichOutputOrWhichBlockItHangsOff() {
        // A condition drawn between an agent and its recovery branch decides whether the branch
        // fires, not which of the agent's outputs it belongs to — nor whose block it is.
        var flow = graph(
                List.of(node("agent", "agent"), node("if", "condition"), node("recover", "flow")),
                List.of(
                        new FlowEdge("e1", "agent", "if", FlowEdge.ERROR),
                        new FlowEdge("e2", "if", "recover")));

        FlowGates.Origin origin = FlowGates.originOf(flow, "recover");

        assertThat(origin.sourceId()).isEqualTo("agent");
        assertThat(origin.is(FlowEdge.ERROR)).isTrue();
    }

    @Test
    void aGatesOwnElseOutputIsTheGatesAnswerNotTheBlocksOutput() {
        // agent → if, and the branch hangs off the gate's ELSE. The block's output is still its
        // main one: "else" says which way the test went, and FlowGates.decide reads that.
        var flow = graph(
                List.of(node("agent", "agent"), node("if", "condition"), node("recover", "flow")),
                List.of(
                        new FlowEdge("e1", "agent", "if"),
                        new FlowEdge("e2", "if", "recover", FlowEdge.ELSE)));

        FlowGates.Origin origin = FlowGates.originOf(flow, "recover");

        assertThat(origin.sourceId()).isEqualTo("agent");
        assertThat(origin.onMain()).isTrue();
    }

    @Test
    void aBranchNothingFeedsHasNoOrigin() {
        var flow = graph(List.of(node("agent", "agent"), node("loose", "flow")), List.of());

        FlowGates.Origin origin = FlowGates.originOf(flow, "loose");

        assertThat(origin.sourceId()).isNull();
        assertThat(origin.onMain()).isTrue();
    }

    @Test
    void anEdgeIsMainUntilItSaysOtherwise() {
        assertThat(new FlowEdge("e", "a", "b").onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", null).onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", "").onMainOutput()).isTrue();
        assertThat(new FlowEdge("e", "a", "b", FlowEdge.ERROR).onMainOutput()).isFalse();
        assertThat(new FlowEdge("e", "a", "b", FlowEdge.REJECTED).onMainOutput()).isFalse();
    }
}
