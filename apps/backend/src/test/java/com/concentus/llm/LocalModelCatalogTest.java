package com.concentus.llm;

import com.concentus.service.PricingTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which self-hosted models are available, and how often we ask.
 *
 * <p>Three things consult this on every page load, so the caching is not an optimisation — without
 * it the designer becomes a load generator against the machine that is also holding the weights.
 */
class LocalModelCatalogTest {

    private StubClient client;
    private PricingTable pricing;
    private LocalModelCatalog catalog;

    /** Answers with scripted models, counting probes, without any network. */
    private static class StubClient extends LocalModelClient {
        final AtomicInteger probes = new AtomicInteger();
        Set<String> models = Set.of("qwen3:14b");
        RuntimeException failure;
        boolean configured = true;

        StubClient() {
            super(new ObjectMapper(), "http://127.0.0.1:11434/v1", "", 60);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Set<String> listModels() {
            probes.incrementAndGet();
            if (failure != null) throw failure;
            return models;
        }
    }

    @BeforeEach
    void setUp() {
        client = new StubClient();
        pricing = new PricingTable("", 3.0, 15.0);
        catalog = new LocalModelCatalog(client, pricing);
    }

    @Test
    void anUnconfiguredServerIsNeverProbed() {
        client.configured = false;

        assertThat(catalog.models()).isEmpty();
        assertThat(catalog.isAvailable()).isFalse();
        // Not merely empty: nothing was dialled at all, so an unconfigured deployment pays no
        // connection attempts on every page load.
        assertThat(client.probes).hasValue(0);
        assertThat(catalog.lastError()).contains("LOCAL_MODEL_BASE_URL");
    }

    @Test
    void modelsAreReportedAndTheBackendIsAvailable() {
        assertThat(catalog.models()).containsExactly("qwen3:14b");
        assertThat(catalog.isAvailable()).isTrue();
        assertThat(catalog.lastError()).isNull();
    }

    @Test
    void repeatedQuestionsHitTheCacheRatherThanTheServer() {
        catalog.models();
        catalog.isAvailable();
        catalog.serves("qwen3:14b");

        assertThat(client.probes).hasValue(1);
    }

    @Test
    void invalidatingForcesTheNextQuestionToReProbe() {
        catalog.models();
        catalog.invalidate();
        catalog.models();

        assertThat(client.probes).hasValue(2);
    }

    @Test
    void matchingIsCaseInsensitive() {
        // The same weights are `qwen3:14b` on Ollama and `Qwen/Qwen3-14B` on vLLM; matching
        // exactly would make a correct id look unsupported.
        client.models = Set.of("Qwen/Qwen3-14B");
        assertThat(catalog.serves("qwen/qwen3-14b")).isTrue();
    }

    @Test
    void aModelTheServerDoesNotHaveIsNotServed() {
        assertThat(catalog.serves("claude-opus-4-8")).isFalse();
        assertThat(catalog.serves(null)).isFalse();
        assertThat(catalog.serves("  ")).isFalse();
    }

    @Test
    void anUnreachableServerReportsWhyRatherThanJustEmpty() {
        client.failure = new LlmException(LocalModelClient.ID, "Connection refused");

        assertThat(catalog.models()).isEmpty();
        assertThat(catalog.isAvailable()).isFalse();
        assertThat(catalog.lastError()).contains("Connection refused");
    }

    @Test
    void aReachableServerWithNoModelsSaysToPullOne() {
        client.models = Set.of();

        assertThat(catalog.isAvailable()).isFalse();
        assertThat(catalog.lastError()).contains("ollama pull");
    }

    @Test
    void discoveredModelsArePricedAsFree() {
        // You are paying for the GPU, not per token. Without this a self-hosted run reports a
        // dollar figure at the Claude fallback rate, which looks authoritative and is wrong.
        catalog.models();

        assertThat(pricing.isFree("qwen3:14b")).isTrue();
        assertThat(pricing.rateFor("qwen3:14b")).isEqualTo(PricingTable.FREE);
    }
}
