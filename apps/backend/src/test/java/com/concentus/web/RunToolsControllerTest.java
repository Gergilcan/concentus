package com.concentus.web;

import com.concentus.api.ApiCaller;
import com.concentus.api.OpenApiCatalog;
import com.concentus.config.AgentSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-run MCP endpoint: the JSON-RPC handshake the {@code claude} CLI performs, and the token
 * gate that keeps it per-run.
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

    private RunToolsController controller() {
        run = new AgentRun("run-1", "flow-1", "Flow", "local");
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
        return new RunToolsController(runs, new OpenApiCatalog(MAPPER),
                new ApiCaller(MAPPER), MAPPER);
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
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("petstore__listPets");
        assertThat(tools.get(0).path("inputSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void callingAToolThatWasNeverAllowedIsAnErrorResultNotACall() {
        ResponseEntity<JsonNode> res = controller().rpc("run-1", "tok-secret",
                rpc("tools/call", "{\"name\":\"petstore__dropPets\",\"arguments\":{}}"));

        JsonNode result = res.getBody().path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("Unknown tool");
    }
}
