package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model-id routing and the "configured" gate.
 *
 * <p>A flow names only a model, so this mapping is what decides which vendor a block talks to. It
 * also decides what happens when a key is missing: an unconfigured provider must be absent rather
 * than present-and-broken, so the failure lands at launch with a clear message instead of as an
 * HTTP error mid-run.
 */
class ProviderRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProviderRegistry registry(String openaiKey, String deepseekKey, String geminiKey,
                                      String extraCompatible, String extraPrefixes) {
        return new ProviderRegistry(mapper,
                openaiKey, "https://api.openai.com/v1",
                deepseekKey, "https://api.deepseek.com/v1",
                geminiKey,
                "", "us-central1", "",
                extraCompatible, extraPrefixes);
    }

    @Test
    void routesEachModelFamilyToItsProvider() {
        ProviderRegistry r = registry("sk-o", "sk-d", "sk-g", "", "");

        assertThat(r.forModel("gpt-5").map(LlmProvider::id)).contains("openai");
        assertThat(r.forModel("deepseek-chat").map(LlmProvider::id)).contains("deepseek");
        assertThat(r.forModel("gemini-3.5-flash").map(LlmProvider::id)).contains("gemini");
    }

    @Test
    void anUnconfiguredProviderIsAbsentRatherThanBroken() {
        ProviderRegistry r = registry("", "sk-d", "", "", "");

        assertThat(r.available()).containsExactly("deepseek");
        assertThat(r.forModel("gpt-5")).isEmpty();
        // ...but the model still resolves to a provider NAME, so the error can say which key is
        // missing rather than just "unknown model".
        assertThat(r.providerIdForModel("gpt-5")).contains("openai");
    }

    @Test
    void claudeModelsRouteToAnthropicWhichThisBackendDoesNotServe() {
        ProviderRegistry r = registry("sk-o", "", "", "", "");

        // Claude keeps its own dedicated backends (claude CLI / Managed Agents); routing it here
        // must not silently fall through to whichever provider happens to be configured.
        assertThat(r.providerIdForModel("claude-opus-4-8")).contains("anthropic");
        assertThat(r.forModel("claude-opus-4-8")).isEmpty();
    }

    @Test
    void anUnknownModelRoutesNowhere() {
        ProviderRegistry r = registry("sk-o", "", "", "", "");

        assertThat(r.providerIdForModel("llama-3-70b")).isEmpty();
        assertThat(r.forModel("llama-3-70b")).isEmpty();
    }

    @Test
    void anyOpenAiCompatibleVendorCanBeAddedFromConfigAlone() {
        // Groq, Mistral, xAI, OpenRouter, Together, Ollama, vLLM — no code change needed.
        ProviderRegistry r = registry("", "", "",
                "groq|https://api.groq.com/openai/v1|gsk-test",
                "llama-:groq");

        assertThat(r.available()).contains("groq");
        assertThat(r.forModel("llama-3-70b").map(LlmProvider::id)).contains("groq");
    }

    @Test
    void aLocalServerRegistersWithoutAKey() {
        ProviderRegistry r = registry("", "", "",
                "ollama|http://localhost:11434/v1|",
                "qwen:ollama");

        assertThat(r.available()).contains("ollama");
        assertThat(r.forModel("qwen2.5").map(LlmProvider::id)).contains("ollama");
    }

    @Test
    void theLongestMatchingPrefixWins() {
        // A specific rule must beat a general one, or "deepseek-reasoner" could land on a
        // catch-all "d" style prefix.
        ProviderRegistry r = registry("", "sk-d", "",
                "special|http://localhost:9/v1|",
                "deepseek-reasoner:special");

        assertThat(r.forModel("deepseek-reasoner").map(LlmProvider::id)).contains("special");
        assertThat(r.forModel("deepseek-chat").map(LlmProvider::id)).contains("deepseek");
    }
}
