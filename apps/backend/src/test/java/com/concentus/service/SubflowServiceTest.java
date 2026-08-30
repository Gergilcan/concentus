package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.store.FlowStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Running one flow from another, and the three ways that goes wrong.
 *
 * <p>A sub-flow is the first thing in the product that can start work without a person asking, so
 * it is also the first that can start work forever. The guards are not decoration: a cycle drawn
 * across two flows is invisible on either canvas, and the machine that notices is this one.
 */
class SubflowServiceTest {

    private final FlowStore flows = mock(FlowStore.class);
    private final RunService runs = mock(RunService.class);

    private SubflowService service() {
        return service(3);
    }

    private SubflowService service(int maxDepth) {
        return new SubflowService(flows, () -> runs, maxDepth);
    }

    private static AgentRun parentRun(String flowId, List<String> ancestors) {
        AgentRun run = new AgentRun("run-parent", flowId, "Parent");
        run.organizationId = "default";
        run.flowChain = ancestors;
        return run;
    }

    private static AgentSpec.SubflowSpec spec(String flowId, boolean wait) {
        AgentSpec.SubflowSpec s = new AgentSpec.SubflowSpec();
        s.nodeId = "sub-1";
        s.label = "Child";
        s.flowId = flowId;
        s.waitForResult = wait;
        return s;
    }

