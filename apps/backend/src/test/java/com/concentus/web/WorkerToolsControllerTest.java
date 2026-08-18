package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.McpClient;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.FacadeProfile;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-worker MCP facade over the wire: the token gate, the profile-filtered listing, and —
 * the part that must never be wrong — what happens when a worker calls a write.
 */
class WorkerToolsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A server whose network life is replaced by canned tools and a call recorder. */
    private static final class FakeMcpClient extends McpClient {
        final AtomicReference<String> called = new AtomicReference<>();

        FakeMcpClient() {
            super("holded", "https://mcp.example.com/mcp", McpClient.TokenSource.fixed(null), MAPPER);
        }

        @Override
        public List<ChatTypes.ToolSpec> listTools() {
            return List.of(
                    new ChatTypes.ToolSpec("list_contacts", "Lists contacts", null),
                    new ChatTypes.ToolSpec("create_contact", "Creates a contact", null));
        }

        @Override
        public String callTool(String name, String argumentsJson) {
            called.set(name);
            return "real result of " + name;
        }
    }

    private AgentRun run;
    private FakeMcpClient mcp;

    private WorkerToolsController controller(FacadeProfile profile) {
        run = new AgentRun("run-1", "flow-1", "Flow", "local");
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        AgentSpec worker = new AgentSpec();
        worker.nodeId = "n1";
        worker.name = "Worker A";
        AgentSpec.McpServerSpec server = new AgentSpec.McpServerSpec();
        server.name = "holded";
        server.url = "https://mcp.example.com/mcp";
        worker.mcpServers.add(server);
        run.compiled = new CompiledFlow(coord, List.of(worker));
        run.workerToolTokens.put("n1", "tok-worker");
        run.workerFacadeProfiles.put("n1", profile);
        mcp = new FakeMcpClient();
        run.mcpClients.put("holded", mcp);

        RunService runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        McpOAuthStore oauth = mock(McpOAuthStore.class);
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.empty());
        OrgContext org = mock(OrgContext.class);
        when(org.defaultOrganizationId()).thenReturn("default");
        return new WorkerToolsController(runs, MAPPER, oauth, org);
    }

    private static JsonNode rpc(String method, String params) {
        try {
            return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FacadeProfile profile(List<String> tools, boolean readOnly, Boolean dryRun) {
        return new FacadeProfile("p1", "test-profile", "", tools, readOnly, dryRun);
    }

    // ------------------------------------------------------------------ token gate

    @Test
    void aWrongOrMissingTokenIsA401WithNoDetail() {
        WorkerToolsController c = controller(profile(List.of(), false, true));

        assertThat(c.rpc("run-1", "n1", "wrong", rpc("tools/list", null))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(c.rpc("run-1", "n1", null, rpc("tools/list", null))
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void anotherWorkersTokenDoesNotOpenThisWorkersFacade() {
        WorkerToolsController c = controller(profile(List.of(), false, true));
        run.workerToolTokens.put("n2", "tok-other");

        assertThat(c.rpc("run-1", "n1", "tok-other", rpc("tools/list", null))
                .getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ listing

    @Test
    void theListingIsTheProfileNotTheServer() {
        WorkerToolsController c = controller(profile(List.of(), true, null));

        ResponseEntity<JsonNode> res = c.rpc("run-1", "n1", "tok-worker", rpc("tools/list", null));

        JsonNode tools = res.getBody().path("result").path("tools");
        assertThat(tools).hasSize(1); // read-only: create_contact is not even advertised
        assertThat(tools.get(0).path("name").asText()).isEqualTo("holded__list_contacts");
    }

    @Test
    void underDryRunWritesAreListedAndLabelled() {
        WorkerToolsController c = controller(profile(List.of(), false, true));

        JsonNode tools = c.rpc("run-1", "n1", "tok-worker", rpc("tools/list", null))
                .getBody().path("result").path("tools");

        assertThat(tools).hasSize(2);
        JsonNode create = tools.get(1);
        assertThat(create.path("name").asText()).isEqualTo("holded__create_contact");
        assertThat(create.path("description").asText()).startsWith("[DRY RUN");
    }

    // ------------------------------------------------------------------ calls

    @Test
    void aReadExecutesForReal() {
        WorkerToolsController c = controller(profile(List.of(), true, null));

        JsonNode result = c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"holded__list_contacts\",\"arguments\":{}}")).getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText())
                .isEqualTo("real result of list_contacts");
        assertThat(mcp.called.get()).isEqualTo("list_contacts");
    }

    @Test
    void aWriteUnderDryRunIsSimulatedAndNeverReachesTheServer() {
        WorkerToolsController c = controller(profile(List.of(), false, true));

        JsonNode result = c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"holded__create_contact\",\"arguments\":{\"name\":\"ACME\"}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("DRY RUN").contains("ACME").contains("NOT executed");
        assertThat(mcp.called.get()).isNull(); // the whole point
    }

    @Test
    void aWriteUnderReadOnlyIsBlockedAndNeverReachesTheServer() {
        WorkerToolsController c = controller(profile(List.of(), true, null));

        JsonNode result = c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"holded__create_contact\",\"arguments\":{}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("read-only");
        assertThat(mcp.called.get()).isNull();
    }

    @Test
    void aToolOutsideTheAllowlistIsRefusedByNameEvenIfNeverListed() {
        // The server also has tools the profile never allowlisted; calling one by name must fail.
        WorkerToolsController c = controller(profile(List.of("contact"), false, false));

        JsonNode result = c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"holded__list_invoices\",\"arguments\":{}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("not part");
        assertThat(mcp.called.get()).isNull();
    }

    @Test
    void aWriteExecutesOnlyWhenDryRunWasDeliberatelyCleared() {
        WorkerToolsController c = controller(profile(List.of(), false, false));

        JsonNode result = c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"holded__create_contact\",\"arguments\":{}}"))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(mcp.called.get()).isEqualTo("create_contact");
    }
}
