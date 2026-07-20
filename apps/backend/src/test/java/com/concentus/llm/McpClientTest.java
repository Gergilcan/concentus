package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP client behaviour against a stub server.
 *
 * <p>The transport has several rules a hand-rolled client silently gets wrong: a request response
 * may be JSON or an SSE stream, a session id issued at initialize must be echoed afterwards, and a
 * failed tool call is a 200 with {@code isError} rather than a JSON-RPC error. Each is pinned.
 */
class McpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<JsonNode> received = new ArrayList<>();
    private final List<String> receivedSessionHeaders = new ArrayList<>();
    private final List<String> receivedAuthHeaders = new ArrayList<>();

    /** Maps a parsed request to a canned reply. */
    private volatile Function<JsonNode, Reply> handler = r -> Reply.json("{}");

    private record Reply(int status, String contentType, String body, String sessionId) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body, null);
        }

        static Reply jsonWithSession(String body, String sessionId) {
            return new Reply(200, "application/json", body, sessionId);
        }

        static Reply sse(String body) {
            return new Reply(200, "text/event-stream", body, null);
        }

        static Reply status(int status) {
            return new Reply(status, "application/json", "", null);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedSessionHeaders.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            Reply reply;
            if (body.isBlank()) {
                reply = Reply.status(202);
            } else {
                JsonNode parsed = mapper.readTree(body);
                received.add(parsed);
                // A notification has no id and is answered 202 with no body.
                reply = parsed.has("id") ? handler.apply(parsed) : Reply.status(202);
            }

            if (reply.sessionId() != null) {
                exchange.getResponseHeaders().add("MCP-Session-Id", reply.sessionId());
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            if (out.length == 0) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                exchange.sendResponseHeaders(reply.status(), out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private McpClient client(String token) {
        return new McpClient("github", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                token, mapper);
    }

    private static String initResult(String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"" + version
                + "\",\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"1.0.0\"}}}";
    }

    private static String toolsResult(String name, String nextCursor) {
        String cursor = nextCursor == null ? "" : ",\"nextCursor\":\"" + nextCursor + "\"";
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"" + name
                + "\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\","
                + "\"properties\":{\"q\":{\"type\":\"string\"}}}}]" + cursor + "}}";
    }

    @Test
    void handshakeSendsInitializeThenTheInitializedNotification() {
        handler = r -> Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION));

        client(null).initialize();

        assertThat(received.get(0).get("method").asText()).isEqualTo("initialize");
        assertThat(received.get(0).get("params").get("protocolVersion").asText())
                .isEqualTo(McpClient.PREFERRED_PROTOCOL_VERSION);
        // Required by the spec before normal operations, and it carries no id.
        assertThat(received.get(1).get("method").asText()).isEqualTo("notifications/initialized");
        assertThat(received.get(1).has("id")).isFalse();
    }

    @Test
    void aSessionIdIssuedAtInitializeIsEchoedOnLaterRequests() {
        handler = r -> "initialize".equals(r.path("method").asText())
                ? Reply.jsonWithSession(initResult(McpClient.PREFERRED_PROTOCOL_VERSION), "sess-42")
                : Reply.json(toolsResult("search", null));

        client(null).listTools();

        // First request can't carry it; everything after must.
        assertThat(receivedSessionHeaders.get(0)).isNull();
        assertThat(receivedSessionHeaders.subList(1, receivedSessionHeaders.size()))
                .isNotEmpty()
                .allMatch("sess-42"::equals);
    }

    @Test
    void aBearerTokenIsSentOnEveryRequest() {
        handler = r -> "initialize".equals(r.path("method").asText())
                ? Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION))
                : Reply.json(toolsResult("search", null));

        client("ghp_secret").listTools();

        assertThat(receivedAuthHeaders).isNotEmpty().allMatch("Bearer ghp_secret"::equals);
    }

    @Test
    void toolsAreReadFromAJsonResponse() {
        handler = r -> "initialize".equals(r.path("method").asText())
                ? Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION))
                : Reply.json(toolsResult("search_issues", null));

        List<ChatTypes.ToolSpec> tools = client(null).listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search_issues");
        assertThat(tools.get(0).parameters().get("properties").has("q")).isTrue();
    }

    @Test
    void toolsAreAlsoReadFromAnSseResponse() {
        // The server picks the content type; a client that only handles JSON breaks on servers
        // that stream.
        handler = r -> "initialize".equals(r.path("method").asText())
                ? Reply.sse("event: message\ndata: " + initResult(McpClient.PREFERRED_PROTOCOL_VERSION) + "\n\n")
                : Reply.sse("event: message\ndata: " + toolsResult("search", null) + "\n\n");

        List<ChatTypes.ToolSpec> tools = client(null).listTools();

        assertThat(tools).extracting(ChatTypes.ToolSpec::name).containsExactly("search");
    }

    @Test
    void ourResponseIsPickedOutOfAnInterleavedSseStream() {
        // Servers may interleave their own notifications; matching on the request id is what
        // makes taking "the first frame" wrong.
        handler = r -> {
            if ("initialize".equals(r.path("method").asText())) {
                return Reply.sse("data: " + initResult(McpClient.PREFERRED_PROTOCOL_VERSION) + "\n\n");
            }
            return Reply.sse("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                    + "data: " + toolsResult("search", null) + "\n\n");
        };

        assertThat(client(null).listTools()).extracting(ChatTypes.ToolSpec::name).containsExactly("search");
    }

    @Test
    void paginationFollowsNextCursorToTheEnd() {
        handler = r -> {
            if ("initialize".equals(r.path("method").asText())) {
                return Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION));
            }
            boolean firstPage = !r.path("params").has("cursor");
            return Reply.json(firstPage ? toolsResult("page1", "c2") : toolsResult("page2", null));
        };

        assertThat(client(null).listTools())
                .extracting(ChatTypes.ToolSpec::name)
                .containsExactly("page1", "page2");
    }

    @Test
    void aFailedToolCallComesBackAsTextForTheModelToReadNotAnException() {
        handler = r -> {
            if ("initialize".equals(r.path("method").asText())) {
                return Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION));
            }
            return Reply.json("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"Invalid date format\"}],\"isError\":true}}");
        };

        String out = client(null).callTool("book", "{}");

        // The tool ran and failed — the model can correct itself from this.
        assertThat(out).contains("Invalid date format");
    }

    @Test
    void aProtocolErrorIsThrownBecauseTheCallNeverRan() {
        handler = r -> {
            if ("initialize".equals(r.path("method").asText())) {
                return Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION));
            }
            return Reply.json("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":"
                    + "{\"code\":-32602,\"message\":\"Unknown tool: nope\"}}");
        };

        assertThatThrownBy(() -> client(null).callTool("nope", "{}"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Unknown tool");
    }

    @Test
    void toolResultTextIsFlattenedAndNonTextPartsAreNamedNotDropped() {
        handler = r -> {
            if ("initialize".equals(r.path("method").asText())) {
                return Reply.json(initResult(McpClient.PREFERRED_PROTOCOL_VERSION));
            }
            return Reply.json("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"line one\"},"
                    + "{\"type\":\"image\",\"data\":\"...\",\"mimeType\":\"image/png\"},"
                    + "{\"type\":\"text\",\"text\":\"line two\"}]}}");
        };

        String out = client(null).callTool("t", "{}");

        assertThat(out).contains("line one").contains("line two");
        // Silently dropping it would leave the model believing it saw everything.
        assertThat(out).contains("image content omitted");
    }

    @Test
    void anInitializeErrorIsSurfacedRatherThanLeavingTheClientHalfReady() {
        handler = r -> Reply.json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                + "{\"code\":-32602,\"message\":\"Unsupported protocol version\"}}");

        assertThatThrownBy(() -> client(null).initialize())
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Unsupported protocol version");
    }
}
