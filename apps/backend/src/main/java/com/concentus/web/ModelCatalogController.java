package com.concentus.web;

import com.concentus.execution.ExecutionBackends;
import com.concentus.service.PricingTable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the designer needs to know about models before a run: what each one costs, and which
 * execution backends can actually run right now.
 *
 * <p>Both come from the running configuration rather than a copy in the UI — a second copy would
 * drift and end up contradicting the cost totals shown against a run.
 *
 * <p>Replaces the former {@code /api/llm/providers}, which existed to report which third-party
 * model vendors had credentials. With Claude as the only model family there are no providers to
 * enumerate: the question is which Claude credential is present, and that is what
 * {@code backends} answers.
 */
@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

    private final PricingTable pricing;
    private final ExecutionBackends backends;
    private final com.concentus.llm.LocalModelCatalog localModels;
    private final com.concentus.llm.ToolSearchIndex toolSearch;
    private final int toolSearchThreshold;

    public ModelCatalogController(PricingTable pricing, ExecutionBackends backends,
                                  com.concentus.llm.LocalModelCatalog localModels,
                                  com.concentus.llm.ToolSearchIndex toolSearch,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${local-model.tool-search-threshold:25}") int toolSearchThreshold) {
        this.pricing = pricing;
        this.backends = backends;
        this.localModels = localModels;
        this.toolSearch = toolSearch;
        this.toolSearchThreshold = toolSearchThreshold;
    }

    /** USD per million tokens for one model. */
    public record ModelRate(double input, double output) {
    }

    /** An execution backend and whether it can run right now. */
    public record BackendStatus(String id, String name, boolean available) {
    }

    /**
     * Whether MCP tool search will rank semantically, and what is missing when it will not.
     *
     * <p>Reported because its two halves live in different places — a Postgres extension and a
     * model on the inference server — and when either is absent the app silently does something
     * worse. Nobody should have to read the log to find out which.
     */
    public record ToolSearchStatus(boolean enabled, String embeddingModel, boolean modelPresent,
                                   boolean vectorReady, int threshold, String detail) {
    }

    /**
     * @param pricing     rates for models named in {@code pricing.models}
     * @param fallback    the rate applied to any model not listed there
     * @param backends    execution backends and their availability, so the designer can say when a
     *                    flow could not run rather than failing at launch
     * @param localModels model ids the self-hosted server is serving right now. Discovered rather
     *                    than configured — what you can run is whatever you have pulled — so the
     *                    picker offers exactly those and never a model that would 404 at launch
     * @param localError  why that list is empty, when it is
     */
    public record ModelCatalog(Map<String, ModelRate> pricing, ModelRate fallback,
                               List<BackendStatus> backends, List<String> localModels,
                               String localError, ToolSearchStatus toolSearch) {
    }

    @GetMapping
    public ModelCatalog catalog() {
        Map<String, ModelRate> rates = new LinkedHashMap<>();
        pricing.configuredRates().forEach((model, rate) ->
                rates.put(model, new ModelRate(rate.inputUsdPerMTok(), rate.outputUsdPerMTok())));
        PricingTable.Rate fallback = pricing.fallbackRate();

        List<BackendStatus> status = backends.all().stream()
                .map(b -> new BackendStatus(b.id(), b.displayName(), b.isAvailable()))
                .toList();

        List<String> local = List.copyOf(localModels.models());
        // Free unless someone priced them explicitly: you are paying for the GPU, not per token.
        for (String model : local) {
            rates.putIfAbsent(model, new ModelRate(0, 0));
        }

        var health = toolSearch.health(localModels.models());
        return new ModelCatalog(rates,
                new ModelRate(fallback.inputUsdPerMTok(), fallback.outputUsdPerMTok()), status,
                local, localModels.lastError(),
                new ToolSearchStatus(health.enabled(), health.embeddingModel(), health.modelPresent(),
                        health.vectorReady(), toolSearchThreshold, health.detail()));
    }
}
