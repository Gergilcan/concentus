package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A model server you host yourself, spoken to over OpenAI's {@code /chat/completions} shape.
 *
 * <p>One implementation covers every runner worth using: Ollama, llama.cpp's {@code llama-server},
 * vLLM, LM Studio and TGI all expose this format. They differ in base URL and in whether they want
 * a key, which is configuration rather than code.
 *
 * <p><b>Not a general provider abstraction.</b> An earlier version of this file fanned out to
 * OpenAI, Gemini, DeepSeek and Azure; that was removed deliberately. This exists for a model on
 * your own hardware, where there is no vendor, no per-token bill and nothing leaving the machine —
 * a different proposition from "one more hosted API".
 *
 * <h2>Timeouts</h2>
 * Generous, and deliberately so. A 14B model on one consumer GPU produces tens of tokens a second,
 * and the first call after a cold start also pays for loading the weights into VRAM — tens of
 * seconds before the first byte. A timeout tuned for a hosted API turns a working setup into
 * intermittent failures that read as the model being broken.
 */
@Component
public class LocalModelClient {

    /** Stable id for errors and for {@code AgentRun.backend}. */
    public static final String ID = "local-model";

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration requestTimeout;

    public LocalModelClient(ObjectMapper mapper,
                            @Value("${local-model.base-url:}") String baseUrl,
                            @Value("${local-model.api-key:}") String apiKey,
                            @Value("${local-model.request-timeout-seconds:900}") int timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Whether a server address is configured at all. Says nothing about it being reachable. */
    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    /**
     * Model ids the server currently serves.
     *
     * <p>Asked rather than configured, because the answer is a fact about the machine: what you
     * pulled is what you can run. A hand-maintained list goes stale the first time you pull a
     * model, and lets the designer offer one that isn't there.
     *
     * @throws LlmException if the server cannot be reached
     */
    public Set<String> listModels() {
        JsonNode body = get("/models");
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode m : body.path("data")) {
            String id = m.path("id").asText("");
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    /**
     * Embeds a batch of texts.
     *
     * <p>The same server, the same OpenAI-shaped surface — Ollama, vLLM and LM Studio all expose
     * {@code /embeddings} alongside {@code /chat/completions}. Batched in one request because the
     * per-call overhead dominates for short strings, and a tool index is hundreds of them.
     *
     * @param model an embedding model, not a chat one. Asking a chat model to embed returns a
     *              404 or nonsense, depending on the runner.
     * @return one vector per input, in order
     */
    public List<float[]> embed(String model, List<String> texts) {
        if (texts.isEmpty()) return List.of();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                throw new LlmException(ID, "The server has no embedding model '" + model
                        + "'. Pull one — `ollama pull " + model + "` — or set "
                        + "LOCAL_MODEL_EMBEDDING_MODEL to one it has.");
            }
            if (res.statusCode() / 100 != 2) {
                throw new LlmException(ID, explain(res.statusCode(), res.body(), model));
            }
            JsonNode data = mapper.readTree(res.body()).path("data");
            List<float[]> out = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode vector = item.path("embedding");
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) values[i] = (float) vector.get(i).asDouble();
                out.add(values);
            }
            if (out.size() != texts.size()) {
                // Silently short would misalign every vector with its tool, which produces a
                // search that returns confident nonsense rather than an error.
                throw new LlmException(ID, "Asked for " + texts.size() + " embeddings and got "
                        + out.size() + ".");
            }
            return out;
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(ID, "Interrupted while embedding");
        } catch (Exception e) {
            throw new LlmException(ID, "Embedding call failed: " + e.getMessage());
        }
    }

    public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
        ObjectNode body = buildBody(request);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new LlmException(ID, explain(res.statusCode(), res.body(), request.model()));
            }
            return parse(mapper.readTree(res.body()), request.tools());
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(ID, "Interrupted calling the local model server");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException(ID, "The local model server did not answer within "
                    + requestTimeout.toSeconds() + "s. A cold start loads the weights into VRAM and "
                    + "is slow; if it persists, the model may not fit the GPU and is spilling into "
                    + "system RAM.");
        } catch (java.io.IOException e) {
            throw new LlmException(ID, "Could not reach the local model server at " + baseUrl
                    + " — " + e.getMessage() + ". Is it running and reachable from here? Inside a "
                    + "container `localhost` is the container itself; use host.docker.internal.");
        } catch (Exception e) {
            throw new LlmException(ID, "Call to the local model server failed: " + e.getMessage(), e);
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

    private ChatTypes.ChatReply parse(JsonNode root, List<ChatTypes.ToolSpec> offered) {
        JsonNode message = root.path("choices").path(0).path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;

        List<ChatTypes.ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode c : message.path("tool_calls")) {
            // Local runners sometimes omit the call id. It is what pairs a result back to its
            // request, so an empty one makes the next turn unmatchable and the model sees its own
            // tool answer vanish. A stable index-based id costs nothing and is invisible when the
            // server does send one.
            String id = c.path("id").asText("");
            if (id.isBlank()) id = "call_" + index;
            calls.add(new ChatTypes.ToolCall(
                    id,
                    c.path("function").path("name").asText(""),
                    argumentsOf(c.path("function").path("arguments"))));
            index++;
        }

        // Smaller models routinely put the tool call in the message text instead of in the
        // tool_calls field. Left alone it reaches the user as a wall of JSON and the tool never
        // runs — the model looks like it is refusing to work when in fact it tried.
        if (calls.isEmpty() && text != null) {
            Leaked leaked = recoverLeakedCall(text, offered);
            if (leaked != null) {
                if (leaked.call() != null) calls.add(leaked.call());
                text = leaked.text();
            }
        }

        JsonNode usage = root.path("usage");
        // No cache tiers on a server you host: the prompt is re-processed each turn, or served
        // from the runner's own KV cache, which has no billing meaning. Reported as plain input.
        return new ChatTypes.ChatReply(text, calls, new ChatTypes.TokenUsage(
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0), 0, 0));
    }

    /** Either a recovered tool call, or plain text rescued from a tool-call-shaped wrapper. */
    record Leaked(ChatTypes.ToolCall call, String text) {
    }

    /**
     * Rescues a tool call the model wrote into its message instead of into {@code tool_calls}.
     *
     * <p>Two shapes, both seen from Qwen through Ollama:
     *
     * <ul>
     *   <li><b>A real tool, in the wrong field.</b> {@code {"name":"list_contacts","arguments":{…}}}
     *       — promoted to an actual call, so the turn proceeds as the model intended.</li>
     *   <li><b>An invented wrapper.</b> {@code {"name":"respond","arguments":{"message":"…"}}} —
     *       there is no {@code respond} tool; the model decided its final answer also had to be a
     *       tool call. Unwrapped to the message, because the answer is right there and showing raw
     *       JSON to a person instead is the worst of both outcomes.</li>
     * </ul>
     *
     * <p>Only consulted when {@code tool_calls} came back empty, and only when the whole message is
     * the JSON — prose that merely quotes a tool call is left exactly as written.
     */
    static Leaked recoverLeakedCall(String text, List<ChatTypes.ToolSpec> offered) {
        String body = text.strip();
        // Some templates emit the call wrapped in these tags rather than as bare JSON.
        if (body.startsWith("<tool_call>")) {
            int end = body.indexOf("</tool_call>");
            body = end > 0 ? body.substring("<tool_call>".length(), end).strip()
                    : body.substring("<tool_call>".length()).strip();
        }
        if (!body.startsWith("{") || !body.endsWith("}")) return null;

        JsonNode node;
        try {
            node = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            return null;
        }
        String name = node.path("name").asText("");
        if (name.isBlank() || !node.has("arguments")) return null;

        boolean known = offered != null && offered.stream()
                .anyMatch(t -> t.name().equals(name));
        if (known) {
            return new Leaked(new ChatTypes.ToolCall("call_recovered", name,
                    argumentsOf(node.path("arguments"))), null);
        }

        // Unknown tool: the model invented a wrapper for its own prose. Take the longest string in
        // the arguments — templates call the field message, response, text or content, and guessing
        // at the name would just add another thing to get wrong.
        String best = null;
        for (JsonNode value : node.path("arguments")) {
            if (value.isTextual() && (best == null || value.asText().length() > best.length())) {
                best = value.asText();
            }
        }
        return best == null ? null : new Leaked(null, best);
    }

    /**
     * Tool arguments as a JSON string.
     *
     * <p>The wire format says this is a string <em>containing</em> JSON, but local runners
     * routinely send a JSON object instead. {@code asText()} on an object returns an empty string,
     * which reaches the tool as "no arguments" — a silent failure where the model appears to have
     * called the tool with nothing.
     */
    private static String argumentsOf(JsonNode arguments) {
        if (arguments.isMissingNode() || arguments.isNull()) return "{}";
        return arguments.isTextual() ? arguments.asText("{}") : arguments.toString();
    }

    /** Turns the server's rejection into something that names the likely cause. */
    private String explain(int status, String body, String model) {
        String brief = brief(body);
        if (status == 404) {
            return "The local model server has no model '" + model + "' (404). Pull it first — e.g. "
                    + "`ollama pull " + model + "` — and check the id matches exactly, tag included.";
        }
        if (status == 400 && brief.toLowerCase().contains("tool")) {
            return "The server rejected the tool definitions (400): " + brief + ". Not every model "
                    + "supports tool calling, and the runner has to expose it — a flow needs both.";
        }
        if (status == 401 || status == 403) {
            return "The local model server refused the credential (" + status + "). Set or clear "
                    + "LOCAL_MODEL_API_KEY to match how the server is configured.";
        }
        return "The local model server returned " + status + ": " + brief;
    }

    private JsonNode get(String path) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    // Short: this answers from memory, and it gates "is this backend available",
                    // which must not hang a page load.
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new LlmException(ID, "GET " + path + " returned " + res.statusCode());
            }
            return mapper.readTree(res.body());
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(ID, "Interrupted contacting the local model server");
        } catch (Exception e) {
            throw new LlmException(ID, "Could not reach " + baseUrl + path + ": " + e.getMessage());
        }
    }

    /** Error bodies can be enormous; keep the console line readable. */
    private static String brief(String body) {
        if (body == null) return "";
        String t = body.strip();
        return t.length() <= 400 ? t : t.substring(0, 400) + "…";
    }
}
