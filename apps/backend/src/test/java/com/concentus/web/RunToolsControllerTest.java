package com.concentus.web;

import com.concentus.api.ApiCaller;
import com.concentus.api.OpenApiCatalog;
import com.concentus.config.AgentSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.concentus.store.FlowMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-run MCP endpoint: the JSON-RPC handshake the {@code claude} CLI performs, the token
 * gate that keeps it per-run, and the flow-memory tools saved flows get.
 */
class RunToolsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {"openapi": "3.0.0", "servers": [{"url": "https://api.example.com"}],
             "paths": {"/pets": {
               "get": {"operationId": "listPets", "summary": "List pets"},
               "delete": {"operationId": "dropPets"}
             }}}
            """;

    private AgentRun run;
    private final FlowMemoryStore memory = mock(FlowMemoryStore.class);

    private RunToolsController controller() {
        return controller("flow-1");
    }

    private RunToolsController controller(String flowId) {
        run = new AgentRun("run-1", flowId, "Flow");
        run.toolToken = "tok-secret";
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        AgentSpec.ApiSourceSpec api = new AgentSpec.ApiSourceSpec();
        api.label = "petstore";
        api.specInline = SPEC;
        // Only the read is allowed; the delete exists in the spec but was never ticked.
        api.ops = List.of("GET /pets");
        coord.apiSources.add(api);
        run.compiled = new CompiledFlow(coord, List.of());

        RunService runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        when(memory.isAvailable()).thenReturn(true);
        return new RunToolsController(runs, new OpenApiCatalog(MAPPER),
                new ApiCaller(MAPPER), MAPPER, memory,
                mock(com.concentus.service.SubflowService.class),
                mock(com.concentus.service.KnowledgeRetriever.class),
                new com.concentus.service.ContextAssembler(12000),
                new com.concentus.service.ToolCallLoopGuard());
    }

    private static JsonNode rpc(String method, String params) {
        try {
            return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aWrongTokenIsA401WithNoDetail() {
        RunToolsController c = controller();

        assertThat(c.rpc("run-1", "wrong", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);
        assertThat(c.rpc("run-1", null, rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void initializeAnswersTheHandshake() {
        ResponseEntity<JsonNode> res = controller().rpc("run-1", "tok-secret", rpc("initialize", null));

        JsonNode result = res.getBody().path("result");
        assertThat(result.path("protocolVersion").asText()).isNotBlank();
        assertThat(result.path("capabilities").has("tools")).isTrue();
    }

    @Test
    void toolsListExposesOnlyTheAllowedOperations() {
        ResponseEntity<JsonNode> res = controller().rpc("run-1", "tok-secret", rpc("tools/list", null));

        JsonNode tools = res.getBody().path("result").path("tools");
        // The spec has two operations; only the ticked one becomes a tool. The delete is not
        // hidden by prompt engineering — it simply does not exist as far as the model knows.
        // A saved flow additionally gets its two memory tools.
        assertThat(tools).hasSize(3);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("petstore__listPets");
        assertThat(tools.get(0).path("inputSchema").path("type").asText()).isEqualTo("object");
        assertThat(tools.get(1).path("name").asText()).isEqualTo("memory_read");
        assertThat(tools.get(2).path("name").asText()).isEqualTo("memory_append");
        assertThat(tools.get(2).path("inputSchema").path("required").get(0).asText())
                .isEqualTo("note");
    }

    @Test
    void callingAToolThatWasNeverAllowedIsAnErrorResultNotACall() {
        ResponseEntity<JsonNode> res = controller().rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"petstore__dropPets\",\"arguments\":{}}"));

        JsonNode result = res.getBody().path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("Unknown tool");
    }

    // ------------------------------------------------------------- flow memory

    @Test
    void anAdHocRunHasNoMemoryTools() {
        // No flow id: nothing for notes to survive under, so the tools do not exist at all —
        // absent from the list, and unknown when called anyway.
        RunToolsController c = controller(null);

        JsonNode tools = c.rpc("run-1", "tok-secret", rpc("tools/list", null))
                .getBody().path("result").path("tools");
        assertThat(tools).hasSize(1);

        JsonNode result = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_read\",\"arguments\":{}}"))
                .getBody().path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("Unknown tool");
    }

    @Test
    void memoryReadReturnsTheNotesOldestFirst() {
        RunToolsController c = controller();
        when(memory.recent("flow-1", RunToolsController.MEMORY_READ_LIMIT)).thenReturn(List.of(
                new FlowMemoryStore.Note(1, "run-0", "the API needs paging", 1_000),
                new FlowMemoryStore.Note(2, "run-0", "invoices done through July", 2_000)));
        when(memory.count("flow-1")).thenReturn(2);

        JsonNode result = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_read\",\"arguments\":{}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).contains("2 note(s)");
        assertThat(text.indexOf("the API needs paging"))
                .isLessThan(text.indexOf("invoices done through July"));
    }

    @Test
    void memoryReadOnAnEmptyMemorySaysSoWithoutError() {
        RunToolsController c = controller();
        when(memory.recent(anyString(), anyInt())).thenReturn(List.of());

        JsonNode result = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_read\",\"arguments\":{}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("empty");
    }

    @Test
    void memoryAppendSavesTheNoteAndReportsTheTotal() {
        RunToolsController c = controller();
        when(memory.count("flow-1")).thenReturn(3);

        JsonNode result = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_append\",\"arguments\":{\"note\":\"remember this\"}}"))
                .getBody().path("result");

        verify(memory).append("flow-1", "run-1", "remember this");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("3 note(s)");
    }

    @Test
    void memoryAppendReportsTheStoresRefusalToTheModel() {
        RunToolsController c = controller();
        // The store enforces the limits (blank, oversized); the tool's job is to pass the reason
        // through as an error result the model can react to, not to swallow it.
        doThrow(new IllegalArgumentException("A non-empty note is required."))
                .when(memory).append(anyString(), anyString(), anyString());

        JsonNode result = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_append\",\"arguments\":{\"note\":\"\"}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("A non-empty note is required.");
    }

    @Test
    void whenTheDatabaseIsDownMemoryToolsSaySoInsteadOfPretending() {
        RunToolsController c = controller();
        when(memory.isAvailable()).thenReturn(false);

        JsonNode read = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_read\",\"arguments\":{}}"))
                .getBody().path("result");
        JsonNode append = c.rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"memory_append\",\"arguments\":{\"note\":\"x\"}}"))
                .getBody().path("result");

        assertThat(read.path("isError").asBoolean()).isTrue();
        assertThat(append.path("isError").asBoolean()).isTrue();
        assertThat(append.path("content").get(0).path("text").asText()).contains("NOT saved");
    }
}
