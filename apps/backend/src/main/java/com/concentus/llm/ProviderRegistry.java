package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a model id to the provider that serves it.
 *
 * <p>Routing is by model-id prefix rather than an explicit per-agent provider field, so a flow
 * only ever names a model — the same thing it already did for Claude. Adding a vendor is a
 * config entry, not a canvas change.
 *
 * <p>Every provider is optional: one with no credential configured simply isn't registered, and
 * naming its model produces a clear error at launch instead of a failed HTTP call mid-run.
 */
@Component
public class ProviderRegistry {

    /** Prefixes that route to each built-in provider, longest match first. */
    private static final Map<String, String> DEFAULT_PREFIXES = Map.of(
            "gpt-", "openai",
            "o1", "openai",
            "o3", "openai",
            "deepseek", "deepseek",
            "gemini-", "gemini",
            "claude-", "anthropic");

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final Map<String, String> prefixToProvider;

    public ProviderRegistry(
            ObjectMapper mapper,
            @Value("${llm.openai.api-key:}") String openaiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String openaiBaseUrl,
            @Value("${llm.deepseek.api-key:}") String deepseekKey,
            @Value("${llm.deepseek.base-url:https://api.deepseek.com/v1}") String deepseekBaseUrl,
            @Value("${llm.gemini.api-key:}") String geminiKey,
            @Value("${llm.vertex.project:}") String vertexProject,
            @Value("${llm.vertex.region:us-central1}") String vertexRegion,
            @Value("${llm.vertex.access-token:}") String vertexToken,
            @Value("${llm.openai-compatible:}") String extraCompatible,
            @Value("${llm.model-prefixes:}") String extraPrefixes) {

        register(new OpenAiCompatibleProvider("openai", openaiBaseUrl, openaiKey, mapper));
        register(new OpenAiCompatibleProvider("deepseek", deepseekBaseUrl, deepseekKey, mapper));
        register(GeminiProvider.developerApi(geminiKey, mapper));
        if (!vertexProject.isBlank()) {
            register(GeminiProvider.vertex(vertexProject, vertexRegion, () -> vertexToken, mapper));
        }
        // Anything else speaking the OpenAI shape: id|baseUrl|apiKey, comma-separated. Covers
        // Groq, Mistral, xAI, OpenRouter, Together, Ollama, vLLM without code changes.
        for (String entry : split(extraCompatible)) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 2) continue;
            register(new OpenAiCompatibleProvider(parts[0].trim(), parts[1].trim(),
                    parts.length > 2 ? parts[2].trim() : "", mapper));
        }

        Map<String, String> prefixes = new LinkedHashMap<>(DEFAULT_PREFIXES);
        for (String entry : split(extraPrefixes)) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) prefixes.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
        }
        this.prefixToProvider = Map.copyOf(prefixes);
    }

    private void register(LlmProvider p) {
        // Unconfigured providers are left out entirely so `available()` is the truth about what
        // can actually run, rather than a list that fails on first use.
        if (p.isConfigured()) providers.put(p.id(), p);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** Providers that are configured and usable right now. */
    public List<String> available() {
        return List.copyOf(providers.keySet());
    }

    /** The provider for a model id, or empty when none is configured for it. */
    public Optional<LlmProvider> forModel(String model) {
        return providerIdForModel(model).map(providers::get);
    }

    /**
     * The provider id a model routes to, whether or not it is configured. Kept separate from
     * {@link #forModel} so an unconfigured provider can be named in the error — "gemini is not
     * configured" beats "no provider for gemini-3.5-flash".
     */
    public Optional<String> providerIdForModel(String model) {
        if (model == null || model.isBlank()) return Optional.empty();
        String lower = model.toLowerCase(Locale.ROOT);
        String best = null;
        String bestPrefix = null;
        // Longest prefix wins, so a specific rule beats a general one.
        for (Map.Entry<String, String> e : prefixToProvider.entrySet()) {
            if (lower.startsWith(e.getKey()) && (bestPrefix == null || e.getKey().length() > bestPrefix.length())) {
                bestPrefix = e.getKey();
                best = e.getValue();
            }
        }
        return Optional.ofNullable(best);
    }
}
