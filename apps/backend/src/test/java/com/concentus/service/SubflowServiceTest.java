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
        AgentRun run = new AgentRun("run-parent", flowId, "Parent", "local");
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
        FlowGraph child = new FlowGraph(id, "Child", "local", List.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
        when(flows.get(id)).thenReturn(Optional.of(child));
    }

    private static RunSummary summary(String id, String status) {
        return new RunSummary(id, "flow_child", "Child", "local", status, 0L, null,
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
        AgentRun childRun = new AgentRun("run-child", "flow_child", "Child", "local");
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
        when(flows.get("flow_gone")).thenReturn(Optional.empty());

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
        return new FlowGraph(flowId, "Parent", "local",
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
}