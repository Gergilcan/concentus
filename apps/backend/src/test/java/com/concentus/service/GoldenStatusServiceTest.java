package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.GoldenStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoldenStatusService}: what counts as "the flow changed since the run I
 * trust", and the three conditions that must hold before a save spends money on a check.
 */
class GoldenStatusServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunService runs = mock(RunService.class);
    private final GoldenStatusService service = new GoldenStatusService(runs, mapper);

    private static FlowGraph flow(String id, List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph(id, "Flow", nodes, edges, null, null, null, null, null);
    }

    private static FlowNode agent(String id, String prompt, int x) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Coord");
        data.put("systemPrompt", prompt);
        data.put("_pos", Map.of("x", x, "y", 100));
        return new FlowNode(id, "agent", "coordinator", data);
    }

    private static FlowGraph simple(String prompt, int x) {
        return flow("f1", List.of(agent("a1", prompt, x)), List.of(new FlowEdge("e1", "in", "a1")));
    }

    /** Registers a golden run for f1 whose snapshot is the given flow. */
    private AgentRun golden(FlowGraph executed) throws Exception {
        AgentRun run = new AgentRun("run_g", "f1", "Flow");
        run.golden = true;
        run.flowJson = mapper.writeValueAsString(executed);
        when(runs.list()).thenReturn(List.of(run.toSummary()));
        when(runs.get("run_g")).thenReturn(Optional.of(run));
        return run;
    }

    // ---------------------------------------------------------------- what counts as a change

    @Test
    void movingANodeOnTheCanvasIsNotAChange() throws Exception {
        // The whole reason a signature exists rather than a JSON comparison: dragging a box two
        // pixels must not claim the flow is untested, or the chip is noise within a day.
        golden(simple("Do the thing.", 100));

        Optional<GoldenStatus> status = service.of(simple("Do the thing.", 480));

        assertThat(status).isPresent();
        assertThat(status.orElseThrow().stale()).isFalse();
    }

    @Test
    void rewritingAPromptIsAChange() throws Exception {
        golden(simple("Do the thing.", 100));

        assertThat(service.of(simple("Do a DIFFERENT thing.", 100)).orElseThrow().stale()).isTrue();
    }

    @Test
    void rewiringAnEdgeIsAChange() throws Exception {
        golden(simple("Do the thing.", 100));

        FlowGraph rewired = flow("f1", List.of(agent("a1", "Do the thing.", 100)),
                List.of(new FlowEdge("e1", "in", "somewhere-else")));

        assertThat(service.of(rewired).orElseThrow().stale()).isTrue();
    }

    @Test
    void keyOrderInsideNodeDataIsNotAChange() throws Exception {
        // Node data survives a round trip through JSON, and map order is not stable across one.
        FlowNode reordered = new FlowNode("a1", "agent", "coordinator", new java.util.LinkedHashMap<>(
                Map.of("systemPrompt", "Do the thing.", "_pos", Map.of("y", 100, "x", 100), "name", "Coord")));
        golden(simple("Do the thing.", 100));

        FlowGraph same = flow("f1", List.of(reordered), List.of(new FlowEdge("e1", "in", "a1")));

        assertThat(service.of(same).orElseThrow().stale()).isFalse();
    }

    // ---------------------------------------------------------------- no golden, no opinion

    @Test
    void aFlowWithNoGoldenReferenceHasNoStatusAtAll() {
        when(runs.list()).thenReturn(List.of());

        assertThat(service.of(simple("Do the thing.", 100))).isEmpty();
    }

    @Test
    void aGoldenRunWithNoStoredSnapshotIsNeverReportedStale() throws Exception {
        // With nothing to compare against, "changed" would be a guess — and a chip that cries
        // stale on every flow gets ignored.
        AgentRun run = golden(simple("Do the thing.", 100));
        run.flowJson = null;

        assertThat(service.of(simple("Anything else entirely.", 999)).orElseThrow().stale()).isFalse();
    }

    @Test
    void anUnsavedFlowHasNoStatus() {
        assertThat(service.of(flow(null, List.of(), List.of()))).isEmpty();
    }

    // ---------------------------------------------------------------- automatic check on save

    @Test
    void anEditedFlowThatOptedInIsChecked() throws Exception {
        golden(simple("Do the thing.", 100));
        FlowGraph before = simple("Do the thing.", 100);
        FlowGraph after = optIn(simple("Do something else.", 100));

        assertThat(service.autoCheckAfterSave(before, after)).isPresent();
    }

    @Test
    void aFlowThatDidNotOptInIsNeverCheckedAutomatically() throws Exception {
        golden(simple("Do the thing.", 100));

        assertThat(service.autoCheckAfterSave(simple("a", 100), simple("b", 100))).isEmpty();
    }

    @Test
    void aSaveThatChangedNothingBehaviouralSpendsNothing() throws Exception {
        // Re-saving after a rename, a tag edit or a second click on Save: no graph change, no run.
        golden(simple("Do the thing.", 100));
        FlowGraph before = simple("Do the thing.", 100);
        FlowGraph after = optIn(simple("Do the thing.", 480)); // moved only

        assertThat(service.autoCheckAfterSave(before, after)).isEmpty();
    }

    @Test
    void aFirstSaveHasNothingToCompareAgainstAndStartsNothing() throws Exception {
        golden(simple("Do the thing.", 100));

        assertThat(service.autoCheckAfterSave(null, optIn(simple("x", 100)))).isEmpty();
    }

    private static FlowGraph optIn(FlowGraph flow) {
        return new FlowGraph(flow.id(), flow.name(), flow.nodes(), flow.edges(),
                null, null, null, null, null, null, null, null, null, null, true);
    }
}
