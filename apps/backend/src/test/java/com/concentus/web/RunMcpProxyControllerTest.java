package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The claude CLI's route to an OAuth-protected MCP server.
 *
 * <p>The CLI reads its servers from a config file written once, at the start of the run, so a token
 * put in that file is the token the whole run uses — and Resend's lasts fifteen minutes. Nothing in
 * the backend can fix that from the outside, because the CLI talks to the server directly and the
 * 401 never passes through here. Pointing the config at this endpoint instead is what puts the
 * renewal back in reach: one hop, per request, with the token resolved at the moment of the call.
 */
class RunMcpProxyControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer remote;
    private AgentRun run;
    private RunService runs;
    private McpOAuthStore oauth;

    /** The only token the remote server accepts. */
    private volatile String accepted = "granted-2";
    private final List<String> tokensSeen = new ArrayList<>();
    private final AtomicInteger rejections = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/mcp", ex -> {
            ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            tokensSeen.add(auth);
            if (!("Bearer " + accepted).equals(auth)) {
                rejections.incrementAndGet();
                respond(ex, 401, "{\"error\":\"token expired\"}");
                return;
            }
            ex.getResponseHeaders().add("MCP-Session-Id", "s-9");
            respond(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
        });
        remote.start();

        run = new AgentRun("run-1", "flow-1", "Google Ads — Tecnovent", "local");
        AgentSpec coordinator = new AgentSpec();
        coordinator.name = "Estratega";
        AgentSpec.McpServerSpec server = new AgentSpec.McpServerSpec();
        server.name = "resend";
        server.url = "http://127.0.0.1:" + remote.getAddress().getPort() + "/mcp";
        coordinator.mcpServers.add(server);
        run.compiled = new CompiledFlow(coordinator, List.of());
        run.toolToken = "tok-run";

        runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        oauth = mock(McpOAuthStore.class);
        when(oauth.accessToken(anyString(), anyString())).thenReturn(Optional.of("granted-1"));
        when(oauth.renewedAccessToken(anyString(), anyString())).thenReturn(Optional.of("granted-2"));
    }

    @AfterEach
    void stop() {
        remote.stop(0);
    }

    private RunMcpProxyController controller() {
        OrgContext org = mock(OrgContext.class);
        when(org.defaultOrganizationId()).thenReturn("local");
        return new RunMcpProxyController(runs, oauth, org, MAPPER);
    }

    private ResponseEntity<byte[]> call(String token) {
        return controller().proxy("run-1", "resend", token, null, null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_wrong_token_is_refused_before_anything_reaches_the_server() {
        assertThat(call("wrong").getStatusCode().value()).isEqualTo(401);

        assertThat(tokensSeen).isEmpty();
    }

    @Test
    void an_unknown_server_name_is_not_a_hole_to_call_anything_through() {
        ResponseEntity<byte[]> res = controller().proxy("run-1", "not-in-this-flow", "tok-run",
                null, null, "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(tokensSeen).isEmpty();
    }

    @Test
    void renews_the_grant_and_repeats_the_call_when_the_server_rejects_the_token() {
        ResponseEntity<byte[]> res = call("tok-run");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(res.getBody(), StandardCharsets.UTF_8)).contains("\"ok\":true");
        // The first attempt carried the stale grant, the second the renewed one.
        assertThat(tokensSeen).containsExactly("Bearer granted-1", "Bearer granted-2");
        assertThat(rejections).hasValue(1);
    }

    @Test
    void passes_the_servers_session_id_back_so_the_handshake_survives_the_hop() {
        ResponseEntity<byte[]> res = call("tok-run");

        assertThat(res.getHeaders().getFirst("MCP-Session-Id")).isEqualTo("s-9");
    }

    @Test
    void a_grant_that_cannot_be_renewed_stops_the_run_and_names_the_server() {
        when(oauth.renewedAccessToken(anyString(), anyString())).thenReturn(Optional.empty());

        ResponseEntity<byte[]> res = call("tok-run");

        String body = new String(res.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("resend").contains("authoriz");
        assertThat(run.error).contains("resend");
        assertThat(run.bufferedEvents()).anySatisfy(e -> assertThat(e.text()).contains("resend"));
        verify(runs).stop("run-1");
        // One rejected attempt, and no second one: there was no new token to try.
        assertThat(rejections).hasValue(1);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
