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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-format tests for the OpenAI-compatible provider, run against a stub HTTP server.
 *
 * <p>The shape of the request is the whole contract with a vendor — a wrong field name fails at
 * runtime against a real endpoint and nowhere else. Serving a canned response locally pins both
 * halves (what we send, what we parse) without a network call or an API key.
 */
class OpenAiCompatibleProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider("openai",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-test", mapper);
    }

    private JsonNode sentBody() throws Exception {
        return mapper.readTree(lastRequestBody.get());
    }

    private static ChatTypes.ChatRequest req(String system, List<ChatTypes.ChatMessage> messages,
                                             List<ChatTypes.ToolSpec> tools) {
        return new ChatTypes.ChatRequest("gpt-5", system, messages, tools, 1000);
    }

    @Test
    void sendsSystemPromptAsTheFirstMessageAndCarriesTheApiKey() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";

        ChatTypes.ChatReply reply = provider().chat(
                req("You are terse.", List.of(ChatTypes.ChatMessage.user("hello")), List.of()));

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer sk-test");
        JsonNode messages = sentBody().get("messages");
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("You are terse.");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(reply.text()).isEqualTo("hi");
    }

    @Test
    void sendsToolsInTheFunctionWrapperShape() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{}}";
        JsonNode schema = mapper.readTree(
                "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\"}},\"required\":[\"task\"]}");

        provider().chat(req(null, List.of(ChatTypes.ChatMessage.user("go")),
                List.of(new ChatTypes.ToolSpec("delegate", "Hand work to an agent", schema))));

        JsonNode tool = sentBody().get("tools").get(0);
        // The nesting is load-bearing: name/description/parameters live under "function".
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("function").get("name").asText()).isEqualTo("delegate");
        assertThat(tool.get("function").get("parameters").get("required").get(0).asText()).isEqualTo("task");
    }

    @Test
    void parsesToolCallsOffTheResponse() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                + "{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"delegate\",\"arguments\":\"{\\\"task\\\":\\\"review\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}";

        ChatTypes.ChatReply reply = provider().chat(
                req(null, List.of(ChatTypes.ChatMessage.user("go")), List.of()));

        assertThat(reply.hasToolCalls()).isTrue();
        assertThat(reply.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("delegate");
        // Arguments stay raw — the caller parses them against the tool's own schema.
        assertThat(reply.toolCalls().get(0).argumentsJson()).contains("review");
    }

    @Test
    void cachedTokensAreSplitOutOfPromptTokens() {
        // prompt_tokens INCLUDES the cached portion; counting both would double-count it and
        // price the cached part at the full input rate.
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"x\"}}],"
                + "\"usage\":{\"prompt_tokens\":10000,\"completion_tokens\":50,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":9000}}}";

        ChatTypes.TokenUsage usage = provider().chat(
                req(null, List.of(ChatTypes.ChatMessage.user("go")), List.of())).usage();

        assertThat(usage.inputTokens()).isEqualTo(1000L);
        assertThat(usage.cacheReadTokens()).isEqualTo(9000L);
        assertThat(usage.outputTokens()).isEqualTo(50L);
    }

    @Test
    void aToolResultCarriesTheIdOfTheCallItAnswers() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"done\"}}],\"usage\":{}}";

        provider().chat(req(null, List.of(
                ChatTypes.ChatMessage.user("go"),
                ChatTypes.ChatMessage.assistant(null,
                        List.of(new ChatTypes.ToolCall("call_1", "delegate", "{}"))),
                ChatTypes.ChatMessage.toolResult("call_1", "reviewed")), List.of()));

        JsonNode messages = sentBody().get("messages");
        JsonNode toolMsg = messages.get(messages.size() - 1);
        assertThat(toolMsg.get("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.get("tool_call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void aNon2xxResponseNamesTheProviderAndTheStatus() {
        status = 429;
        responseBody = "{\"error\":{\"message\":\"rate limited\"}}";

        assertThatThrownBy(() -> provider().chat(
                req(null, List.of(ChatTypes.ChatMessage.user("go")), List.of())))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("429")
                .hasMessageContaining("rate limited");
    }

    @Test
    void azureStyleVendorsCarryTheKeyInAnApiKeyHeaderNotAuthorization() throws Exception {
        // Azure OpenAI rejects `Authorization: Bearer <key>` for key auth — it wants a bare
        // `api-key` header, and the Bearer prefix must not be added to it.
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{}}";
        OpenAiCompatibleProvider azure = new OpenAiCompatibleProvider("azure",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "azure-key",
                "api-key", mapper);

        azure.chat(req(null, List.of(ChatTypes.ChatMessage.user("hi")), List.of()));

        assertThat(lastApiKeyHeader.get()).isEqualTo("azure-key");
        assertThat(lastAuthHeader.get()).isNull();
    }

    @Test
    void aLocalServerNeedsNoApiKey() {
        // Ollama / vLLM expose this same API without auth; requiring a key would lock them out.
        OpenAiCompatibleProvider local = new OpenAiCompatibleProvider(
                "ollama", "http://localhost:11434/v1", "", mapper);
        assertThat(local.isConfigured()).isTrue();

        OpenAiCompatibleProvider remote = new OpenAiCompatibleProvider(
                "openai", "https://api.openai.com/v1", "", mapper);
        assertThat(remote.isConfigured()).isFalse();
    }
}
