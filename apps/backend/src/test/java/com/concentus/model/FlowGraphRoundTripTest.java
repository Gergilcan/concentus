package com.concentus.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlowGraph must survive serialise-then-parse with EVERY component intact.
 *
 * <p>This test exists because it did not: Jackson's creator detection, faced with a record that
 * kept convenience constructors for old shapes, picked a SECONDARY constructor to deserialise
 * with — and silently dropped every component that constructor predates. Reading flows back from
 * the store lost {@code folder} on arrival and had been losing {@code variables} since they
 * shipped. The convenience constructors now carry {@code @JsonIgnore}; this round trip is the
 * tripwire for the next person who adds a component and a shortcut constructor together.
 */
class FlowGraphRoundTripTest {

    @Test
    void everyComponentSurvivesTheStoreRoundTrip() throws Exception {
        FlowGraph flow = new FlowGraph("flow_1", "Triage", "local",
                List.of(new FlowNode("n1", "agent", "coordinator", Map.of("name", "Coord"))),
                List.of(new FlowEdge("e1", "n1", "n1")),
                false, List.of("ops"), true,
                "https://hooks.example", 25.0,
                "cred_slack", "C012345", "https://teams.example",
                Map.of("COMPANY", "ACME"), "Samples", true);

        // A plain mapper, deliberately: the strictest reader this JSON will ever meet, and the
        // configuration under which the constructor ambiguity bit hardest.
        ObjectMapper mapper = new ObjectMapper();
        FlowGraph back = mapper.readValue(mapper.writeValueAsString(flow), FlowGraph.class);

        assertThat(back).isEqualTo(flow);
        // The two components the bug actually ate, named so a regression reads as itself.
        assertThat(back.folder()).isEqualTo("Samples");
        assertThat(back.variables()).containsEntry("COMPANY", "ACME");
        assertThat(back.approvalSlackChannel()).isEqualTo("C012345");
        // The newest component, which is what this tripwire is for.
        assertThat(back.goldenAutoRunOrDefault()).isTrue();
    }

    @Test
    void withIdCopiesEveryComponent() {
        // Every save stamps the id through withId, so a withId that calls a stale shortcut
        // constructor strips the newest components from EVERY stored flow — which is exactly
        // how `folder` and `variables` were being lost at save time. Equality on an id-aligned
        // copy catches the next stale shortcut before it ships.
        FlowGraph flow = new FlowGraph("flow_1", "Triage", "local",
                List.of(new FlowNode("n1", "agent", "coordinator", Map.of("name", "Coord"))),
                List.of(new FlowEdge("e1", "n1", "n1")),
                false, List.of("ops"), true,
                "https://hooks.example", 25.0,
                "cred_slack", "C012345", "https://teams.example",
                Map.of("COMPANY", "ACME"), "Samples", true);

        assertThat(flow.withId("flow_2")).isEqualTo(flow.withId("flow_2").withId("flow_2"))
                .extracting(FlowGraph::folder, FlowGraph::variables, FlowGraph::budgetUsd,
                        FlowGraph::goldenAutoRun)
                .containsExactly("Samples", Map.of("COMPANY", "ACME"), 25.0, true);
        assertThat(flow.withId("flow_2").withId("flow_1")).isEqualTo(flow);
        // withFolder is the other copy every seeded flow goes through, and it strips the same way.
        assertThat(flow.withFolder("Other").goldenAutoRun()).isTrue();
    }
}
