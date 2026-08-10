package com.concentus.web;

import com.concentus.api.OpenApiCatalog;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Designer support for API nodes: parse a spec and show what could be allowed. */
@RestController
@RequestMapping("/api/api-nodes")
public class ApiNodeController {

    private final OpenApiCatalog catalog;

    public ApiNodeController(OpenApiCatalog catalog) {
        this.catalog = catalog;
    }

    public record PreviewRequest(String specUrl, String specText) {
    }

    public record OperationView(String key, String method, String path, String description,
                                int paramCount, boolean hasBody, boolean write) {
    }

    /**
     * Fetches and parses the spec, returning every operation with its shape — the inspector
     * renders this as the allowlist. `write` is what lets the UI offer "select all reads" while
     * making anything that changes state an individual, deliberate tick.
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest body) {
        OpenApiCatalog.Parsed parsed = catalog.load(body.specUrl(), body.specText());
        List<OperationView> ops = parsed.operations().stream()
                .map(op -> new OperationView(op.key(), op.method().toUpperCase(), op.path(),
                        op.description(), op.params().size(), op.bodySchema() != null,
                        !op.method().equals("get") && !op.method().equals("head")))
                .toList();
        return Map.of("baseUrl", parsed.baseUrl(), "operations", ops);
    }
}
