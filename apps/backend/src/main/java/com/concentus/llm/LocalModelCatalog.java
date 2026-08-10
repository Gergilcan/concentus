package com.concentus.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Which models the local server is serving right now, cached briefly.
 *
 * <p>Asked rather than configured. What you can run is whatever you have pulled, and that changes
 * whenever you pull something — a hand-written list would let the designer offer a model that
 * isn't there, and the run would fail at the first call with a 404.
 *
 * <p>Cached because three things ask this on every page load and every launch: whether the backend
 * is available, whether it serves a given model, and what to put in the picker. A short TTL keeps
 * a freshly pulled model appearing within a minute without turning the designer into a load
 * generator against a machine that is also holding the weights.
 *
 * <p>A failed probe is cached too, negatively. A server that is down should not be re-dialled on
 * every request — the connection attempt is what costs, and the answer is not going to change
 * within the window.
 */
@Component
public class LocalModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(LocalModelCatalog.class);

    /** Long enough to stop hammering the server, short enough that a fresh pull shows up. */
    private static final Duration TTL = Duration.ofSeconds(30);
    /** Failures are held longer: retrying a dead server on every page load helps nobody. */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(60);

    private final LocalModelClient client;
    private final com.concentus.service.PricingTable pricing;

    private volatile Set<String> models = Set.of();
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile String lastError;

    public LocalModelCatalog(LocalModelClient client, com.concentus.service.PricingTable pricing) {
        this.client = client;
        this.pricing = pricing;
    }

    /** Model ids the server serves; empty when it is unreachable or unconfigured. */
    public Set<String> models() {
        refreshIfStale();
        return models;
    }

    /** True when a server is configured and answered with at least one model. */
    public boolean isAvailable() {
        return !models().isEmpty();
    }

    /**
     * Whether this server serves the model.
     *
     * <p>Case-insensitive, because ids differ in case between runners for the same weights —
     * Ollama's {@code qwen3:14b} against vLLM's {@code Qwen/Qwen3-14B}. Matching exactly would
     * make a correct id look unsupported.
     */
    public boolean serves(String model) {
        if (model == null || model.isBlank()) return false;
        String wanted = model.trim().toLowerCase(Locale.ROOT);
        for (String id : models()) {
            if (id.toLowerCase(Locale.ROOT).equals(wanted)) return true;
        }
        return false;
    }

    /** Why the last probe failed, for a message that says more than "unavailable". */
    public String lastError() {
        return lastError;
    }

    /** Drops the cache, so the next question re-probes. For the "check now" path in the UI. */
    public void invalidate() {
        checkedAt = Instant.EPOCH;
    }

    private void refreshIfStale() {
        if (!client.isConfigured()) {
            models = Set.of();
            lastError = "No local model server is configured (set LOCAL_MODEL_BASE_URL).";
            return;
        }
        Duration ttl = models.isEmpty() ? FAILURE_TTL : TTL;
        if (Duration.between(checkedAt, Instant.now()).compareTo(ttl) < 0) return;

        synchronized (this) {
            // Re-checked inside the lock: several threads can arrive at once on a page load, and
            // without this they would each probe the server with the same question.
            if (Duration.between(checkedAt, Instant.now()).compareTo(ttl) < 0) return;
            try {
                Set<String> found = client.listModels();
                if (found.isEmpty()) {
                    lastError = "The server at " + client.baseUrl() + " is reachable but serves no "
                            + "models. Pull one first — e.g. `ollama pull qwen3:14b`.";
                } else {
                    lastError = null;
                    if (models.isEmpty()) {
                        log.info("Local model server at {} serves {} model(s): {}",
                                client.baseUrl(), found.size(), found);
                    }
                }
                models = found;
                // A model on your own GPU has no per-token bill. Told to the pricing table here
                // rather than configured, because the set is whatever the server turns out to be
                // serving — otherwise a self-hosted run reports a dollar figure at the Claude
                // fallback rate, which is worse than showing nothing because it looks authoritative.
                pricing.markFree(found);
            } catch (RuntimeException e) {
                if (lastError == null) {
                    // Logged once per outage rather than every probe: this runs on a timer driven
                    // by page loads, and a down server would otherwise fill the log. INFO, not
                    // WARN: on a desktop the default points at Ollama's address, and most machines
                    // simply don't run one — the model picker already says so where it matters.
                    log.info("Local model server at {} is unreachable: {}",
                            client.baseUrl(), e.getMessage());
                }
                models = Set.of();
                lastError = e.getMessage();
            } finally {
                checkedAt = Instant.now();
            }
        }
    }
}
