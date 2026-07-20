package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Any provider speaking OpenAI's {@code /chat/completions} shape.
 *
 * <p>One implementation covers a lot of ground — OpenAI, DeepSeek, Groq, Mistral, xAI, OpenRouter,
 * Together, and local servers (Ollama, vLLM, LM Studio) all expose this format. They differ only
 * in base URL, credential, and model ids, so each is an instance rather than a subclass.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final String id;
    private final String baseUrl;
    private final String apiKey;
    /** Header carrying the credential. Azure OpenAI uses {@code api-key}, not {@code Authorization}. */
    private final String authHeader;
    private final String authPrefix;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleProvider(String id, String baseUrl, String apiKey, ObjectMapper mapper) {
        this(id, baseUrl, apiKey, "Authorization", mapper);
    }

    /**
     * @param authHeader header to put the credential in — {@code Authorization} for OpenAI-style
     *                   vendors, {@code api-key} for Azure OpenAI. Anything other than
     *                   {@code Authorization} is sent as a bare value, since the {@code Bearer}
     *                   prefix is specific to that header.
     */
    public OpenAiCompatibleProvider(String id, String baseUrl, String apiKey, String authHeader,
                                    ObjectMapper mapper) {
        this.id = id;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.authHeader = authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader;
        this.authPrefix = "Authorization".equalsIgnoreCase(this.authHeader) ? "Bearer " : "";
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isConfigured() {
        // A local server (Ollama, vLLM) needs no key, so a base URL alone is enough there.
        return !baseUrl.isBlank() && (apiKey != null && !apiKey.isBlank() || isLoopback());
    }

    private boolean isLoopback() {
        return baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
    }

    @Override
    public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
        ObjectNode body = buildBody(request);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                b.header(authHeader, authPrefix + apiKey);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new LlmException(id, id + " returned " + res.statusCode() + ": " + brief(res.body()));
            }
            return parse(mapper.readTree(res.body()));
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(id, "Interrupted calling " + id, e);
        } catch (Exception e) {
            throw new LlmException(id, "Call to " + id + " failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildBody(ChatTypes.ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        if (request.maxTokens() > 0) body.put("max_tokens", request.maxTokens());

        ArrayNode messages = body.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (ChatTypes.ChatMessage m : request.messages()) {
            messages.add(toWire(m));
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ChatTypes.ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description() == null ? "" : t.description());
                fn.set("parameters", t.parameters());
            }
        }
        return body;
    }

    private ObjectNode toWire(ChatTypes.ChatMessage m) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", m.role());
        // A tool result carries the id of the call it answers; without it the model can't match
        // the result to its request and the turn is rejected.
        if ("tool".equals(m.role())) {
            node.put("tool_call_id", m.toolCallId());
            node.put("content", m.text() == null ? "" : m.text());
            return node;
        }
        node.put("content", m.text() == null ? "" : m.text());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ChatTypes.ToolCall c : m.toolCalls()) {
                ObjectNode call = calls.addObject();
                call.put("id", c.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", c.name());
                fn.put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
            }
        }
        return node;
    }

    private ChatTypes.ChatReply parse(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;

        List<ChatTypes.ToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            calls.add(new ChatTypes.ToolCall(
                    c.path("id").asText(""),
                    c.path("function").path("name").asText(""),
                    c.path("function").path("arguments").asText("{}")));
        }

        JsonNode usage = root.path("usage");
        // prompt_tokens INCLUDES cached tokens on OpenAI, so the cached part is subtracted out to
        // avoid counting it twice — it is priced at a different rate.
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long prompt = usage.path("prompt_tokens").asLong(0);
        return new ChatTypes.ChatReply(text, calls, new ChatTypes.TokenUsage(
                Math.max(0, prompt - cached), usage.path("completion_tokens").asLong(0), cached, 0));
    }

    /** Error bodies can be enormous; keep the console line readable. */
    private static String brief(String body) {
        if (body == null) return "";
        String t = body.strip();
        return t.length() <= 400 ? t : t.substring(0, 400) + "…";
    }
}
