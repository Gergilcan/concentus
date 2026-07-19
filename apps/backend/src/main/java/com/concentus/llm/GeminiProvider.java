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
import java.util.function.Supplier;

/**
 * Google Gemini via {@code generateContent}, covering both the Gemini Developer API and Vertex AI.
 *
 * <p>The two differ only in endpoint and credential — the request and response bodies are the
 * same — so they are one class with an endpoint/auth strategy rather than two.
 *
 * <p>Gemini's shape differs from OpenAI's in several load-bearing ways, all handled here:
 * <ul>
 *   <li>roles are {@code user} / {@code model}, not {@code assistant};</li>
 *   <li>a tool <em>result</em> is sent under role {@code user} as a {@code functionResponse} part,
 *       not a dedicated tool role;</li>
 *   <li>call arguments arrive as a real JSON object, not a stringified blob;</li>
 *   <li>there is no tool-call finish reason — you detect tool use by scanning the parts.</li>
 * </ul>
 *
 * <p><b>Roadmap note:</b> Google now documents {@code generateContent} as legacy in favour of a
 * newer Interactions API, with new models and agentic features landing only there. This adapter
 * targets {@code generateContent} because that shape is verifiable and works today; the provider
 * seam is what keeps swapping it a contained change.
 */
public class GeminiProvider implements LlmProvider {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final String id;
    private final String apiKey;
    /** Vertex: an OAuth token supplier. Null for the Developer API, which uses an API key. */
    private final Supplier<String> bearerToken;
    private final String endpointTemplate;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private GeminiProvider(String id, String endpointTemplate, String apiKey,
                           Supplier<String> bearerToken, ObjectMapper mapper) {
        this.id = id;
        this.endpointTemplate = endpointTemplate;
        this.apiKey = apiKey;
        this.bearerToken = bearerToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** Gemini Developer API (generativelanguage.googleapis.com), authenticated with an API key. */
    public static GeminiProvider developerApi(String apiKey, ObjectMapper mapper) {
        return new GeminiProvider("gemini",
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
                apiKey, null, mapper);
    }

    /**
     * Vertex AI. Same bodies, but a project/region endpoint and an OAuth bearer token
     * (`gcloud auth print-access-token`) rather than an API key.
     */
    public static GeminiProvider vertex(String project, String region, Supplier<String> bearerToken,
                                        ObjectMapper mapper) {
        String host = "global".equals(region)
                ? "https://aiplatform.googleapis.com"
                : "https://" + region + "-aiplatform.googleapis.com";
        String template = host + "/v1/projects/" + project + "/locations/" + region
                + "/publishers/google/models/%s:generateContent";
        return new GeminiProvider("vertex", template, null, bearerToken, mapper);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isConfigured() {
        if (bearerToken != null) {
            String t = bearerToken.get();
            return t != null && !t.isBlank();
        }
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(endpointTemplate, request.model())))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(buildBody(request))));
            if (bearerToken != null) {
                b.header("Authorization", "Bearer " + bearerToken.get());
            } else {
                // Header rather than ?key= so the credential stays out of URLs and access logs.
                b.header("x-goog-api-key", apiKey);
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

        if (request.system() != null && !request.system().isBlank()) {
            body.putObject("systemInstruction").putArray("parts")
                    .addObject().put("text", request.system());
        }
        if (request.maxTokens() > 0) {
            body.putObject("generationConfig").put("maxOutputTokens", request.maxTokens());
        }

        ArrayNode contents = body.putArray("contents");
        for (ChatTypes.ChatMessage m : request.messages()) {
            contents.add(toContent(m));
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            // tools is an array whose single element holds ALL declarations — not one element
            // per tool.
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ChatTypes.ToolSpec t : request.tools()) {
                ObjectNode fn = declarations.addObject();
                fn.put("name", t.name());
                fn.put("description", t.description() == null ? "" : t.description());
                fn.set("parameters", t.parameters());
            }
        }
        return body;
    }

    private ObjectNode toContent(ChatTypes.ChatMessage m) {
        ObjectNode content = mapper.createObjectNode();

        if ("tool".equals(m.role())) {
            // A result goes back as a user turn carrying a functionResponse part. `response` must
            // be an object, so a plain string result is wrapped.
            content.put("role", "user");
            ObjectNode fr = content.putArray("parts").addObject().putObject("functionResponse");
            fr.put("name", m.toolCallId() == null ? "" : m.toolCallId());
            fr.putObject("response").put("result", m.text() == null ? "" : m.text());
            return content;
        }

        content.put("role", "assistant".equals(m.role()) ? "model" : "user");
        ArrayNode parts = content.putArray("parts");
        if (m.text() != null && !m.text().isBlank()) {
            parts.addObject().put("text", m.text());
        }
        for (ChatTypes.ToolCall c : m.toolCalls()) {
            ObjectNode fc = parts.addObject().putObject("functionCall");
            fc.put("name", c.name());
            fc.set("args", readArgs(c.argumentsJson()));
        }
        // A turn with no parts at all is rejected; keep it well-formed.
        if (parts.isEmpty()) parts.addObject().put("text", "");
        return content;
    }

    /** Gemini wants args as an object; our neutral type carries them as raw JSON text. */
    private JsonNode readArgs(String json) {
        try {
            JsonNode n = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return n.isObject() ? n : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private ChatTypes.ChatReply parse(JsonNode root) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");

        StringBuilder text = new StringBuilder();
        List<ChatTypes.ToolCall> calls = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.hasNonNull("text")) {
                text.append(part.path("text").asText());
            }
            JsonNode fc = part.path("functionCall");
            if (!fc.isMissingNode() && fc.hasNonNull("name")) {
                String name = fc.path("name").asText();
                // Results are matched back by name when no id is supplied.
                String callId = fc.path("id").asText(name);
                calls.add(new ChatTypes.ToolCall(callId, name, fc.path("args").toString()));
            }
        }

        JsonNode usage = root.path("usageMetadata");
        long cached = usage.path("cachedContentTokenCount").asLong(0);
        long prompt = usage.path("promptTokenCount").asLong(0);
        // Thinking tokens bill as output but are reported outside candidatesTokenCount, so
        // omitting them would undercount every reasoning turn.
        long output = usage.path("candidatesTokenCount").asLong(0)
                + usage.path("thoughtsTokenCount").asLong(0);
        return new ChatTypes.ChatReply(
                text.isEmpty() ? null : text.toString(), calls,
                new ChatTypes.TokenUsage(Math.max(0, prompt - cached), output, cached, 0));
    }

    private static String brief(String body) {
        if (body == null) return "";
        String t = body.strip();
        return t.length() <= 400 ? t : t.substring(0, 400) + "…";
    }
}
