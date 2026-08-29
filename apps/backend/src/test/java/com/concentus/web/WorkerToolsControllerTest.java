package com.concentus.web;

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
    private final com.concentus.service.FanoutExecutor fanout =
            mock(com.concentus.service.FanoutExecutor.class);
    private FakeMcpClient mcp;

    private WorkerToolsController controller(FacadeProfile profile) {
        run = new AgentRun("run-1", "flow-1", "Flow", "local");
        run.organizationId = "default";
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
        return new WorkerToolsController(runs, MAPPER, oauth,
                new com.concentus.service.ToolCallLoopGuard(), fanout);
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

    /**
     * The tools that came from an MCP server, which is what a facade profile decides about.
     *
     * <p>Every worker also carries the two it uses to talk to its siblings. Those are not a
     * server's, they carry no {@code server__} prefix, and a profile has no opinion about them:
     * it decides what a worker may do to the world, and a note to a sibling does nothing to the
     * world. Counting them here would be counting the wrong thing.
     */
    private static List<JsonNode> serverTools(JsonNode tools) {
        List<JsonNode> out = new java.util.ArrayList<>();
        tools.forEach(t -> {
            if (t.path("name").asText("").contains("__")) out.add(t);
        });
        return out;
    }

    @Test
    void theListingIsTheProfileNotTheServer() {
        WorkerToolsController c = controller(profile(List.of(), true, null));

        ResponseEntity<JsonNode> res = c.rpc("run-1", "n1", "tok-worker", rpc("tools/list", null));

        List<JsonNode> tools = serverTools(res.getBody().path("result").path("tools"));
        assertThat(tools).hasSize(1); // read-only: create_contact is not even advertised
        assertThat(tools.get(0).path("name").asText()).isEqualTo("holded__list_contacts");
    }

    @Test
    void everyWorkerCanReachItsSiblings() {
        WorkerToolsController c = controller(profile(List.of(), true, null));

        JsonNode tools = c.rpc("run-1", "n1", "tok-worker", rpc("tools/list", null))
                .getBody().path("result").path("tools");

        // Even under the strictest profile: the point of the channel is that a worker which may
        // change nothing can still stop the others repeating what it has already found out.
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("share_finding", "read_findings");
    }

    @Test
    void underDryRunWritesAreListedAndLabelled() {
        WorkerToolsController c = controller(profile(List.of(), false, true));

        List<JsonNode> tools = serverTools(c.rpc("run-1", "n1", "tok-worker", rpc("tools/list", null))
                .getBody().path("result").path("tools"));

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

    // ------------------------------------------------------------------ ask_worker

    /** A second worker beside n1, so there is somebody to ask. */
    private AgentSpec sibling(String nodeId, String name) {
        AgentSpec other = new AgentSpec();
        other.nodeId = nodeId;
        other.name = name;
        run.compiled = new CompiledFlow(run.compiled.coordinator(),
                List.of(run.compiled.subAgents().get(0), other));
        return other;
    }

    private JsonNode ask(WorkerToolsController c, String worker, String question) {
        return c.rpc("run-1", "n1", "tok-worker", rpc("tools/call",
                "{\"name\":\"ask_worker\",\"arguments\":{\"worker\":\"" + worker
                        + "\",\"question\":\"" + question + "\"}}")).getBody().path("result");
    }

    @Test
    void aQuestionToASiblingIsAnsweredFromItsWorkspaceAndSaidInTheLog() {
        WorkerToolsController c = controller(profile(List.of(), true, null));
        AgentSpec scout = sibling("n2", "Scout");
        when(fanout.askAbout(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(scout), org.mockito.ArgumentMatchers.eq("where is the config?")))
                .thenReturn("In src/config.yaml, line 12.");

        JsonNode result = ask(c, "scout", "where is the config?");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("In src/config.yaml, line 12.");
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("Asked Scout: where is the config?"));
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("Scout answered"));
    }

    @Test
    void anUnknownWorkerIsRefusedWithTheNamesThatExist() {
        WorkerToolsController c = controller(profile(List.of(), true, null));
        sibling("n2", "Scout");

        JsonNode result = ask(c, "Nobody", "anything?");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("No worker named 'Nobody'").contains("Scout");
        org.mockito.Mockito.verifyNoInteractions(fanout);
    }

    @Test
    void questionsAreCappedPerWorker() {
        WorkerToolsController c = controller(profile(List.of(), true, null));
        sibling("n2", "Scout");
        when(fanout.askAbout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn("yes");

        for (int i = 0; i < WorkerToolsController.MAX_QUESTIONS; i++) {
            assertThat(ask(c, "Scout", "q" + i).path("isError").asBoolean()).isFalse();
        }
        JsonNode sixth = ask(c, "Scout", "one more");

        // A worker that asks instead of working is a loop with extra steps; the sixth is refused
        // with the reason, and never reaches the answering process.
        assertThat(sixth.path("isError").asBoolean()).isTrue();
        assertThat(sixth.path("content").get(0).path("text").asText()).contains("that is the limit");
        org.mockito.Mockito.verify(fanout, org.mockito.Mockito.times(WorkerToolsController.MAX_QUESTIONS))
                .askAbout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
