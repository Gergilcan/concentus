package com.concentus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The wire shapes every MCP endpoint in this package shares: the JSON-RPC envelope, the tool-result
 * block, the handshake reply, the method dispatch, and the per-endpoint token comparison.
 *
 * <p>One copy rather than one per controller, because these are protocol shapes rather than
 * decisions: the {@code claude} CLI accepts a response only if the envelope matches, so five
 * independently maintained copies could only ever agree or be wrong. The token compare is here for
 * a sharper reason — it must stay constant-time on every endpoint, and a rule kept in one place is
 * one that cannot be forgotten at the next call site. The published-flow endpoint and the webhook
 * compare their secrets through it for the same reason.
 *
 * @see RunToolsController the flow's API operations
 * @see WorkerToolsController one worker's MCP facade
 * @see PlanToolsController the planner's {@code plan_submit}
 * @see VerdictToolsController the verifier's {@code verdict_submit}
 * @see McpStudioController Concentus itself, for an agent outside
 */
final class McpJsonRpc {

    private McpJsonRpc() {
    }

    /**
     * Routes one JSON-RPC request to an endpoint's handlers, once its token has been accepted.
     *
     * <p>The three handlers are the only things the endpoints differ in; the method names, the
     * notification handling and the error code for anything else are the protocol's, not theirs.
     */
    static ResponseEntity<JsonNode> dispatch(ObjectMapper mapper, JsonNode request,
                                             Supplier<ObjectNode> initialize,
                                             Supplier<ObjectNode> toolsList,
                                             Function<JsonNode, ObjectNode> toolsCall) {
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        return switch (method) {
            case "initialize" -> ok(mapper, id, initialize.get());
            // Notifications carry no id and expect no result.
            case "notifications/initialized", "notifications/cancelled" ->
                    ResponseEntity.accepted().build();
            case "tools/list" -> ok(mapper, id, toolsList.get());
            case "tools/call" -> ok(mapper, id, toolsCall.apply(request.path("params")));
            case "ping" -> ok(mapper, id, mapper.createObjectNode());
            default -> error(mapper, id, -32601, "Method not supported: " + method);
        };
    }

    /**
     * Whether the presented token is the expected one, compared in constant time so a wrong token
     * cannot be recovered by timing the 401. A missing token on either side never matches.
     */
    static boolean tokenMatches(String expected, String presented) {
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code initialize} reply: protocol version, a tools capability, and who answered. */
    static ObjectNode initializeResult(ObjectMapper mapper, String serverName) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", serverName);
        info.put("version", "1.0");
        return result;
    }

    /** MCP tool-result shape: content blocks plus an isError flag the model can react to. */
    static ObjectNode callResult(ObjectMapper mapper, boolean isError, String text) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    static ResponseEntity<JsonNode> ok(ObjectMapper mapper, JsonNode id, ObjectNode result) {
        ObjectNode envelope = envelope(mapper, id);
        envelope.set("result", result);
        return ResponseEntity.ok(envelope);
    }

    static ResponseEntity<JsonNode> error(ObjectMapper mapper, JsonNode id, int code, String message) {
        return ResponseEntity.ok(errorEnvelope(mapper, id, code, message));
    }

    /** The JSON-RPC error envelope on its own, for an endpoint that answers in bytes. */
    static ObjectNode errorEnvelope(ObjectMapper mapper, JsonNode id, int code, String message) {
        ObjectNode envelope = envelope(mapper, id);
        ObjectNode err = envelope.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return envelope;
    }

    /** A notification carries no id, and its reply must not invent one. */
    private static ObjectNode envelope(ObjectMapper mapper, JsonNode id) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) envelope.set("id", id);
        return envelope;
    }
}
