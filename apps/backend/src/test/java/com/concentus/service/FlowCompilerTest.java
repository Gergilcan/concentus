package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link FlowCompiler}'s pure flow -> spec compilation logic. */
class FlowCompilerTest {

    private final FlowCompiler compiler = new FlowCompiler();

    private static FlowNode agent(String id, String role, String name) {
        return new FlowNode(id, "agent", role, Map.of("name", name, "systemPrompt", "do stuff"));
    }

    private static FlowNode mcp(String id, String name, String url) {
        return new FlowNode(id, "mcp", null, Map.of("name", name, "url", url));
    }

    private static FlowEdge edge(String source, String target) {
        return new FlowEdge(source + "-" + target, source, target);
    }

    // ------------------------------------------------------------- valid flow

    @Test
    void compilesAValidSingleAgentFlow() {
        FlowNode a = agent("a1", "coordinator", "Solo");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().name).isEqualTo("Solo");
        assertThat(compiled.coordinator().nodeId).isEqualTo("a1");
        assertThat(compiled.subAgents()).isEmpty();
    }

    @Test
    void singleAgentWithNoRoleIsTreatedAsCoordinator() {
        // No agent is explicitly marked "coordinator", but since there's only one agent it's used as such.
        FlowNode a = agent("a1", null, "Solo");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().nodeId).isEqualTo("a1");
    }

    // ------------------------------------------------------ delegation wiring

    @Test
    void onlyAgentsLinkedToCoordinatorBecomeSubAgents() {
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode wired = agent("s1", "subagent", "Wired");
        FlowNode unwired = agent("s2", "subagent", "Unwired");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, wired, unwired),
                List.of(edge("c1", "s1")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().nodeId).isEqualTo("c1");
        assertThat(compiled.subAgents()).hasSize(1);
        assertThat(compiled.subAgents().get(0).nodeId).isEqualTo("s1");
        assertThat(compiled.subAgents().get(0).name).isEqualTo("Wired");
    }

    @Test
    void delegationEdgeIsUndirectedForWiring() {
        // The coordinator<->subagent edge direction shouldn't matter for whether the agent is wired in.
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode sub = agent("s1", "subagent", "Sub");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, sub),
                List.of(edge("s1", "c1")), // reversed direction
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.subAgents()).extracting(s -> s.nodeId).containsExactly("s1");
    }

    @Test
    void resourceNodesAreOnlyAttachedToTheAgentTheyAreWiredTo() {
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode sub = agent("s1", "subagent", "Sub");
        FlowNode mcpNode = mcp("m1", "github", "https://example.com/mcp");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, sub, mcpNode),
                List.of(edge("c1", "s1"), edge("s1", "m1")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        AgentSpec subSpec = compiled.subAgents().get(0);
        assertThat(subSpec.mcpServers).hasSize(1);
        assertThat(subSpec.mcpServers.get(0).name).isEqualTo("github");
        assertThat(compiled.coordinator().mcpServers).isEmpty();
    }

    // ----------------------------------------------------------- invalid flows

    @Test
    void flowWithNoAgentNodesThrows() {
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.<FlowNode>of(), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no agent nodes");
    }

    @Test
    void multipleAgentsWithoutACoordinatorThrows() {
        FlowNode a1 = agent("a1", "subagent", "One");
        FlowNode a2 = agent("a2", "subagent", "Two");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a1, a2), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coordinator");
    }

    @Test
    void multipleCoordinatorsThrows() {
        FlowNode a1 = agent("a1", "coordinator", "One");
        FlowNode a2 = agent("a2", "coordinator", "Two");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a1, a2), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one coordinator");
    }

    @Test
    void badEdgeWiringAResourceMissingRequiredFieldsThrowsOnValidation() {
        // An MCP node wired to the coordinator but missing its `url` produces an invalid AgentSpec;
        // FlowCompiler surfaces that as a compile-time failure rather than a silently broken agent.
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode badMcp = new FlowNode("m1", "mcp", null, Map.of("name", "broken")); // no url
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, badMcp),
                List.of(edge("c1", "m1")),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }
}
