package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format tests for the Gemini adapter against a stub server.
 *
 * <p>Gemini's shape diverges from OpenAI's in ways that fail silently if got wrong — different
 * role names, tool results carried as a user turn, arguments as an object rather than a string,
 * and no tool-call finish reason. Each of those is pinned here.
 */
class GeminiProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
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

    /**
     * Builds a provider pointed at the stub. The public factories hard-code Google's hosts, so the
     * private constructor is used to redirect the endpoint — the code under test is the body
     * codec, not the URL constant.
     */
    private GeminiProvider provider() throws Exception {
        String template = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/%s:generateContent";
        Constructor<GeminiProvider> ctor = GeminiProvider.class.getDeclaredConstructor(
                String.class, String.class, String.class, java.util.function.Supplier.class, ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance("gemini", template, "test-key", null, mapper);
    }

    private JsonNode sent() throws Exception {
        return mapper.readTree(lastBody.get());
    }

    private static ChatTypes.ChatRequest req(String system, List<ChatTypes.ChatMessage> messages,
                                             List<ChatTypes.ToolSpec> tools) {
        return new ChatTypes.ChatRequest("gemini-3.5-flash", system, messages, tools, 500);
    }

    @Test
    void systemPromptGoesToSystemInstructionAndTheKeyIsAHeader() throws Exception {
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}";

        ChatTypes.ChatReply reply = provider().chat(
                req("Be terse.", List.of(ChatTypes.ChatMessage.user("hello")), List.of()));

        assertThat(lastApiKeyHeader.get()).isEqualTo("test-key");
        // Not a query param — keeps the credential out of URLs and access logs.
        assertThat(lastPath.get()).doesNotContain("test-key");
        assertThat(sent().get("systemInstruction").get("parts").get(0).get("text").asText())
                .isEqualTo("Be terse.");
        assertThat(reply.text()).isEqualTo("hi");
    }

    @Test
    void assistantTurnsUseTheModelRoleNotAssistant() throws Exception {
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";

        provider().chat(req(null, List.of(
                ChatTypes.ChatMessage.user("hi"),
                ChatTypes.ChatMessage.assistant("hello", List.of())), List.of()));

        JsonNode contents = sent().get("contents");
        assertThat(contents.get(0).get("role").asText()).isEqualTo("user");
        assertThat(contents.get(1).get("role").asText()).isEqualTo("model");
    }

    @Test
    void allToolsGoInOneFunctionDeclarationsArray() throws Exception {
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";
        JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{}}");

        provider().chat(req(null, List.of(ChatTypes.ChatMessage.user("go")), List.of(
                new ChatTypes.ToolSpec("a", "first", schema),
                new ChatTypes.ToolSpec("b", "second", schema))));

        JsonNode tools = sent().get("tools");
        // One element holding both declarations — not one element per tool.
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("functionDeclarations")).hasSize(2);
    }

    @Test
    void detectsToolCallsFromThePartsRatherThanAFinishReason() throws Exception {
        // Gemini has no tool-call finish reason; finishReason stays STOP.
        responseBody = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
                + "{\"functionCall\":{\"id\":\"c1\",\"name\":\"delegate\","
                + "\"args\":{\"task\":\"review\"}}}]}}]}";

        ChatTypes.ChatReply reply = provider().chat(req(null,
                List.of(ChatTypes.ChatMessage.user("go")), List.of()));

        assertThat(reply.hasToolCalls()).isTrue();
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("delegate");
        assertThat(reply.toolCalls().get(0).id()).isEqualTo("c1");
        // args arrive as an object and are normalised to raw JSON text.
        assertThat(reply.toolCalls().get(0).argumentsJson()).contains("review");
    }

    @Test
    void aToolResultIsSentAsAUserTurnWithFunctionResponse() throws Exception {
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]}}]}";

        provider().chat(req(null, List.of(
                ChatTypes.ChatMessage.user("go"),
                ChatTypes.ChatMessage.assistant(null,
                        List.of(new ChatTypes.ToolCall("delegate", "delegate", "{}"))),
                ChatTypes.ChatMessage.toolResult("delegate", "reviewed")), List.of()));

        JsonNode contents = sent().get("contents");
        JsonNode last = contents.get(contents.size() - 1);
        assertThat(last.get("role").asText()).isEqualTo("user");
        JsonNode fr = last.get("parts").get(0).get("functionResponse");
        assertThat(fr.get("name").asText()).isEqualTo("delegate");
        // `response` must be an object, so a plain string is wrapped.
        assertThat(fr.get("response").get("result").asText()).isEqualTo("reviewed");
    }

    @Test
    void thinkingTokensCountAsOutputAndCachedSplitsOutOfPrompt() throws Exception {
        // thoughtsTokenCount bills as output but sits OUTSIDE candidatesTokenCount, so omitting
        // it would undercount every reasoning turn.
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1000,\"cachedContentTokenCount\":600,"
                + "\"candidatesTokenCount\":40,\"thoughtsTokenCount\":25}}";

        ChatTypes.TokenUsage usage = provider().chat(
                req(null, List.of(ChatTypes.ChatMessage.user("go")), List.of())).usage();

        assertThat(usage.inputTokens()).isEqualTo(400L);
        assertThat(usage.cacheReadTokens()).isEqualTo(600L);
        assertThat(usage.outputTokens()).isEqualTo(65L);
    }
}
