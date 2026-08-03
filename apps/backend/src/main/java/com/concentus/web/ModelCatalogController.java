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

    public ModelCatalogController(PricingTable pricing, ExecutionBackends backends) {
        this.pricing = pricing;
        this.backends = backends;
    }

    /** USD per million tokens for one model. */
    public record ModelRate(double input, double output) {
    }

    /** An execution backend and whether it can run right now. */
    public record BackendStatus(String id, String name, boolean available) {
    }

    /**
     * @param pricing  rates for models named in {@code pricing.models}
     * @param fallback the rate applied to any model not listed there
     * @param backends execution backends and their availability, so the designer can say when a
     *                 flow could not run rather than failing at launch
     */
    public record ModelCatalog(Map<String, ModelRate> pricing, ModelRate fallback,
                               List<BackendStatus> backends) {
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

        return new ModelCatalog(rates,
                new ModelRate(fallback.inputUsdPerMTok(), fallback.outputUsdPerMTok()), status);
    }
}
