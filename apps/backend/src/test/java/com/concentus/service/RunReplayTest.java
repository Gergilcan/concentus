package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.NodeExec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replaying a run's recorded outputs against the flow as drawn today.
 *
 * <p>The contract under test: routing decisions are re-taken from recorded text, nothing is ever
 * invented (missing output ⇒ unknown, not a guess), and a divergence is exactly a block whose
 * fate flips — ran-then/would-skip-now, skipped-then/would-run-now, ran-then/gone-now, or a new
 * block that recorded output would fire.
 */
class RunReplayTest {

    private static FlowNode agent(String id, String name) {
        return new FlowNode(id, "agent", "coordinator", Map.of("name", name));
    }

    private static FlowNode condition(String id, String test, String value) {
        return new FlowNode(id, "condition", null,
                Map.of("label", "if", "test", test, "value", value));
    }

    private static FlowNode handOff(String id) {
        return new FlowNode(id, "flow", null, Map.of("label", "notify", "flowId", "flow_2"));
    }

    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("flow_1", "Reporting", "local", nodes, edges,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** agent → condition → hand-off, with the condition's test supplied per side. */
    private static FlowGraph gated(String test, String value) {
        return graph(
                List.of(agent("a1", "Reporter"), condition("c1", test, value), handOff("f1")),
                List.of(new FlowEdge("e1", "a1", "c1"), new FlowEdge("e2", "c1", "f1")));
    }

    private static AgentRun runWhere(String agentOutput, String handOffStatus) {
        AgentRun run = new AgentRun("run_1", "flow_1", "Reporting", "local");
        NodeExec a = run.nodeExec("a1", "agent", "Reporter");
        a.status = "passed";
        a.appendOutput(agentOutput);
        if (handOffStatus != null) {
            NodeExec f = run.nodeExec("f1", "flow", "notify");
            f.status = handOffStatus;
        }
        return run;
    }

    private static RunReplay.ReplayNode nodeOf(RunReplay.ReplayReport report, String id) {
        return report.nodes().stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void an_unchanged_flow_replays_with_no_divergence() {
        FlowGraph flow = gated("contains", "ok");
        RunReplay.ReplayReport report =
                RunReplay.compare(runWhere("all ok", "passed"), flow, flow);

        assertThat(report.divergences()).isZero();
        assertThat(nodeOf(report, "f1").now()).isEqualTo("would-run");
        assertThat(nodeOf(report, "f1").then()).isEqualTo("ran");
    }

    @Test
    void a_note_added_since_the_run_is_not_a_divergence_and_not_a_block() {
        // The flow was documented after the run: a note and a frame appeared. Nothing about what
        // the run would do changed, so the replay must say nothing — listed, a note with no exec
        // would read as "skipped then, would run now" and be counted.
        FlowGraph then = gated("contains", "ok");
        FlowGraph now = graph(
                List.of(agent("a1", "Reporter"), condition("c1", "contains", "ok"), handOff("f1"),
                        new FlowNode("n1", "note", null, Map.of("text", "Nightly", "color", "yellow")),
                        new FlowNode("g1", "group", null, Map.of("label", "Report", "color", "blue"))),
                List.of(new FlowEdge("e1", "a1", "c1"), new FlowEdge("e2", "c1", "f1")));

        RunReplay.ReplayReport report = RunReplay.compare(runWhere("all ok", "passed"), then, now);

        assertThat(report.divergences()).isZero();
        assertThat(report.nodes()).extracting(RunReplay.ReplayNode::nodeId)
                .containsExactlyInAnyOrder("a1", "c1", "f1");

        // And a note removed since the run is not "a block that ran and is gone" either.
        assertThat(RunReplay.compare(runWhere("all ok", "passed"), now, then).divergences()).isZero();
    }

    @Test
    void an_edited_condition_that_flips_the_branch_is_the_divergence() {
        // The run went through when the gate looked for "ok"; today it looks for "ERROR".
        RunReplay.ReplayReport report = RunReplay.compare(
                runWhere("all ok", "passed"), gated("contains", "ok"), gated("contains", "ERROR"));

        RunReplay.ReplayNode f1 = nodeOf(report, "f1");
        assertThat(f1.then()).isEqualTo("ran");
        assertThat(f1.now()).isEqualTo("would-skip");
        assertThat(f1.divergent()).isTrue();
        assertThat(report.divergences()).isEqualTo(1);
    }

    @Test
    void a_condition_edited_the_other_way_makes_a_skipped_branch_fire() {
        // The gate rejected the output then; edited, it would let the same text through now.
        RunReplay.ReplayReport report = RunReplay.compare(
                runWhere("all ok", null), gated("contains", "ERROR"), gated("contains", "ok"));

        RunReplay.ReplayNode f1 = nodeOf(report, "f1");
        assertThat(f1.then()).isEqualTo("skipped");
        assertThat(f1.now()).isEqualTo("would-run");
        assertThat(f1.divergent()).isTrue();
    }

    @Test
    void a_deleted_block_that_ran_is_reported_gone_and_divergent() {
        FlowGraph now = graph(List.of(agent("a1", "Reporter")), List.of());
        RunReplay.ReplayReport report =
                RunReplay.compare(runWhere("all ok", "passed"), gated("contains", "ok"), now);

        RunReplay.ReplayNode f1 = nodeOf(report, "f1");
        assertThat(f1.now()).isEqualTo("gone");
        assertThat(f1.divergent()).isTrue();
    }

    @Test
    void a_new_block_the_recorded_output_would_fire_is_divergent_too() {
        FlowGraph then = graph(List.of(agent("a1", "Reporter")), List.of());
        FlowGraph now = graph(
                List.of(agent("a1", "Reporter"), handOff("f9")),
                List.of(new FlowEdge("e9", "a1", "f9")));
        RunReplay.ReplayReport report = RunReplay.compare(runWhere("all ok", null), then, now);

        RunReplay.ReplayNode f9 = nodeOf(report, "f9");
        assertThat(f9.then()).isEqualTo("absent");
        assertThat(f9.now()).isEqualTo("would-run");
        assertThat(f9.divergent()).isTrue();
    }

    @Test
    void a_branch_on_the_error_output_would_not_fire_when_the_source_passed() {
        FlowGraph flow = graph(
                List.of(agent("a1", "Reporter"), handOff("f1")),
                List.of(new FlowEdge("e1", "a1", "f1", FlowEdge.ERROR)));
        RunReplay.ReplayReport report = RunReplay.compare(runWhere("all ok", null), flow, flow);

        RunReplay.ReplayNode f1 = nodeOf(report, "f1");
        assertThat(f1.now()).isEqualTo("would-skip");
        // Skipped then, would skip now: the recovery branch not firing is the same decision twice.
        assertThat(f1.divergent()).isFalse();
        assertThat(report.divergences()).isZero();
    }

    @Test
    void a_source_with_no_recorded_output_answers_unknown_rather_than_guessing() {
        FlowGraph flow = gated("contains", "ok");
        AgentRun run = new AgentRun("run_1", "flow_1", "Reporting", "local");
        RunReplay.ReplayReport report = RunReplay.compare(run, flow, flow);

        RunReplay.ReplayNode f1 = nodeOf(report, "f1");
        assertThat(f1.now()).isEqualTo("unknown");
        assertThat(f1.divergent()).isFalse();
    }
}
