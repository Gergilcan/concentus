package com.concentus.web;

import com.concentus.service.PluginRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lists and manages Claude Code plugins, so agents can select which ones a run loads.
 *
 * <p>Thin over {@link PluginRegistry}, which shells the {@code claude plugin} CLI — the one
 * stable interface to the plugin store. Plugins exist only on the Claude backend.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry registry;

    public PluginController(PluginRegistry registry) {
        this.registry = registry;
    }

    public record PluginsView(List<PluginRegistry.PluginInfo> plugins,
                              List<PluginRegistry.MarketplaceInfo> marketplaces) {
    }

    @GetMapping
    public PluginsView list() {
        return new PluginsView(registry.list(), registry.marketplaces());
    }

    @PostMapping("/install")
    public Map<String, String> install(@RequestBody Map<String, String> body) {
        String id = required(body, "id");
        if (!PluginRegistry.isSafeId(id)) {
            throw new IllegalArgumentException("Plugin ids look like name or name@marketplace.");
        }
        return Map.of("id", id, "status", registry.install(id));
    }

    @PostMapping("/uninstall")
    public Map<String, String> uninstall(@RequestBody Map<String, String> body) {
        String id = required(body, "id");
        if (!PluginRegistry.isSafeId(id)) {
            throw new IllegalArgumentException("Plugin ids look like name or name@marketplace.");
        }
        return Map.of("id", id, "status", registry.uninstall(id));
    }

    @PostMapping("/marketplaces")
    public Map<String, String> addMarketplace(@RequestBody Map<String, String> body) {
        String source = required(body, "source");
        if (!PluginRegistry.isSafeSource(source)) {
            throw new IllegalArgumentException("A marketplace source is a URL, path or owner/repo.");
        }
        return Map.of("source", source, "status", registry.marketplaceAdd(source));
    }

    @PostMapping("/marketplaces/remove")
    public Map<String, String> removeMarketplace(@RequestBody Map<String, String> body) {
        String name = required(body, "name");
        if (!PluginRegistry.isSafeId(name)) {
            throw new IllegalArgumentException("Invalid marketplace name.");
        }
        return Map.of("name", name, "status", registry.marketplaceRemove(name));
    }

    private static String required(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v != null) v = v.trim();
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required.");
        return v;
    }
}
