package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Model Context Protocol client over Streamable HTTP.
 *
 * <p>MCP is an open protocol, so its tools are usable from any model — this client fetches a
 * server's tool list and forwards calls, letting the api backend offer MCP tools to OpenAI,
 * Gemini or anything else. (The Claude backends reach the same servers through the CLI instead.)
 *
 * <p>Three details of the transport are easy to get wrong and are handled here:
 * <ul>
 *   <li>a response to a request may come back as {@code application/json} <em>or</em> as an SSE
 *       stream, and a client must accept both;</li>
 *   <li>the server may issue a session id at initialize which must then be echoed on every later
 *       request, and a 404 means the session died and initialize must be redone;</li>
 *   <li>a failed tool call arrives as a normal 200 with {@code isError: true} — distinct from a
 *       JSON-RPC {@code error}, which means the call never ran.</li>
 * </ul>
 */
public class McpClient implements AutoCloseable {

    /** Latest final spec version at time of writing; the server may negotiate us down. */
    static final String PREFERRED_PROTOCOL_VERSION = "2025-11-25";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final String serverName;
    private final URI endpoint;
    private final String bearerToken;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile boolean initialized;

    public McpClient(String serverName, String url, String bearerToken, ObjectMapper mapper) {
        this.serverName = serverName;
        this.endpoint = URI.create(url);
        this.bearerToken = bearerToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public String serverName() {
        return serverName;
    }

    /** Handshake: initialize, remember what the server negotiated, then confirm readiness. */
    public synchronized void initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PREFERRED_PROTOCOL_VERSION);
        // Declaring nothing is deliberate: we implement no server-to-client calls, and the spec
        // requires both sides to use only capabilities that were actually negotiated.
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "concentus").put("version", "1.0.0");

        Response res = send("initialize", params, true);
        JsonNode result = res.body().path("result");
        if (res.body().has("error")) {
            throw new LlmException(serverName, "MCP initialize failed: "
                    + res.body().path("error").path("message").asText("unknown"));
        }
        this.sessionId = res.sessionId();
        this.protocolVersion = result.path("protocolVersion").asText(PREFERRED_PROTOCOL_VERSION);
        // A notification, so the server answers 202 with no body — nothing to parse.
        send("notifications/initialized", null, false);
        this.initialized = true;
    }

    /** All tools the server exposes, following pagination to the end. */
    public List<ChatTypes.ToolSpec> listTools() {
        if (!initialized) initialize();
        List<ChatTypes.ToolSpec> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = requireResult(send("tools/list", params, true));

            for (JsonNode t : result.path("tools")) {
                JsonNode schema = t.path("inputSchema");
                tools.add(new ChatTypes.ToolSpec(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        schema.isObject() ? schema : emptyObjectSchema()));
            }
            cursor = result.path("nextCursor").isTextual() ? result.path("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    /**
     * Calls a tool and returns its text content.
     *
     * <p>A tool that ran and failed comes back as text so the model can correct itself; only a
     * protocol-level error (the call never ran) is thrown.
     */
    public String callTool(String name, String argumentsJson) {
        if (!initialized) initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        try {
            JsonNode args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
            params.set("arguments", args.isObject() ? args : mapper.createObjectNode());
        } catch (Exception e) {
            params.putObject("arguments");
        }

        JsonNode result = requireResult(send("tools/call", params, true));
        String text = textOf(result.path("content"));
        return result.path("isError").asBoolean(false)
                ? "The tool reported an error: " + text
                : text;
    }

    /** Flattens the content array; non-text parts are named rather than dropped silently. */
    private static String textOf(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type)) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(part.path("text").asText(""));
            } else if (!type.isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("(").append(type).append(" content omitted)");
            }
        }
        return sb.toString();
    }

    private JsonNode requireResult(Response res) {
        if (res.body().has("error")) {
            // The call never ran — a model retry can't fix a protocol error.
            throw new LlmException(serverName, "MCP error from " + serverName + ": "
                    + res.body().path("error").path("message").asText("unknown"));
        }
        return res.body().path("result");
    }

    private ObjectNode emptyObjectSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private record Response(JsonNode body, String sessionId) {
    }

    private Response send(String method, ObjectNode params, boolean expectResult) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        if (expectResult) request.put("id", nextId.getAndIncrement());
        request.put("method", method);
        if (params != null) request.set("params", params);

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    // Both are required: the server chooses which to answer with.
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
            if (bearerToken != null && !bearerToken.isBlank()) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            if (sessionId != null) b.header("MCP-Session-Id", sessionId);
            // Only after initialize — the negotiated version isn't known before then.
            if (protocolVersion != null) b.header("MCP-Protocol-Version", protocolVersion);

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 404 && sessionId != null) {
                // The server dropped our session; a fresh initialize is the prescribed recovery.
                sessionId = null;
                protocolVersion = null;
                initialized = false;
                throw new LlmException(serverName, "MCP session expired for " + serverName + "; retry.");
            }
            if (res.statusCode() / 100 != 2) {
                throw new LlmException(serverName,
                        serverName + " returned " + res.statusCode() + ": " + brief(res.body()));
            }
            // Notifications are answered 202 with no body.
            if (!expectResult) return new Response(mapper.createObjectNode(), sessionId);

            String newSession = res.headers().firstValue("MCP-Session-Id").orElse(sessionId);
            String contentType = res.headers().firstValue("Content-Type").orElse("");
            JsonNode body = contentType.contains("text/event-stream")
                    ? parseSse(res.body(), request.path("id").asInt())
                    : mapper.readTree(res.body());
            return new Response(body, newSession);
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(serverName, "Interrupted calling " + serverName, e);
        } catch (Exception e) {
            throw new LlmException(serverName, "MCP call to " + serverName + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pulls our response out of an SSE stream.
     *
     * <p>The server may interleave its own requests and notifications, so the frame whose id
     * matches ours is the one to take rather than simply the first or last.
     */
    private JsonNode parseSse(String body, int requestId) {
        JsonNode fallback = null;
        for (String block : body.split("\r?\n\r?\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\r?\n")) {
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            if (data.isEmpty()) continue;
            try {
                JsonNode frame = mapper.readTree(data.toString());
                if (frame.path("id").asInt(-1) == requestId) return frame;
                if (frame.has("result") || frame.has("error")) fallback = frame;
            } catch (Exception ignored) {
                // A frame we can't parse isn't ours; keep looking.
            }
        }
        if (fallback != null) return fallback;
        throw new LlmException(serverName, "No JSON-RPC response found in the SSE stream from " + serverName);
    }

    private static String brief(String body) {
        if (body == null) return "";
        String t = body.strip();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }

    @Override
    public void close() {
        // Best effort: tell the server the session is done so it can release resources.
        if (sessionId == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("MCP-Session-Id", sessionId)
                    .DELETE();
            if (bearerToken != null && !bearerToken.isBlank()) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 405 is a valid answer here, and a failed cleanup must never fail a run.
        } finally {
            sessionId = null;
            initialized = false;
        }
    }
}
