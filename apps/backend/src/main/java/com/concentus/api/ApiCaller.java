package com.concentus.api;

import com.concentus.config.AgentSpec.ApiSourceSpec;
import com.concentus.support.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes one allowed OpenAPI operation with arguments the agent filled in.
 *
 * <p>Validation happens here, <b>before</b> the request leaves the machine: a missing required
 * parameter or a body on a body-less operation is a clear error back to the model — cheap to fix
 * in the next turn — rather than a provider's 4xx with the provider's phrasing. Auth comes from
 * the credential vault and is attached here; the model never sees the token and cannot be talked
 * into sending it elsewhere, because the header is not part of its input schema.
 */
@Service
public class ApiCaller {

    /** Response bodies are for reasoning, not archiving; past this they get clamped. */
    private static final int MAX_RESPONSE_CHARS = 6000;

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ApiCaller(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(boolean ok, int status, String body) {
    }

    public Result call(ApiSourceSpec spec, OpenApiCatalog.Operation op, String parsedBaseUrl,
                       JsonNode args) throws Exception {
        List<String> missing = new ArrayList<>();
        for (OpenApiCatalog.Param p : op.params()) {
            if (p.required() && (args == null || !args.hasNonNull(p.name()))) missing.add(p.name());
        }
        if (op.bodySchema() != null && (args == null || !args.hasNonNull("body"))) missing.add("body");
        if (!missing.isEmpty()) {
            return new Result(false, 0, "Missing required argument(s): " + String.join(", ", missing)
                    + ". Fill them and call again.");
        }

        String base = !spec.baseUrl.isBlank() ? spec.baseUrl : parsedBaseUrl;
        if (base == null || base.isBlank()) {
            return new Result(false, 0, "The spec declares no servers[0].url and the node sets no "
                    + "base URL. Set one on the API node.");
        }

        String path = op.path();
        StringBuilder query = new StringBuilder();
        for (OpenApiCatalog.Param p : op.params()) {
            JsonNode v = args.get(p.name());
            if (v == null || v.isNull()) continue;
            if (p.in().equals("path")) {
                // Encoded, so a value with a slash cannot rewrite the path shape the allowlist
                // approved — "1/../admin" must not become a different endpoint.
                path = path.replace("{" + p.name() + "}",
                        URLEncoder.encode(v.asText(), StandardCharsets.UTF_8));
            } else {
                query.append(query.isEmpty() ? '?' : '&')
                        .append(URLEncoder.encode(p.name(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(v.asText(), StandardCharsets.UTF_8));
            }
        }
        if (path.contains("{")) {
            return new Result(false, 0, "Unresolved path parameter in " + path
                    + " — every {placeholder} needs a value.");
        }

        String url = base.replaceAll("/+$", "") + path + query;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60));
        String method = op.method().toUpperCase(Locale.ROOT);
        JsonNode body = args == null ? null : args.get("body");
        if (body != null && !body.isNull()) {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }

        String token = spec.resolveToken();
        if (token != null && !token.isBlank()) {
            String header = spec.authHeader == null || spec.authHeader.isBlank()
                    ? "Authorization" : spec.authHeader.trim();
            String value = "Authorization".equalsIgnoreCase(header) ? "Bearer " + token : token;
            req.header(header, value);
        }

        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(res.statusCode() / 100 == 2, res.statusCode(),
                Texts.brief(res.body(), MAX_RESPONSE_CHARS));
    }
}
