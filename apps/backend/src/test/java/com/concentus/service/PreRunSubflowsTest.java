package com.concentus.service;

import com.concentus.config.AgentSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A flow wired INTO an agent runs before it, and its answer arrives as context.
 *
 * <p>That is what makes the direction rule mean something: an input feeds. The same node also
 * leaves the agent a {@code run_flow} tool, so "ask it again, differently" stays possible — but
 * the first answer is there without anyone deciding to ask.
 */
class PreRunSubflowsTest {

    private final SubflowService subflows = mock(SubflowService.class);

    private static AgentSpec agentWith(AgentSpec.SubflowSpec... specs) {
        AgentSpec spec = new AgentSpec();
        spec.name = "Estratega";
        spec.systemPrompt = "You lead the work.";
        spec.subflows.addAll(List.of(specs));
        return spec;
    }

    private static AgentSpec.SubflowSpec sub(String label, boolean wait) {
        AgentSpec.SubflowSpec s = new AgentSpec.SubflowSpec();
        s.nodeId = "sub-" + label;
        s.label = label;
        s.flowId = "flow_" + label;
        s.waitForResult = wait;
        return s;
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun("run-1", "flow-parent", "Parent");
        run.initialPrompt = "Haz el informe de la semana";
        return run;
    }

    @Test
    void the_child_answer_is_appended_to_the_agents_briefing() {
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(true, "run-child", "COMPLETED",
                        "11 leads, 4 cualificados.", null));
        AgentSpec spec = agentWith(sub("Datos", true));

        new PreRunSubflows(subflows).inject(spec, run(), m -> {});

        assertThat(spec.systemPrompt)
                .contains("You lead the work.")
                .contains("Datos")
                .contains("11 leads, 4 cualificados.");
    }

    @Test
    void the_child_is_given_the_text_this_run_started_with() {
        // It runs before the agent has said anything, so the run's own input is the only honest
        // thing to hand it.
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(true, "run-child", "COMPLETED", "ok", null));

        new PreRunSubflows(subflows).inject(agentWith(sub("Datos", true)), run(), m -> {});

        verify(subflows).run(any(), any(), org.mockito.ArgumentMatchers.eq("Haz el informe de la semana"));
    }

    @Test
    void a_node_that_does_not_wait_is_started_without_pretending_to_have_an_answer() {
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(true, "run-child", "RUNNING", null, null));
        AgentSpec spec = agentWith(sub("Aviso", false));

        new PreRunSubflows(subflows).inject(spec, run(), m -> {});

        assertThat(spec.systemPrompt).isEqualTo("You lead the work.");
    }

    @Test
    void a_child_that_failed_says_so_in_the_briefing_instead_of_leaving_a_hole() {
        // Silence would have the agent write around missing data as though it had it.
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(false, null, null, null, "Budget reached."));
        AgentSpec spec = agentWith(sub("Datos", true));

        List<String> said = new ArrayList<>();
        new PreRunSubflows(subflows).inject(spec, run(), said::add);

        assertThat(spec.systemPrompt).contains("Datos").contains("Budget reached.");
        assertThat(said).anySatisfy(m -> assertThat(m).contains("Datos"));
    }

    @Test
    void an_agent_with_no_flow_nodes_is_left_exactly_as_it_was() {
        AgentSpec spec = agentWith();

        new PreRunSubflows(subflows).inject(spec, run(), m -> {});

        assertThat(spec.systemPrompt).isEqualTo("You lead the work.");
        verify(subflows, never()).run(any(), any(), anyString());
    }
}