    private void childExists(String id) {
        FlowGraph child = new FlowGraph(id, "Child", List.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
        when(flows.getIn("default", id)).thenReturn(Optional.of(child));
    }

    private static RunSummary summary(String id, String status) {
        return new RunSummary(id, "flow_child", "Child", status, 0L, null,
                List.of(), null, "subflow", 0, 0, 0, false, 0);
    }

    @Test
    void a_hand_off_that_nobody_waits_for_reports_the_run_it_started() {
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-child", "RUNNING"));

        SubflowService.Result result = service().run(parentRun("flow_parent", List.of()),
                spec("flow_child", false), "Do the thing");

        assertThat(result.started()).isTrue();
        assertThat(result.runId()).isEqualTo("run-child");
        assertThat(result.output()).isNull();
    }

    @Test
    void waiting_returns_what_the_child_answered() {
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-child", "RUNNING"));
        AgentRun childRun = new AgentRun("run-child", "flow_child", "Child");
        childRun.status = "COMPLETED";
        childRun.restoreEvents(List.of(
                com.concentus.model.RunEvent.of("agent_message", "Eleven leads this week.")));
        when(runs.get("run-child")).thenReturn(Optional.of(childRun));

        SubflowService.Result result = service().run(parentRun("flow_parent", List.of()),
                spec("flow_child", true), "Count the leads");

        assertThat(result.output()).isEqualTo("Eleven leads this week.");
        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    @Test
    void a_flow_already_in_the_chain_is_refused_rather_than_run_again() {
        // A runs B, B runs A. Neither canvas shows the loop; only the chain does.
        childExists("flow_a");

        SubflowService.Result result = service().run(parentRun("flow_b", List.of("flow_a")),
                spec("flow_a", true), "go");

        assertThat(result.started()).isFalse();
        assertThat(result.error()).contains("already running");
        verify(runs, never()).startSubflow(any(), anyString(), any());
    }

    @Test
    void the_chain_stops_at_the_configured_depth() {
        childExists("flow_child");

        SubflowService.Result result = service(1).run(parentRun("flow_parent", List.of("flow_root")),
                spec("flow_child", true), "go");

        assertThat(result.started()).isFalse();
        assertThat(result.error()).contains("deep");
        verify(runs, never()).startSubflow(any(), anyString(), any());
    }

    @Test
    void a_flow_that_was_deleted_says_so_instead_of_failing_obscurely() {
        when(flows.getIn("default", "flow_gone")).thenReturn(Optional.empty());

        SubflowService.Result result = service().run(parentRun("flow_parent", List.of()),
                spec("flow_gone", true), "go");

        assertThat(result.started()).isFalse();
        assertThat(result.error()).contains("no longer exists");
    }

    @Test
    void a_child_refused_by_its_own_budget_reports_that_reason_verbatim() {
        // The child's ceiling is the child's, and a parent must not spend through it.
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("Budget reached: 'Child' has spent $5.00 of its $5.00 monthly ceiling."));

        SubflowService.Result result = service().run(parentRun("flow_parent", List.of()),
                spec("flow_child", true), "go");

        assertThat(result.started()).isFalse();
        assertThat(result.error()).contains("Budget reached");
    }

    @Test
    void hand_offs_fire_when_the_run_completed() {
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-child", "RUNNING"));
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = "COMPLETED";
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Done: 11 leads.")));
        parent.compiled = new CompiledFlow(new AgentSpec(), List.of(), null, null,
                List.of(spec("flow_child", true)));

        service().handOffAfter(parent);

        // The child is handed the parent's closing answer, and nobody waits for it: the run that
        // would have read the reply is already over.
        verify(runs).startSubflow(any(), org.mockito.ArgumentMatchers.eq("Done: 11 leads."), any());
    }

    @Test
    void a_run_that_failed_with_nothing_on_its_error_output_hands_nothing_on() {
        // A hand-off on the MAIN output exists because the first flow's result is the second
        // one's reason. After a failure there is no result, and passing on the absence would act
        // on nothing.
        childExists("flow_child");
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = "ERROR";
        parent.compiled = new CompiledFlow(new AgentSpec(), List.of(), null, null,
                List.of(spec("flow_child", true)));

        service().handOffAfter(parent);

        verify(runs, never()).startSubflow(any(), anyString(), any());
        assertThat(parent.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("did not complete"));
        // And it stays a failure: nothing handled it.
        assertThat(parent.status).isEqualTo("ERROR");
    }

    /** agent → hand-off, where the hand-off hangs off the agent's error output. */
    private static FlowGraph graphWithErrorHandOff(String flowId, String childId) {
        return new FlowGraph(flowId, "Parent",
                List.of(
                        new com.concentus.model.FlowNode("a", "agent", "coordinator", java.util.Map.of()),
                        new com.concentus.model.FlowNode("sub-1", "flow", null,
                                java.util.Map.of("flowId", childId))),
                List.of(new com.concentus.model.FlowEdge("e1", "a", "sub-1",
                        com.concentus.model.FlowEdge.ERROR)),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void a_failed_run_fires_the_branch_wired_to_its_error_output_and_is_handled() {
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-rescue", "RUNNING"));
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = "ERROR";
        parent.error = "the mailbox refused the credential";
        parent.compiled = new CompiledFlow(new AgentSpec(), List.of(), null, null,
                List.of(spec("flow_child", true)));

        service().handOffAfter(parent, graphWithErrorHandOff("flow_parent", "flow_child"));

        // The branch is handed the failure itself — the one thing that certainly exists — and
        // the run completes: somebody drew what should happen when this goes wrong, and it
        // happened. That is precisely what "handled" means.
        verify(runs).startSubflow(any(),
                org.mockito.ArgumentMatchers.contains("the mailbox refused the credential"), any());
        assertThat(parent.status).isEqualTo("COMPLETED");
    }

    @Test
    void a_run_that_completed_does_not_fire_the_error_branch() {
        childExists("flow_child");
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = "COMPLETED";
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Done.")));
        parent.compiled = new CompiledFlow(new AgentSpec(), List.of(), null, null,
                List.of(spec("flow_child", true)));

        service().handOffAfter(parent, graphWithErrorHandOff("flow_parent", "flow_child"));

        // The recovery branch is FOR failures. Running it on success would send the "it broke"
        // notification precisely when nothing broke.
        verify(runs, never()).startSubflow(any(), anyString(), any());
    }

    @Test
    void hand_offs_fire_once_per_run_however_many_turns_it_has() {
        // A finished run goes back to RUNNING when someone sends it another message, and lands on
        // COMPLETED again at the end of that turn. Without this guard the second turn would start
        // the same hand-off a second time — the email sent twice, the invoice raised twice.
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-child", "RUNNING"));
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = "COMPLETED";
        parent.compiled = new CompiledFlow(new AgentSpec(), List.of(), null, null,
                List.of(spec("flow_child", true)));

        SubflowService service = service();
        service.handOffAfter(parent);
        service.handOffAfter(parent);

        verify(runs, org.mockito.Mockito.times(1)).startSubflow(any(), anyString(), any());
    }

    // ------------------------------------------------------------ per-block outputs

    private static AgentSpec agent(String nodeId, String name) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        return s;
    }

    /**
     * coordinator a → workers w1, w2 → verifier v; and one hand-off hanging off {@code handle} of
     * {@code source}.
     */
    private static FlowGraph fanoutGraph(String source, String handle) {
        return new FlowGraph("flow_parent", "Parent",
                List.of(
                        new com.concentus.model.FlowNode("a", "agent", "coordinator", java.util.Map.of()),
                        new com.concentus.model.FlowNode("w1", "agent", "subagent", java.util.Map.of()),
                        new com.concentus.model.FlowNode("w2", "agent", "subagent", java.util.Map.of()),
                        new com.concentus.model.FlowNode("v", "verifier", null, java.util.Map.of()),
                        new com.concentus.model.FlowNode("sub-1", "flow", null,
                                java.util.Map.of("flowId", "flow_child"))),
                List.of(
                        new com.concentus.model.FlowEdge("e1", "a", "w1"),
                        new com.concentus.model.FlowEdge("e2", "a", "w2"),
                        new com.concentus.model.FlowEdge("e3", source, "sub-1", handle)),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private AgentRun fanoutRun(String status) {
        childExists("flow_child");
        when(runs.startSubflow(any(), anyString(), any())).thenReturn(summary("run-branch", "RUNNING"));
        AgentRun parent = parentRun("flow_parent", List.of());
        parent.status = status;
        parent.compiled = new CompiledFlow(agent("a", "Planner"),
                List.of(agent("w1", "Ads writer"), agent("w2", "Ads reviewer")),
                null, agent("v", "Judge"), List.of(spec("flow_child", true)));
        return parent;
    }

    @Test
    void the_error_branch_of_a_block_fires_when_that_block_failed_even_though_the_run_completed() {
        AgentRun parent = fanoutRun("COMPLETED");
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Merged.")));
        com.concentus.model.NodeExec w1 = parent.nodeExec("w1", "agent", "Ads writer");
        w1.status = "failed";
        w1.error = "timed out after 10 minutes";
        parent.restoreEvents(List.of(new com.concentus.model.RunEvent("error", "killed", "Ads writer", "w1", 0L)));

        service().handOffAfter(parent, fanoutGraph("w1", com.concentus.model.FlowEdge.ERROR));

        // The other workers carried the run home; this one still crashed, and the branch drawn
        // for exactly that is the only way anybody hears about it.
        verify(runs).startSubflow(any(),
                org.mockito.ArgumentMatchers.argThat(p -> p.startsWith("Ads writer failed: timed out after 10 minutes")
                        && p.contains("## Log — Ads writer") && p.contains("killed")), any());
        assertThat(parent.status).isEqualTo("COMPLETED");
    }

    @Test
    void the_error_branch_of_a_block_does_not_fire_for_another_blocks_failure() {
        AgentRun parent = fanoutRun("ERROR");
        parent.error = "timed out";
        com.concentus.model.NodeExec w2 = parent.nodeExec("w2", "agent", "Ads reviewer");
        w2.status = "failed";
        w2.error = "timed out";

        service().handOffAfter(parent, fanoutGraph("w1", com.concentus.model.FlowEdge.ERROR));

        // Wired to w1, and w1 is fine. The old rule fired this on any failure, which made an
        // error wire mean "something, somewhere" — the drawing says whose.
        verify(runs, never()).startSubflow(any(), anyString(), any());
        assertThat(parent.status).isEqualTo("ERROR");
        assertThat(parent.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("'Ads reviewer' failed and nothing is wired to its error output"));
    }

    @Test
    void a_failure_nobody_pinned_on_a_block_fires_the_coordinators_error_branch() {
        AgentRun parent = fanoutRun("ERROR");
        parent.error = "Every worker failed. The combined report lists each reason.";

        service().handOffAfter(parent, fanoutGraph("a", com.concentus.model.FlowEdge.ERROR));

        verify(runs).startSubflow(any(),
                org.mockito.ArgumentMatchers.startsWith("Every worker failed."), any());
        assertThat(parent.status).isEqualTo("COMPLETED");
    }

    @Test
    void a_failure_nobody_pinned_on_a_block_does_not_fire_a_workers_error_branch() {
        AgentRun parent = fanoutRun("ERROR");
        parent.error = "Every worker failed. The combined report lists each reason.";

        service().handOffAfter(parent, fanoutGraph("w1", com.concentus.model.FlowEdge.ERROR));

        verify(runs, never()).startSubflow(any(), anyString(), any());
    }

    @Test
    void the_verifiers_rejected_branch_fires_once_with_the_whole_report() {
        AgentRun parent = fanoutRun("COMPLETED");
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Merged.")));
        com.concentus.model.NodeExec w1 = parent.nodeExec("w1", "agent", "Ads writer");
        w1.status = "passed";
        w1.verdict = "accepted";
        w1.output = "Three headlines.";
        com.concentus.model.NodeExec w2 = parent.nodeExec("w2", "agent", "Ads reviewer");
        w2.status = "passed";
        w2.verdict = "rejected";
        w2.verdictReason = "Cites a CTR that appears in no file.";
        w2.output = "CTR is 12%.";

        service().handOffAfter(parent, fanoutGraph("v", com.concentus.model.FlowEdge.REJECTED));

        verify(runs, org.mockito.Mockito.times(1)).startSubflow(any(),
                org.mockito.ArgumentMatchers.argThat(p -> p.startsWith("# Verification report — Parent")
                        && p.contains("## ✖ Ads reviewer — REJECTED")
                        && p.contains("Cites a CTR that appears in no file.")
                        && p.contains("## ✔ Ads writer — accepted")), any());
    }

    @Test
    void the_verifiers_rejected_branch_stays_quiet_when_everything_was_accepted() {
        AgentRun parent = fanoutRun("COMPLETED");
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Merged.")));
        parent.nodeExec("w1", "agent", "Ads writer").verdict = "accepted";
        parent.nodeExec("w2", "agent", "Ads reviewer").verdict = "accepted";

        service().handOffAfter(parent, fanoutGraph("v", com.concentus.model.FlowEdge.REJECTED));

        verify(runs, never()).startSubflow(any(), anyString(), any());
    }

    @Test
    void a_settled_block_fires_its_own_branch_mid_run_and_the_end_of_the_run_does_not_repeat_it() {
        AgentRun parent = fanoutRun("RUNNING");
        com.concentus.model.NodeExec w1 = parent.nodeExec("w1", "agent", "Ads writer");
        w1.status = "failed";
        w1.error = "timed out";
        com.concentus.model.FlowGraph graph = fanoutGraph("w1", com.concentus.model.FlowEdge.ERROR);

        SubflowService service = service();
        // The verifier settling says nothing about w1's branch: only the settled block's own.
        service.handOffMidRun(parent, graph, "v");
        verify(runs, never()).startSubflow(any(), anyString(), any());

        service.handOffMidRun(parent, graph, "w1");
        verify(runs, org.mockito.Mockito.times(1)).startSubflow(any(),
                org.mockito.ArgumentMatchers.startsWith("Ads writer failed: timed out"), any());

        // The run goes on and finishes; the branch that already ran is not started twice, and the
        // main hand-offs (none drawn here) are the only thing the end of the run looks at.
        parent.status = "COMPLETED";
        parent.restoreEvents(List.of(com.concentus.model.RunEvent.of("agent_message", "Merged.")));
        service.handOffAfter(parent, graph);
        verify(runs, org.mockito.Mockito.times(1)).startSubflow(any(), anyString(), any());
    }

    @Test
    void a_branch_that_fired_mid_run_still_counts_as_handling_a_run_that_then_failed() {
        AgentRun parent = fanoutRun("RUNNING");
        parent.nodeExec("w1", "agent", "Ads writer").verdict = "rejected";
        parent.nodeExec("w2", "agent", "Ads reviewer").verdict = "rejected";
        com.concentus.model.FlowGraph graph = fanoutGraph("v", com.concentus.model.FlowEdge.REJECTED);

        SubflowService service = service();
        service.handOffMidRun(parent, graph, "v");
        verify(runs).startSubflow(any(), org.mockito.ArgumentMatchers.contains("# Verification report"), any());

        parent.status = "ERROR";
        parent.error = "The verifier rejected every worker's output — nothing survived to merge.";
        service.handOffAfter(parent, graph);

        verify(runs, org.mockito.Mockito.times(1)).startSubflow(any(), anyString(), any());
        assertThat(parent.status).isEqualTo("COMPLETED");
    }

    @Test
    void a_run_the_verifier_rejected_entirely_is_handled_by_its_rejected_branch() {
        AgentRun parent = fanoutRun("ERROR");
        parent.error = "The verifier rejected every worker's output — nothing survived to merge.";
        parent.nodeExec("w1", "agent", "Ads writer").verdict = "rejected";
        parent.nodeExec("w2", "agent", "Ads reviewer").verdict = "rejected";

        service().handOffAfter(parent, fanoutGraph("v", com.concentus.model.FlowEdge.REJECTED));

        verify(runs).startSubflow(any(), org.mockito.ArgumentMatchers.contains("Rejected 2 of 2 worker(s)."), any());
        // Somebody drew what should happen when the verifier kills everything, and it happened.
        assertThat(parent.status).isEqualTo("COMPLETED");
    }
}