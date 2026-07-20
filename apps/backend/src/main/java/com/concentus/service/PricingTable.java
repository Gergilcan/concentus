package com.concentus.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts token usage into a USD estimate.
 *
 * <p>Rates are per model, because a flow routinely mixes them — a coordinator on Opus delegating
 * to sub-agents on Sonnet. A single flat rate misprices whichever half doesn't match it.
 *
 * <p>Cached prompt tokens are weighted rather than counted as ordinary input: a cache read bills
 * at roughly 0.1x and a cache write at 1.25x. This matters more than the model rate — a resumed
 * session re-reads its whole history from cache each turn, so cache reads dominate the raw counts
 * while contributing a tenth as much cost.
 *
 * <p>On a Claude subscription there is no per-token bill at all; the figure is an
 * equivalent-usage estimate, useful for comparing runs rather than for accounting.
 */
@Component
public class PricingTable {

    /** Cache reads bill at ~0.1x the model's input rate. */
    private static final double CACHE_READ_MULTIPLIER = 0.1;
    /** Cache writes bill at ~1.25x (the 5-minute TTL premium). */
    private static final double CACHE_WRITE_MULTIPLIER = 1.25;

    public record Rate(double inputUsdPerMTok, double outputUsdPerMTok) {
    }

    private final Map<String, Rate> byModel;
    private final Rate fallback;

    public PricingTable(@Value("${pricing.models:}") String configured,
                        @Value("${pricing.input-usd-per-mtok:3.0}") double fallbackInput,
                        @Value("${pricing.output-usd-per-mtok:15.0}") double fallbackOutput) {
        this.byModel = parse(configured);
        this.fallback = new Rate(fallbackInput, fallbackOutput);
    }

    /** Parses {@code id:input:output} entries; a malformed entry is skipped rather than fatal. */
    private static Map<String, Rate> parse(String configured) {
        Map<String, Rate> out = new LinkedHashMap<>();
        if (configured == null || configured.isBlank()) return Map.copyOf(out);
        for (String entry : configured.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 3) continue;
            try {
                out.put(parts[0].trim(), new Rate(Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            } catch (NumberFormatException ignored) {
                // A typo in one row shouldn't stop the app from starting or price everything else.
            }
        }
        return Map.copyOf(out);
    }

    /**
     * The configured per-model rates, so the designer can show the same numbers the cost estimate
     * uses. A separate copy in the UI would drift from this and quietly contradict the totals.
     */
    public Map<String, Rate> configuredRates() {
        return byModel;
    }

    /** The rate applied to any model not listed. */
    public Rate fallbackRate() {
        return fallback;
    }

    /** The configured rate for a model, or the global fallback when it isn't listed. */
    public Rate rateFor(String model) {
        if (model == null || model.isBlank()) return fallback;
        Rate exact = byModel.get(model);
        return exact != null ? exact : fallback;
    }

    /** USD estimate for one model's usage, with cached tokens weighted. */
    public double costUsd(String model, long inputTokens, long cacheReadTokens,
                          long cacheWriteTokens, long outputTokens) {
        Rate rate = rateFor(model);
        double billableInput = inputTokens
                + (cacheReadTokens * CACHE_READ_MULTIPLIER)
                + (cacheWriteTokens * CACHE_WRITE_MULTIPLIER);
        double usd = (billableInput / 1_000_000d) * rate.inputUsdPerMTok()
                + (outputTokens / 1_000_000d) * rate.outputUsdPerMTok();
        return round(usd);
    }

    /** Four decimal places — enough to keep sub-cent runs from collapsing to zero. */
    public static double round(double usd) {
        return Math.round(usd * 10_000d) / 10_000d;
    }
}
