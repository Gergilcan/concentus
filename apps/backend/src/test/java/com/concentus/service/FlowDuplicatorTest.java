package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a copy of a flow keeps, and the two things it deliberately drops.
 *
 * <p>Both drops are about the copy acting on the world before anyone has read it: a scheduled flow
 * copied and left running fires twice every morning, and a webhook secret copied means two flows
 * claiming one endpoint's signature.
 */
class FlowDuplicatorTest {

    private static FlowGraph source() {
        return new FlowGraph("flow_1", "Daily briefing", "local",
                List.of(new FlowNode("in-1", "input", null,
                                Map.of("mode", "cron", "cron", "0 7 * * 1-5", "secret", "whsec_abc",
                                        "prompt", "Run the daily cycle.")),
                        new FlowNode("agent-1", "agent", "coordinator",
                                Map.of("name", "Lead", "systemPrompt", "An afternoon of writing."))),
                List.of(new FlowEdge("e1", "in-1", "agent-1")),
                Boolean.TRUE, List.of("ads"), Boolean.TRUE, "https://hooks.example.com", 25.0,
                null, null, null, Map.of("COMPANY", "Tecnovent"), "Samples", Boolean.TRUE);
    }

    @Test
    void the_work_comes_across_untouched() {
        FlowGraph copy = FlowDuplicator.copyOf(source(), Set.of());

        assertThat(copy.nodesOrEmpty()).extracting(FlowNode::id).containsExactly("in-1", "agent-1");
        assertThat(copy.nodesOrEmpty().get(1).dataOrEmpty())
                .containsEntry("systemPrompt", "An afternoon of writing.");
        assertThat(copy.edgesOrEmpty()).hasSize(1);
        assertThat(copy.variables()).containsEntry("COMPANY", "Tecnovent");
        assertThat(copy.budgetUsd()).isEqualTo(25.0);
        assertThat(copy.folder()).isEqualTo("Samples");
    }

    // The one outcome a copy must never have: overwriting the flow it is a copy OF.
    @Test
    void a_copy_carries_no_id_so_it_cannot_overwrite_its_source() {
        assertThat(FlowDuplicator.copyOf(source(), Set.of()).id()).isNull();
    }

    @Test
    void a_copy_arrives_paused_with_its_trigger_intact() {
        FlowGraph copy = FlowDuplicator.copyOf(source(), Set.of());

        assertThat(copy.enabledOrDefault()).isFalse();
        assertThat(copy.nodesOrEmpty().get(0).dataOrEmpty())
                .containsEntry("mode", "cron")
                .containsEntry("cron", "0 7 * * 1-5");
    }

    @Test
    void a_webhook_secret_is_not_copied_because_it_names_one_endpoint() {
        FlowGraph copy = FlowDuplicator.copyOf(source(), Set.of());

        assertThat(copy.nodesOrEmpty().get(0).dataOrEmpty()).containsEntry("secret", "");
    }

    // Saving a flow re-runs the golden reference when the flow asks for it. A copy that inherited
    // that would spend an agent run the moment it was created, before anyone had seen it.
    @Test
    void a_copy_does_not_inherit_the_automatic_golden_check() {
        assertThat(FlowDuplicator.copyOf(source(), Set.of()).goldenAutoRunOrDefault()).isFalse();
    }

    @Test
    void a_second_copy_is_numbered_rather_than_named_the_same() {
        assertThat(FlowDuplicator.copyOf(source(), Set.of("Daily briefing")).name())
                .isEqualTo("Daily briefing copy");
        assertThat(FlowDuplicator.copyOf(source(), Set.of("Daily briefing", "Daily briefing copy"))
                .name()).isEqualTo("Daily briefing copy 2");
        // Case is not a distinction anyone reading a flow list makes.
        assertThat(FlowDuplicator.copyOf(source(), Set.of("daily briefing COPY")).name())
                .isEqualTo("Daily briefing copy 2");
    }
}
