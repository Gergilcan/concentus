package com.concentus.web;

import com.concentus.llm.ProviderRegistry;
import com.concentus.service.PricingTable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the designer needs to know about models before a run: which providers are usable, and what
 * each model costs.
 *
 * <p>Both come from the running configuration rather than a copy in the UI — a second copy would
 * drift and end up contradicting the cost totals shown against a run.
 */
@RestController
@RequestMapping("/api/llm")
public class ProviderController {

    private final ProviderRegistry registry;
    private final PricingTable pricing;

    public ProviderController(ProviderRegistry registry, PricingTable pricing) {
        this.registry = registry;
        this.pricing = pricing;
    }

    /** USD per million tokens for one model. */
    public record ModelRate(double input, double output) {
    }

    /**
     * Configured providers plus the per-model rates. Never includes credentials.
     *
     * @param pricing  rates for models named in {@code pricing.models}
     * @param fallback the rate applied to any model not listed there
     */
    public record ProvidersResponse(List<String> configured,
                                    Map<String, ModelRate> pricing,
                                    ModelRate fallback) {
    }

    @GetMapping("/providers")
    public ProvidersResponse providers() {
        Map<String, ModelRate> rates = new LinkedHashMap<>();
        pricing.configuredRates().forEach((model, rate) ->
                rates.put(model, new ModelRate(rate.inputUsdPerMTok(), rate.outputUsdPerMTok())));
        PricingTable.Rate fb = pricing.fallbackRate();
        return new ProvidersResponse(registry.available(), rates,
                new ModelRate(fb.inputUsdPerMTok(), fb.outputUsdPerMTok()));
    }
}
