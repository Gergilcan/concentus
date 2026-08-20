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

    @org.springframework.beans.factory.annotation.Autowired
    public PricingTable(com.concentus.config.Settings settings) {
        this(settings.get("pricing.models", ""),
                settings.decimal("pricing.input-usd-per-mtok", 3.0),
                settings.decimal("pricing.output-usd-per-mtok", 15.0));
    }

    /** The table itself, for a test that is about how a model is priced. */
    public PricingTable(String configured, double fallbackInput, double fallbackOutput) {
        this.byModel = parse(configured);
        this.fallback = new Rate(fallbackInput, fallbackOutput);
    }

    /**
     * Parses {@code id:input:output} entries; a malformed entry is skipped rather than fatal.
     *
     * <p>Split from the <em>right</em>, because model ids contain colons: Ollama names a model
     * {@code qwen3:14b}, so splitting from the left gives four fields and the row is dropped —
     * the model then silently prices at the Claude fallback rate. The last two fields are the
     * rates; everything before them is the id.
     */
    private static Map<String, Rate> parse(String configured) {
        Map<String, Rate> out = new LinkedHashMap<>();
        if (configured == null || configured.isBlank()) return Map.copyOf(out);
        for (String entry : configured.split(",")) {
            String row = entry.trim();
            int lastColon = row.lastIndexOf(':');
            if (lastColon <= 0) continue;
            int prevColon = row.lastIndexOf(':', lastColon - 1);
            if (prevColon <= 0) continue;
            try {
                out.put(row.substring(0, prevColon).trim(),
                        new Rate(Double.parseDouble(row.substring(prevColon + 1, lastColon)),
                                Double.parseDouble(row.substring(lastColon + 1))));
            } catch (NumberFormatException ignored) {
                // A typo in one row shouldn't stop the app from starting or misprice everything else.
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Models that cost nothing per token because you are hosting them.
     *
     * <p>Registered at runtime rather than configured, since the set is whatever the local server
     * turns out to be serving. Without this a self-hosted run is priced at the Claude fallback and
     * reports a dollar figure for electricity you already paid for — which is worse than showing
     * nothing, because it looks authoritative.
     */
    private volatile java.util.Set<String> freeModels = java.util.Set.of();

    public void markFree(java.util.Collection<String> models) {
        this.freeModels = models == null ? java.util.Set.of() : java.util.Set.copyOf(models);
    }

    public boolean isFree(String model) {
        return model != null && freeModels.contains(model);
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

    /** A model you host yourself: no per-token bill at all. */
    public static final Rate FREE = new Rate(0, 0);

    /**
     * The configured rate for a model, or the global fallback when it isn't listed.
     *
     * <p>An explicit {@code pricing.models} entry still wins over "self-hosted, so free" — that is
     * how someone accounts for GPU time or electricity if they want to.
     *
     * <p>The fallback applies only to models that name Claude. Anything else unrecognized — a
     * LiteLLM route, an Ollama tag the catalog has not marked free yet — prices at an explicit $0
     * rather than at Claude rates: a made-up dollar figure looks authoritative and is worse than
     * an honest zero. Whoever wants those runs costed sets a {@code pricing.models} entry.
     */
    public Rate rateFor(String model) {
        if (model == null || model.isBlank()) return fallback;
        Rate exact = byModel.get(model);
        if (exact != null) return exact;
        if (isFree(model)) return FREE;
        return model.toLowerCase(java.util.Locale.ROOT).contains("claude") ? fallback : FREE;
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
