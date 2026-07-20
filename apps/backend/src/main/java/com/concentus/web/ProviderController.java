package com.concentus.web;

import com.concentus.llm.ProviderRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Which model providers are usable right now.
 *
 * <p>Exists so the designer can answer "is my key actually configured?" without starting a run
 * and reading the failure — a missing credential is otherwise invisible until launch.
 */
@RestController
@RequestMapping("/api/llm")
public class ProviderController {

    private final ProviderRegistry registry;

    public ProviderController(ProviderRegistry registry) {
        this.registry = registry;
    }

    /** Configured provider ids. Never includes credentials. */
    @GetMapping("/providers")
    public Map<String, List<String>> providers() {
        return Map.of("configured", registry.available());
    }
}
