package com.concentus.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pricing where the model ids are not Anthropic's.
 *
 * <p>Both cases here were live defects: an Ollama id contains a colon, which the rate parser used
 * as its field separator, and a self-hosted model with no configured rate fell through to the
 * Claude fallback — reporting a dollar figure for electricity already paid for.
 */
class PricingTableLocalModelTest {

    private static PricingTable table(String configured) {
        return new PricingTable(configured, 3.0, 15.0);
    }

    @Test
    void aModelIdContainingAColonIsParsedRatherThanDropped() {
        // Ollama names models `name:tag`. Splitting from the left gives four fields, the row is
        // skipped, and the model then silently prices at the Claude fallback.
        PricingTable pricing = table("qwen3:14b:0.2:0.6");

        PricingTable.Rate rate = pricing.rateFor("qwen3:14b");

        assertThat(rate.inputUsdPerMTok()).isEqualTo(0.2);
        assertThat(rate.outputUsdPerMTok()).isEqualTo(0.6);
    }

    @Test
    void ordinaryClaudeRowsStillParse() {
        PricingTable pricing = table("claude-opus-4-8:15:75,claude-sonnet-5:3:15");

        assertThat(pricing.rateFor("claude-opus-4-8").inputUsdPerMTok()).isEqualTo(15);
        assertThat(pricing.rateFor("claude-sonnet-5").outputUsdPerMTok()).isEqualTo(15);
    }

    @Test
    void aRowWithTooFewFieldsIsSkippedWithoutTakingTheRestWithIt() {
        PricingTable pricing = table("broken,claude-sonnet-5:3:15");

        assertThat(pricing.rateFor("claude-sonnet-5").inputUsdPerMTok()).isEqualTo(3);
    }

    @Test
    void aSelfHostedModelCostsNothing() {
        PricingTable pricing = table("");
        pricing.markFree(List.of("qwen3:14b"));

        assertThat(pricing.rateFor("qwen3:14b")).isEqualTo(PricingTable.FREE);
        assertThat(pricing.costUsd("qwen3:14b", 1_000_000, 0, 0, 1_000_000)).isZero();
    }

    @Test
    void anExplicitRateBeatsSelfHosted() {
        // How someone accounts for GPU time or electricity if they want to.
        PricingTable pricing = table("qwen3:14b:0.1:0.1");
        pricing.markFree(List.of("qwen3:14b"));

        assertThat(pricing.rateFor("qwen3:14b").inputUsdPerMTok()).isEqualTo(0.1);
    }

    @Test
    void aModelThatIsNeitherListedNorSelfHostedStillUsesTheFallback() {
        PricingTable pricing = table("");
        pricing.markFree(List.of("qwen3:14b"));

        assertThat(pricing.rateFor("some-new-claude").inputUsdPerMTok()).isEqualTo(3.0);
    }

    @Test
    void anUnknownNonClaudeModelPricesAtAnExplicitZero() {
        // A LiteLLM route or an Ollama tag the catalog has not seen: pricing it at Claude rates
        // reports an authoritative-looking dollar figure for a bill that does not exist.
        PricingTable pricing = table("");

        assertThat(pricing.rateFor("qwen3:32b")).isEqualTo(PricingTable.FREE);
        assertThat(pricing.costUsd("mistral-large", 1_000_000, 0, 0, 1_000_000)).isZero();
    }

    @Test
    void anExplicitRateStillCostsAnUnknownNonClaudeModel() {
        PricingTable pricing = table("mistral-large:2:6");

        assertThat(pricing.rateFor("mistral-large").inputUsdPerMTok()).isEqualTo(2);
    }

    @Test
    void theFreeSetIsReplacedRatherThanAccumulated() {
        // It mirrors what the server currently serves; a model removed there must stop being free,
        // or an unrelated model that later takes its name would price at zero.
        PricingTable pricing = table("");
        pricing.markFree(List.of("qwen3:14b"));
        pricing.markFree(List.of("llama3:8b"));

        assertThat(pricing.isFree("qwen3:14b")).isFalse();
        assertThat(pricing.isFree("llama3:8b")).isTrue();
    }
}
