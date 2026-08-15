package com.concentus.mcp;

import com.concentus.model.DoctorFinding;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.service.FlowCompiler;
import com.concentus.service.FlowDoctor;
import com.concentus.web.FlowController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The flow-authoring tools, at the two points where an agent's mistake would cost the user work.
 *
 * <p>The first is {@code update_flow_node}. A model asked to change an agent's model id sends back
 * the fields it thought about and forgets the rest; if that were applied literally it would strip
 * the tool allowlist that made a reviewer read-only. Merging is therefore the default and is
 * pinned here.
 *
 * <p>The second is {@code create_flow} being handed an id — from a copy-paste, or from the example
 * in {@code flow_schema}. Honouring it would overwrite a flow the user never mentioned.
 */
class FlowStudioToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowController flows;
    private FlowCompiler compiler;
    private FlowDoctor doctor;
    private FlowStudioTools tools;

    @BeforeEach
    void setUp() {
        flows = mock(FlowController.class);
        compiler = mock(FlowCompiler.class);
        doctor = mock(FlowDoctor.class);
        when(doctor.check(any())).thenReturn(List.of());
        tools = new FlowStudioTools(flows, compiler, doctor, MAPPER);
    }

    // ------------------------------------------------------------- node edits

    @Test
    void editingOneFieldOfANodeKeepsTheFieldsTheModelDidNotMention() {
        FlowNode agent = new FlowNode("a1", "agent", "subagent", Map.of(
                "name", "Reviewer",
                "model", "claude-sonnet-5",
                "tools", List.of("Read", "Grep")));
        when(flows.get("f1")).thenReturn(flow(agent));
        when(flows.save(any())).thenAnswer(call -> call.getArgument(0));

        call("update_flow_node", args -> {
            args.put("flowId", "f1");
            args.put("nodeId", "a1");
            args.putObject("data").put("model", "claude-opus-5");
        });

        Map<String, Object> data = savedNode("a1").dataOrEmpty();
        assertThat(data.get("model")).isEqualTo("claude-opus-5");
        assertThat(data.get("name")).isEqualTo("Reviewer");
        assertThat(data.get("tools")).isEqualTo(List.of("Read", "Grep"));
    }

    @Test
    void replaceTrueIsHowAFieldIsActuallyRemoved() {
        FlowNode agent = new FlowNode("a1", "agent", "subagent", Map.of(
                "name", "Reviewer", "tools", List.of("Read")));
        when(flows.get("f1")).thenReturn(flow(agent));
        when(flows.save(any())).thenAnswer(call -> call.getArgument(0));

        call("update_flow_node", args -> {
            args.put("flowId", "f1");
            args.put("nodeId", "a1");
            args.put("replace", true);
            args.putObject("data").put("name", "Reviewer");
        });

        assertThat(savedNode("a1").dataOrEmpty()).containsOnlyKeys("name");
    }

    /** {@code role} lives on the node, not in its data — but that is not where a model looks. */
    @Test
    void aRoleSentInsideDataIsPutWhereItBelongs() {
        when(flows.get("f1")).thenReturn(flow(new FlowNode("a1", "agent", "subagent", Map.of())));
        when(flows.save(any())).thenAnswer(call -> call.getArgument(0));

        call("update_flow_node", args -> {
            args.put("flowId", "f1");
            args.put("nodeId", "a1");
            args.putObject("data").put("role", "coordinator");
        });

        FlowNode saved = savedNode("a1");
        assertThat(saved.role()).isEqualTo("coordinator");
        assertThat(saved.dataOrEmpty()).doesNotContainKey("role");
    }

    @Test
    void anUnknownNodeIdIsAnsweredWithTheIdsThatExist() {
        when(flows.get("f1")).thenReturn(flow(new FlowNode("a1", "agent", "coordinator", Map.of())));

        StudioTool.Result result = call("update_flow_node", args -> {
            args.put("flowId", "f1");
            args.put("nodeId", "typo");
            args.putObject("data").put("model", "x");
        });

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("typo").contains("a1");
        verify(flows, never()).save(any());
    }

    // ----------------------------------------------------------------- create

    @Test
    void anIdSentToCreateIsIgnoredSoNothingIsOverwritten() {
        when(flows.save(any())).thenAnswer(call -> ((FlowGraph) call.getArgument(0)).withId("new-id"));

        call("create_flow", args -> args.set("flow", MAPPER.valueToTree(
                flow("docs-from-code", new FlowNode("a1", "agent", "coordinator", Map.of())))));

        ArgumentCaptor<FlowGraph> saved = ArgumentCaptor.forClass(FlowGraph.class);
        verify(flows).save(saved.capture());
        assertThat(saved.getValue().id()).isNull();
    }

    @Test
    void aGraphThatIsNotAFlowIsRejectedWithAPointerToTheSchema() {
        assertThatThrownBy(() -> call("create_flow", args -> args.putObject("flow").put("nodes", 7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flow_schema");
    }

    // --------------------------------------------------------------- validate

    @Test
    void aGraphThatWillNotCompileIsReportedAsFatalWithTheCompilersOwnMessage() {
        FlowGraph broken = flow(new FlowNode("a1", "agent", null, Map.of()));
        when(flows.get("f1")).thenReturn(broken);
        when(compiler.compile(broken)).thenThrow(
                new IllegalArgumentException("Flow has more than one coordinator — mark exactly one."));

        StudioTool.Result result = call("validate_flow", args -> args.put("flowId", "f1"));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("WILL NOT RUN").contains("more than one coordinator");
    }

    /**
     * Both checks run even when the first one fails, so one call tells the model everything it has
     * to fix instead of one problem per round trip.
     */
    @Test
    void theDoctorStillRunsWhenTheGraphDoesNotCompile() {
        FlowGraph broken = flow(new FlowNode("a1", "agent", null, Map.of()));
        when(flows.get("f1")).thenReturn(broken);
        when(compiler.compile(broken)).thenThrow(new IllegalArgumentException("no coordinator"));
        when(doctor.check(broken)).thenReturn(List.of(new DoctorFinding("error", "credential",
                "The repository node has no credential.", "Pick one in Resources.", "repo-1")));

        StudioTool.Result result = call("validate_flow", args -> args.put("flowId", "f1"));

        assertThat(result.text()).contains("no coordinator")
                .contains("[ERROR] credential").contains("repo-1")
                .contains("Fix: Pick one in Resources.");
    }

    @Test
    void aCleanFlowSaysSoRatherThanAnsweringWithNothing() {
        FlowGraph fine = flow(new FlowNode("a1", "agent", "coordinator", Map.of()));
        when(flows.get("f1")).thenReturn(fine);

        StudioTool.Result result = call("validate_flow", args -> args.put("flowId", "f1"));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("Compiles").contains("nothing to report");
    }

    @Test
    void validateNeedsSomethingToValidate() {
        assertThatThrownBy(() -> call("validate_flow", args -> {
        })).isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- guards

    @Test
    void deletingAFlowRequiresConfirmation() {
        StudioTool.Result result = call("delete_flow", args -> args.put("flowId", "f1"));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("confirm=true");
        verify(flows, never()).delete("f1");
    }

    @Test
    void clearingMemoryRequiresConfirmation() {
        StudioTool.Result result = call("clear_flow_memory", args -> args.put("flowId", "f1"));

        assertThat(result.isError()).isTrue();
        verify(flows, never()).clearMemory("f1");
    }

    // ----------------------------------------------------------------- schema

    /**
     * The schema is the only documentation a model gets, and its example is read from the
     * classpath — so a renamed or removed sample flow must fail here rather than quietly shipping
     * a schema with no example in it.
     */
    @Test
    void theSchemaDescribesEveryNodeTypeAndCarriesARealExample() {
        String schema = call("flow_schema", args -> {
        }).text();

        assertThat(schema).contains("input", "agent", "mcp", "repo", "sql", "knowledge", "api",
                "merge", "verifier");
        assertThat(schema).contains("exactly one input node", "_pos", "validate_flow");
        assertThat(schema).contains("\"id\": \"docs-from-code\"");
    }

    // ------------------------------------------------------------------ setup

    private static FlowGraph flow(FlowNode... nodes) {
        return flow(null, nodes);
    }

    private static FlowGraph flow(String id, FlowNode... nodes) {
        return new FlowGraph(id, "Flow", "local", List.of(nodes), List.of(),
                null, List.of(), null, null);
    }

    private StudioTool.Result call(String name, java.util.function.Consumer<ObjectNode> arguments) {
        ObjectNode args = MAPPER.createObjectNode();
        arguments.accept(args);
        StudioTool tool = tools.tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such tool: " + name));
        return tool.handler().apply(args);
    }

    /** The node with this id out of the graph the tools handed to {@code FlowController.save}. */
    private FlowNode savedNode(String nodeId) {
        ArgumentCaptor<FlowGraph> saved = ArgumentCaptor.forClass(FlowGraph.class);
        verify(flows).save(saved.capture());
        return saved.getValue().nodesOrEmpty().stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node " + nodeId + " was not saved"));
    }
}
