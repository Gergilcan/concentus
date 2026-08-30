package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A condition's two outputs: the branch on the main one runs when the test holds, the branch on
 * the else output runs when it does not — the same rule, read from the other side.
 *
 * <p>Before this, "if it is urgent do X, otherwise do Y" needed two condition nodes with opposite
 * tests, and the two could drift: someone edits one and forgets the other, and there is an input
 * both branches silently agree to drop.
 */
class ElseBranchTest {

    private static FlowNode agent(String id) {
        return new FlowNode(id, "agent", null, Map.of());
    }

    private static FlowNode flowNode(String id) {
        return new FlowNode(id, "flow", null, Map.of());
    }

    private static FlowNode condition(String id, String test, String value) {
        return new FlowNode(id, "condition", null, Map.of("test", test, "value", value));
    }

    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("f1", "Flow", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** agent → if(contains "urgente") → yes-branch; else output → no-branch. */
    private static FlowGraph ifElse() {
        return graph(
                List.of(agent("a"), condition("if", "contains", "urgente"),
                        flowNode("yes"), flowNode("no")),
                List.of(
                        new FlowEdge("e1", "a", "if"),
                        new FlowEdge("e2", "if", "yes"),
                        new FlowEdge("e3", "if", "no", FlowEdge.ELSE)));
    }

    @Test
    void theMainBranchRunsWhenTheTestHolds() {
        var decision = FlowGates.decide(ifElse(), "yes", "pedido urgente del cliente");

        assertThat(decision.fires()).isTrue();
        assertThat(decision.prompts()).containsExactly("pedido urgente del cliente");
    }

    @Test
    void theElseBranchRunsWhenItDoesNot() {
        var flow = ifElse();

        // The same input, judged for both branches: exactly one of them fires. That is the whole
        // contract of an if/else, and the thing two hand-written opposite tests could not promise.
        assertThat(FlowGates.decide(flow, "yes", "consulta normal").fires()).isFalse();
        assertThat(FlowGates.decide(flow, "no", "consulta normal").fires()).isTrue();

        assertThat(FlowGates.decide(flow, "yes", "esto es urgente").fires()).isTrue();
        assertThat(FlowGates.decide(flow, "no", "esto es urgente").fires()).isFalse();
    }

    @Test
    void theElseBranchSaysWhichSideItIs() {
        var decision = FlowGates.decide(ifElse(), "no", "esto es urgente");

        // A branch that did not run must say why in words that name the side, or the log reads
        // as the condition failing twice.
        assertThat(decision.reason()).contains("else branch");
    }

    @Test
    void afterAForEachTheElseKeepsTheRejectedItems() {
        // for-each splits, the condition keeps matching items on one side — and the rest are not
        // dropped on the floor any more: they are the else branch's items.
        var flow = graph(
                List.of(agent("a"),
                        new FlowNode("each", "foreach", null, Map.of("split", "lines")),
                        condition("if", "contains", "prod"),
                        flowNode("deploy"), flowNode("skip")),
                List.of(
                        new FlowEdge("e1", "a", "each"),
                        new FlowEdge("e2", "each", "if"),
                        new FlowEdge("e3", "if", "deploy"),
                        new FlowEdge("e4", "if", "skip", FlowEdge.ELSE)));

        String output = "prod-eu\nstaging\nprod-us\ndev";
        assertThat(FlowGates.decide(flow, "deploy", output).prompts())
                .containsExactly("prod-eu", "prod-us");
        assertThat(FlowGates.decide(flow, "skip", output).prompts())
                .containsExactly("staging", "dev");
    }
}
