package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talking to a self-hosted model server.
 *
 * <p>The cases pinned here are the ones where local runners differ from the spec they claim to
 * implement, because each failure is silent: a tool call with no id, or arguments sent as an
 * object rather than a string, both produce a model that appears to be malfunctioning rather than
 * an error anyone can act on.
 */
class LocalModelClientTest {

    private HttpServer server;
    private LocalModelClient client;
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    private record Response(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Response next = responses.isEmpty() ? new Response(500, "{}") : responses.poll();
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new LocalModelClient(new ObjectMapper(), baseUrl(), "", 60);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void queue(int status, String body) {
        responses.add(new Response(status, body));
    }

    private static ChatTypes.ChatRequest request(String model) {
        return new ChatTypes.ChatRequest(model, "be brief",
                List.of(ChatTypes.ChatMessage.user("hello")), List.of(), 0);
    }

    @Test
    void anUnconfiguredClientReportsItself() {
        assertThat(new LocalModelClient(new ObjectMapper(), "", "", 60).isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() {
        LocalModelClient trailing = new LocalModelClient(new ObjectMapper(), baseUrl() + "/", "", 60);
        queue(200, """
                {"choices":[{"message":{"content":"hi"}}]}""");

        trailing.chat(request("qwen3:14b"));

        assertThat(paths.getFirst()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void modelsAreListedFromTheServer() {
        queue(200, """
                {"data":[{"id":"qwen3:14b"},{"id":"nomic-embed-text"}]}""");

        assertThat(client.listModels()).containsExactly("qwen3:14b", "nomic-embed-text");
    }

    @Test
    void theSystemPromptLeadsTheMessages() {
        queue(200, """
                {"choices":[{"message":{"content":"hi"}}]}""");

        client.chat(request("qwen3:14b"));

        assertThat(requestBodies.getFirst())
                .contains("\"role\":\"system\"").contains("be brief");
    }

    @Test
    void aPlainAnswerComesBackWithItsUsage() {
        queue(200, """
                {"choices":[{"message":{"content":"the answer"}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":8}}""");

        ChatTypes.ChatReply reply = client.chat(request("qwen3:14b"));

        assertThat(reply.text()).isEqualTo("the answer");
        assertThat(reply.hasToolCalls()).isFalse();
        assertThat(reply.usage().inputTokens()).isEqualTo(120);
        assertThat(reply.usage().outputTokens()).isEqualTo(8);
        // Nothing is billed as cached: on a server you host there are no cache tiers, and
        // reporting some would misprice a run against a rate that does not exist.
        assertThat(reply.usage().cacheReadTokens()).isZero();
    }

    @Test
    void toolCallsWithoutAnIdStillGetOne() {
        // llama.cpp and Ollama both omit it at times. The id is what pairs a result back to its
        // request, so an empty one makes the next turn unmatchable and the model sees its own
        // tool answer vanish.
        queue(200, """
                {"choices":[{"message":{"tool_calls":[
                  {"function":{"name":"read_file","arguments":"{}"}},
                  {"function":{"name":"list_files","arguments":"{}"}}]}}]}""");

        ChatTypes.ChatReply reply = client.chat(request("qwen3:14b"));

        assertThat(reply.toolCalls()).extracting(ChatTypes.ToolCall::id)
                .containsExactly("call_0", "call_1");
    }

    @Test
    void aServerSuppliedCallIdIsKept() {
        queue(200, """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call_abc","function":{"name":"read_file","arguments":"{}"}}]}}]}""");

        assertThat(client.chat(request("qwen3:14b")).toolCalls().getFirst().id()).isEqualTo("call_abc");
    }

    @Test
    void argumentsSentAsAnObjectAreNotLost() {
        // The wire format says this is a string containing JSON; local runners routinely send an
        // object. asText() on an object is "", which reaches the tool as "no arguments" — the
        // model then looks like it called the tool with nothing.
        queue(200, """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"c1","function":{"name":"read_file","arguments":{"path":"README.md"}}}]}}]}""");

        String args = client.chat(request("qwen3:14b")).toolCalls().getFirst().argumentsJson();

        assertThat(args).contains("README.md");
    }

    @Test
    void argumentsSentAsAStringPassThroughUnchanged() {
        queue(200, """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"c1","function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}]}""");

        assertThat(client.chat(request("qwen3:14b")).toolCalls().getFirst().argumentsJson())
                .isEqualTo("{\"path\":\"a.txt\"}");
    }

    @Test
    void missingArgumentsBecomeAnEmptyObjectRatherThanNull() {
        queue(200, """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"c1","function":{"name":"list_files"}}]}}]}""");

        assertThat(client.chat(request("qwen3:14b")).toolCalls().getFirst().argumentsJson())
                .isEqualTo("{}");
    }

    @Test
    void aMissingModelSaysToPullIt() {
        queue(404, """
                {"error":{"message":"model 'qwen3:14b' not found"}}""");

        assertThatThrownBy(() -> client.chat(request("qwen3:14b")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("ollama pull qwen3:14b");
    }

    @Test
    void aToolRejectionNamesToolSupportAsTheLikelyCause() {
        queue(400, """
                {"error":"this model does not support tools"}""");

        assertThatThrownBy(() -> client.chat(request("qwen3:14b")))
                .hasMessageContaining("tool calling");
    }

    @Test
    void anAuthFailurePointsAtTheApiKeySetting() {
        queue(401, "{}");

        assertThatThrownBy(() -> client.chat(request("qwen3:14b")))
                .hasMessageContaining("LOCAL_MODEL_API_KEY");
    }

    @Test
    void anUnreachableServerSaysWhereItLooked() {
        // Port 1 is reserved and never listening — a connection refusal, not a timeout.
        LocalModelClient dead = new LocalModelClient(new ObjectMapper(), "http://127.0.0.1:1/v1", "", 60);

        assertThatThrownBy(() -> dead.chat(request("qwen3:14b")))
                .hasMessageContaining("127.0.0.1:1")
                .hasMessageContaining("/v1");
    }

    @Test
    void toolDefinitionsAreSentInTheOpenAiShape() {
        ObjectMapper mapper = new ObjectMapper();
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        queue(200, """
                {"choices":[{"message":{"content":"ok"}}]}""");

        client.chat(new ChatTypes.ChatRequest("qwen3:14b", null,
                List.of(ChatTypes.ChatMessage.user("go")),
                List.of(new ChatTypes.ToolSpec("read_file", "Reads a file", schema)), 0));

        assertThat(requestBodies.getFirst())
                .contains("\"type\":\"function\"")
                .contains("\"name\":\"read_file\"");
    }

    @Test
    void aToolResultCarriesTheCallItAnswers() {
        queue(200, """
                {"choices":[{"message":{"content":"done"}}]}""");

        client.chat(new ChatTypes.ChatRequest("qwen3:14b", null,
                List.of(ChatTypes.ChatMessage.user("go"),
                        ChatTypes.ChatMessage.assistant(null,
                                List.of(new ChatTypes.ToolCall("c1", "read_file", "{}"))),
                        ChatTypes.ChatMessage.toolResult("c1", "file contents")),
                List.of(), 0));

        // Without tool_call_id the model can't match the result to its request and rejects the turn.
        assertThat(requestBodies.getFirst()).contains("\"tool_call_id\":\"c1\"");
    }
}
