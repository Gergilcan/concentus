package com.concentus.api;

import com.concentus.config.AgentSpec.ApiSourceSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One endpoint, given as a URL, as if a specification had described it.
 *
 * <p>The API node could only reach an API that publishes an OpenAPI document. Most of the ones
 * people actually want are a URL and a key from a settings page — a webhook to post to, an
 * internal service, the one endpoint of a large API somebody needs. The alternative was to write
 * an MCP server to wrap a single POST, which is the point at which people close the app.
 *
 * <p>Reusing {@link OpenApiCatalog.Operation} rather than adding a second call path is the whole
 * design: an endpoint typed by hand and one read from a specification become the same object, so
 * the tool schema, the argument checking, the placeholder encoding, the credential header and the
 * response handling are one implementation with one set of bugs.
 */
public final class PlainEndpoint {

    private PlainEndpoint() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Methods worth offering. Not a security boundary — the person typed the URL themselves. */
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_-]+)}");

    /** The endpoint as the rest of the API machinery expects to receive it. */
    public record Resolved(String baseUrl, OpenApiCatalog.Operation operation) {
    }

    /**
     * Reads the node's own fields into a single operation.
     *
     * @throws IllegalArgumentException with a reason meant for whoever drew the node
     */
    public static Resolved of(ApiSourceSpec spec) {
        String raw = spec.url == null ? "" : spec.url.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("The API node has no URL to call.");
        }
        // Placeholders are not legal in a URI, so they are taken out for parsing and put back
        // afterwards: {id} is exactly the part that makes the endpoint worth exposing as a tool.
        URI uri;
        try {
            uri = URI.create(raw.replaceAll("\\{[A-Za-z0-9_-]+}", "_"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a URL this node can call.");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("'" + raw + "' needs a scheme and a host, "
                    + "like https://api.example.com/things/{id}.");
        }

        String method = spec.method == null || spec.method.isBlank()
                ? "GET"
                : spec.method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("'" + method + "' is not a method this node can "
                    + "send. Use one of " + String.join(", ", METHODS) + ".");
        }

        int pathStart = raw.indexOf(uri.getHost()) + uri.getHost().length();
        // Port and path both live after the host in the original text; splitting there keeps the
        // placeholders intact instead of reading them back off the sanitised URI.
        String rest = raw.substring(pathStart);
        String baseUrl = raw.substring(0, pathStart);
        if (rest.startsWith(":")) {
            int slash = rest.indexOf('/');
            if (slash < 0) {
                baseUrl = raw;
                rest = "";
            } else {
                baseUrl = raw.substring(0, pathStart + slash);
                rest = rest.substring(slash);
            }
        }
        String path = rest.isBlank() ? "/" : rest;

        List<OpenApiCatalog.Param> params = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) {
            String name = m.group(1);
            params.add(new OpenApiCatalog.Param(name, "path", true, "string",
                    "Value for {" + name + "} in the URL."));
        }

        // A body only where one is sent: ApiCaller treats a declared body as required, so
        // declaring one on a GET would make the tool impossible to call correctly.
        JsonNode bodySchema = null;
        if (spec.sendsBody && !"GET".equals(method) && !"DELETE".equals(method)) {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.put("description", "The JSON body to send.");
            bodySchema = schema;
        }

        String description = spec.description == null || spec.description.isBlank()
                ? method + " " + path + " on " + uri.getHost()
                : spec.description.trim();
        String id = OpenApiCatalog.sanitize(method.toLowerCase(Locale.ROOT) + path
                .replace("/", "_").replace("{", "").replace("}", ""));
        return new Resolved(baseUrl,
                new OpenApiCatalog.Operation(id, method, path, description, params, bodySchema));
    }
}
