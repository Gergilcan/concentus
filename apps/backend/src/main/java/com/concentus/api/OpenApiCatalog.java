package com.concentus.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses an OpenAPI 3.x document into the operations an agent may call.
 *
 * <p>A deliberate hand-rolled subset rather than swagger-parser: that library drags ~10 MB of
 * dependencies into an installer this project just fought to shrink, and the subset an agent needs
 * — paths, methods, parameters, JSON request bodies, single-file {@code $ref}s — is small and
 * stable. What is not supported fails with a named reason, not a guess: Swagger 2.0 documents are
 * refused outright, and external {@code $ref}s resolve to an empty schema with the ref preserved
 * in description.
 */
@Service
public class OpenApiCatalog {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Parsed specs by document hash — a spec is fetched and parsed once per content, not per run. */
    private final Map<String, List<Operation>> cache = new ConcurrentHashMap<>();

    private static final Set<String> METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head");

    public OpenApiCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * One callable operation, in the shape MCP wants: a name, a description, and a JSON Schema of
     * the inputs the model must fill.
     *
     * <p>Path, query and body parameters are flattened into one input schema; {@link #call} splits
     * them back out. Flat is what models fill reliably — nested "put this one in the query string"
     * envelopes are exactly the kind of structure they get wrong.
     */
    public record Operation(String id, String method, String path, String description,
                            List<Param> params, JsonNode bodySchema) {

        public String key() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }
    }

    public record Param(String name, String in, boolean required, String type, String description) {
    }

    /** Fetches (or accepts inline) and parses a spec. Throws IllegalArgumentException with a reason. */
    public Parsed load(String specUrl, String specInline) {
        String document = specInline != null && !specInline.isBlank()
                ? specInline
                : fetch(specUrl);
        String hash = Integer.toHexString(document.hashCode());
        List<Operation> ops = cache.computeIfAbsent(hash, h -> parse(document));
        return new Parsed(baseUrlOf(document), ops);
    }

    public record Parsed(String baseUrl, List<Operation> operations) {
    }

    private String fetch(String specUrl) {
        if (specUrl == null || specUrl.isBlank()) {
            throw new IllegalArgumentException("The API node has no spec URL and no pasted spec.");
        }
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(specUrl.trim()))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalArgumentException(
                        "The spec URL answered HTTP " + res.statusCode() + ".");
            }
            return res.body();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Interrupted while fetching the spec.");
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not fetch the spec: " + e.getMessage());
        }
    }

    private String baseUrlOf(String document) {
        try {
            JsonNode root = readTree(document);
            JsonNode servers = root.path("servers");
            if (servers.isArray() && !servers.isEmpty()) {
                return servers.get(0).path("url").asText("");
            }
        } catch (RuntimeException ignored) {
            // parse() will report the real problem
        }
        return "";
    }

    private List<Operation> parse(String document) {
        JsonNode root = readTree(document);
        if (root.hasNonNull("swagger")) {
            throw new IllegalArgumentException(
                    "This is a Swagger 2.0 document. Only OpenAPI 3.x is supported — most providers "
                            + "publish a 3.x version of the same spec.");
        }
        if (!root.path("openapi").asText("").startsWith("3")) {
            throw new IllegalArgumentException("Not an OpenAPI 3.x document (no `openapi: 3.x` field).");
        }

        List<Operation> out = new ArrayList<>();
        JsonNode paths = root.path("paths");
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            // Parameters declared at path level apply to every method under it.
            List<Param> shared = params(pathItem.path("parameters"), root);
            pathItem.fields().forEachRemaining(methodEntry -> {
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) return;
                JsonNode op = methodEntry.getValue();
                List<Param> params = new ArrayList<>(shared);
                params.addAll(params(op.path("parameters"), root));
                String id = op.path("operationId").asText("");
                if (id.isBlank()) id = toolNameFor(method, path);
                String description = op.path("summary").asText(op.path("description").asText(""));
                JsonNode bodySchema = deref(
                        op.path("requestBody").path("content").path("application/json").path("schema"),
                        root, new HashSet<>());
                out.add(new Operation(sanitize(id), method, path, description, params,
                        bodySchema.isMissingNode() ? null : bodySchema));
            });
        });
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The spec parsed but declares no operations under `paths`.");
        }
        return out;
    }

    private JsonNode readTree(String document) {
        try {
            String trimmed = document.stripLeading();
            if (trimmed.startsWith("{")) return mapper.readTree(document);
            // YAML specs are the norm in the wild; SnakeYAML is already on the classpath via Spring.
            Object yaml = new org.yaml.snakeyaml.Yaml(
                    new org.yaml.snakeyaml.constructor.SafeConstructor(
                            new org.yaml.snakeyaml.LoaderOptions())).load(document);
            return mapper.valueToTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("The spec is neither valid JSON nor valid YAML: "
                    + com.concentus.support.Texts.brief(e.getMessage(), 200));
        }
    }

    private List<Param> params(JsonNode array, JsonNode root) {
        List<Param> out = new ArrayList<>();
        if (!array.isArray()) return out;
        for (JsonNode raw : array) {
            JsonNode p = deref(raw, root, new HashSet<>());
            String in = p.path("in").asText("query");
            // Header/cookie parameters are the caller's business (auth lives on the node), and
            // exposing them would invite the model to try to set Authorization itself.
            if (!in.equals("query") && !in.equals("path")) continue;
            out.add(new Param(
                    p.path("name").asText(""),
                    in,
                    p.path("required").asBoolean(in.equals("path")),
                    deref(p.path("schema"), root, new HashSet<>()).path("type").asText("string"),
                    p.path("description").asText("")));
        }
        return out;
    }

    /** Resolves local {@code #/components/...} refs; cycle-guarded; foreign refs come back empty. */
    private JsonNode deref(JsonNode node, JsonNode root, Set<String> seen) {
        if (node == null || node.isMissingNode()) return mapper.missingNode();
        String ref = node.path("$ref").asText("");
        if (ref.isBlank()) return node;
        if (!ref.startsWith("#/") || !seen.add(ref)) return mapper.createObjectNode();
        JsonNode target = root;
        for (String part : ref.substring(2).split("/")) {
            target = target.path(part.replace("~1", "/").replace("~0", "~"));
        }
        return deref(target, root, seen);
    }

    /** MCP tool names must be [a-zA-Z0-9_-]; operationIds in the wild are not. */
    public static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    static String toolNameFor(String method, String path) {
        return sanitize(method + path.replace("/", "_").replace("{", "").replace("}", ""));
    }

    /** The MCP inputSchema for an operation: flattened path+query params plus the JSON body. */
    public ObjectNode inputSchema(Operation op) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");
        for (Param p : op.params()) {
            ObjectNode prop = props.putObject(p.name());
            prop.put("type", jsonType(p.type()));
            if (!p.description().isBlank()) prop.put("description", p.description());
            if (p.required()) required.add(p.name());
        }
        if (op.bodySchema() != null && op.bodySchema().isObject()) {
            ObjectNode body = props.putObject("body");
            body.setAll((ObjectNode) op.bodySchema().deepCopy());
            body.put("description", "JSON request body");
            required.add("body");
        }
        if (required.isEmpty()) schema.remove("required");
        return schema;
    }

    private static String jsonType(String openapiType) {
        return switch (openapiType) {
            case "integer", "number", "boolean", "array", "object" -> openapiType;
            default -> "string";
        };
    }
}
