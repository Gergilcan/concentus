package com.concentus.web;

import com.concentus.api.ApiCaller;
import com.concentus.api.OpenApiCatalog;
import com.concentus.config.AgentSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.concentus.service.SubflowService;
import com.concentus.store.FlowMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
 * The tool an agent calls to run another flow.
 *
 * <p>One tool rather than one per node, with the flow named as an argument, because the alternative
 * is a tool list that grows with the canvas. What matters is that the argument is an allowlist: an
 * agent can only reach the flows drawn into its own graph, not every flow the deployment holds.
 */
class RunFlowToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentRun run;
    private final SubflowService subflows = mock(SubflowService.class);

    private RunToolsController controller(AgentSpec.SubflowSpec... wired) {
        run = new AgentRun("run-1", "flow-1", "Parent", "local");
        run.toolToken = "tok-secret";
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        coord.subflows.addAll(List.of(wired));
        run.compiled = new CompiledFlow(coord, List.of());

        RunService runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        FlowMemoryStore memory = mock(FlowMemoryStore.class);
        when(memory.isAvailable()).thenReturn(false);
        return new RunToolsController(runs, new OpenApiCatalog(MAPPER), new ApiCaller(MAPPER),
                MAPPER, memory, subflows, mock(com.concentus.service.KnowledgeRetriever.class),
                new com.concentus.service.ContextAssembler(12000),
                new com.concentus.service.ToolCallLoopGuard());
    }

    private static AgentSpec.SubflowSpec spec(String label, String flowId) {
        AgentSpec.SubflowSpec s = new AgentSpec.SubflowSpec();
        s.nodeId = "sub-" + label;
        s.label = label;
        s.flowId = flowId;
        return s;
    }

    private static JsonNode rpc(String method, String params) {
        try {
            return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String textOf(ResponseEntity<JsonNode> res) {
        return res.getBody().path("result").path("content").get(0).path("text").asText();
    }

    @Test
    void a_flow_with_no_flow_nodes_is_not_offered_the_tool() {
        ResponseEntity<JsonNode> res = controller().rpc("run-1", "tok-secret", rpc("tools/list", null));

        JsonNode tools = res.getBody().path("result").path("tools");
        assertThat(tools).allSatisfy(t -> assertThat(t.path("name").asText()).isNotEqualTo("run_flow"));
    }

    @Test
    void the_tool_names_the_flows_this_agent_may_run() {
        ResponseEntity<JsonNode> res = controller(spec("Weekly report", "flow_report"))
                .rpc("run-1", "tok-secret", rpc("tools/list", null));

        JsonNode tool = null;
        for (JsonNode t : res.getBody().path("result").path("tools")) {
            if ("run_flow".equals(t.path("name").asText())) tool = t;
        }
        assertThat(tool).isNotNull();
        assertThat(tool.path("description").asText()).contains("Weekly report");
        assertThat(tool.path("inputSchema").path("required").toString()).contains("flow");
    }

    @Test
    void calling_it_runs_that_flow_and_hands_back_its_answer() {
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(true, "run-child", "COMPLETED",
                        "Eleven leads this week.", null));

        ResponseEntity<JsonNode> res = controller(spec("Weekly report", "flow_report"))
                .rpc("run-1", "tok-secret", rpc("tools/call",
                        "{\"name\":\"run_flow\",\"arguments\":{\"flow\":\"Weekly report\",\"prompt\":\"Count them\"}}"));

        assertThat(textOf(res)).contains("Eleven leads this week.");
        assertThat(res.getBody().path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void a_flow_that_was_not_drawn_into_this_graph_cannot_be_reached() {
        // The allowlist IS the flow nodes. Without this, run_flow would be a way to run anything
        // in the deployment from any flow that happened to have one sub-flow wired.
        ResponseEntity<JsonNode> res = controller(spec("Weekly report", "flow_report"))
                .rpc("run-1", "tok-secret", rpc("tools/call",
                        "{\"name\":\"run_flow\",\"arguments\":{\"flow\":\"Payroll\",\"prompt\":\"go\"}}"));

        assertThat(res.getBody().path("result").path("isError").asBoolean()).isTrue();
        assertThat(textOf(res)).contains("Weekly report");
        verify(subflows, never()).run(any(), any(), anyString());
    }

    @Test
    void a_refusal_comes_back_as_a_tool_error_the_agent_can_read() {
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(false, null, null, null,
                        "That flow is already running further up this chain."));

        ResponseEntity<JsonNode> res = controller(spec("Weekly report", "flow_report"))
                .rpc("run-1", "tok-secret", rpc("tools/call",
                        "{\"name\":\"run_flow\",\"arguments\":{\"flow\":\"Weekly report\",\"prompt\":\"go\"}}"));

        assertThat(res.getBody().path("result").path("isError").asBoolean()).isTrue();
        assertThat(textOf(res)).contains("already running");
    }

    @Test
    void a_hand_off_nobody_waited_for_reports_the_run_it_started_rather_than_a_result() {
        when(subflows.run(any(), any(), anyString()))
                .thenReturn(new SubflowService.Result(true, "run-child", "RUNNING", null, null));

        ResponseEntity<JsonNode> res = controller(spec("Notify", "flow_notify"))
                .rpc("run-1", "tok-secret", rpc("tools/call",
                        "{\"name\":\"run_flow\",\"arguments\":{\"flow\":\"Notify\",\"prompt\":\"go\"}}"));

        assertThat(res.getBody().path("result").path("isError").asBoolean()).isFalse();
        assertThat(textOf(res)).contains("run-child");
    }
}
