package com.concentus.llm;

import com.concentus.support.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs Concentus in to an MCP server that is protected by OAuth.
 *
 * <h2>Why this exists</h2>
 * The {@code claude} CLI does this dance itself and keeps the result in its own credential store,
 * which is why an OAuth-protected server works on the Claude backends and returns <b>401</b>
 * everywhere else — the token belongs to the CLI, not to this application. A self-hosted model
 * reaching the same server needs its own authorization, and that is what this obtains.
 *
 * <p>Nothing here is Holded-specific. It follows the MCP authorization spec, which is ordinary
 * OAuth 2.1 with two discovery steps in front:
 *
 * <ol>
 *   <li>the server answers an unauthenticated request with {@code WWW-Authenticate} naming a
 *       <em>protected resource metadata</em> document (RFC 9728);</li>
 *   <li>that document names the authorization server, whose own metadata gives the authorize,
 *       token and registration endpoints;</li>
 *   <li>we register as a client dynamically (RFC 7591) — there is no console to go and create an
 *       app in — and then run authorization code with PKCE.</li>
 * </ol>
 *
 * <p><b>PKCE is not optional.</b> OAuth 2.1 requires it, and without it an authorization code
 * intercepted on the redirect back is enough to mint a token.
 */
@Component
public class McpOAuth {

    private static final Logger log = LoggerFactory.getLogger(McpOAuth.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Refresh a little early, so a token cannot lapse between the check and the call. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public McpOAuth(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // The authorize endpoint answers with a redirect we must NOT follow: the whole
                // point is to hand that URL to a browser where a human can sign in.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static class OAuthNotSupported extends RuntimeException {
        public OAuthNotSupported(String message) {
            super(message);
        }
    }

    /** Where a server's authorization endpoints live, and how to register with it. */
    public record Endpoints(String authorizationUrl, String tokenUrl, String registrationUrl,
                            String resource) {
    }

    /** What a completed sign-in leaves behind. Stored encrypted, like any other credential. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {

        public boolean isFresh() {
            // No expiry stated means a token that does not self-describe as expiring; treated as
            // usable, and a 401 later is what forces a refresh.
            return accessToken != null && !accessToken.isBlank()
                    && (expiresAt == null || Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN)));
        }
    }

    /** A registered client, plus the PKCE verifier the pending authorization is bound to. */
    public record Registration(String clientId, String clientSecret) {
    }

    // ---- discovery ----

    /**
     * Finds the authorization server for an MCP endpoint.
     *
     * <p>Probes the resource itself first, because {@code WWW-Authenticate} is authoritative and
     * points at the exact metadata document; the well-known path is the fallback for servers that
     * do not send the header.
     */
    public Endpoints discover(String mcpUrl) {
        String metadataUrl = protectedResourceMetadataUrl(mcpUrl);
        JsonNode resourceMetadata = getJson(metadataUrl);
        String authServer = resourceMetadata.path("authorization_servers").path(0).asText("");
        if (authServer.isBlank()) {
            throw new OAuthNotSupported("The server at " + mcpUrl + " did not name an authorization "
                    + "server, so Concentus cannot sign in to it automatically. Use a token on the "
                    + "node instead.");
        }
        String resource = resourceMetadata.path("resource").asText(mcpUrl);

        JsonNode as = authorizationServerMetadata(authServer);
        String authorize = as.path("authorization_endpoint").asText("");
        String token = as.path("token_endpoint").asText("");
        if (authorize.isBlank() || token.isBlank()) {
            throw new OAuthNotSupported("The authorization server " + authServer
                    + " did not publish the endpoints needed to sign in.");
        }
        return new Endpoints(authorize, token,
                as.path("registration_endpoint").asText(null), resource);
    }

    private JsonNode authorizationServerMetadata(String issuer) {
        String base = issuer.replaceAll("/+$", "");
        // Both spellings are in the wild: RFC 8414 for OAuth, OpenID Connect discovery for the
        // rest. Trying one only would fail against half the servers that work fine.
        for (String path : new String[]{"/.well-known/oauth-authorization-server",
                "/.well-known/openid-configuration"}) {
            try {
                return getJson(base + path);
            } catch (RuntimeException ignored) {
                // Fall through to the next spelling.
            }
        }
        throw new OAuthNotSupported("Could not read authorization metadata from " + issuer + ".");
    }

    /** Asks the MCP endpoint itself, honouring the pointer in {@code WWW-Authenticate}. */
    private String protectedResourceMetadataUrl(String mcpUrl) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(mcpUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            String header = res.headers().firstValue("WWW-Authenticate").orElse("");
            String pointer = valueOf(header, "resource_metadata");
            if (pointer != null) return pointer;
        } catch (Exception e) {
            log.debug("Could not read WWW-Authenticate from {}: {}", mcpUrl, e.toString());
        }
        // Fallback: the well-known path on the resource's own origin.
        URI uri = URI.create(mcpUrl);
        return uri.getScheme() + "://" + uri.getAuthority() + "/.well-known/oauth-protected-resource";
    }

    /** Pulls {@code key="value"} out of a WWW-Authenticate challenge. */
    private static String valueOf(String header, String key) {
        int at = header.indexOf(key + "=\"");
        if (at < 0) return null;
        int start = at + key.length() + 2;
        int end = header.indexOf('"', start);
        return end < 0 ? null : header.substring(start, end);
    }

    // ---- registration ----

    /**
     * Registers this deployment as a client, dynamically.
     *
     * <p>Necessary because there is no developer console to go and create an app in for an
     * arbitrary MCP server, and requiring one would make "connect a server" a support ticket.
     */
    public Registration register(Endpoints endpoints, String redirectUri, String clientName) {
        if (endpoints.registrationUrl() == null || endpoints.registrationUrl().isBlank()) {
            throw new OAuthNotSupported("This server does not offer dynamic client registration, so "
                    + "an app has to be registered with it by hand and its client id configured. "
                    + "Alternatively, run the flow on the Claude backend.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.putArray("redirect_uris").add(redirectUri);
        body.put("client_name", clientName);
        body.put("token_endpoint_auth_method", "none");
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");

        JsonNode res = postJson(endpoints.registrationUrl(), body.toString(), "application/json");
        String clientId = res.path("client_id").asText("");
        if (clientId.isBlank()) {
            throw new OAuthNotSupported("The authorization server registered no client id.");
        }
        return new Registration(clientId, res.path("client_secret").asText(null));
    }

    // ---- authorization code + PKCE ----

    /** A high-entropy PKCE verifier. Kept server-side; only its hash goes in the URL. */
    public String newCodeVerifier() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String newState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The URL a person opens to approve access. */
    public String authorizationUrl(Endpoints endpoints, Registration registration,
                                   String redirectUri, String codeVerifier, String state,
                                   String scope) {
        StringBuilder url = new StringBuilder(endpoints.authorizationUrl());
        url.append(endpoints.authorizationUrl().contains("?") ? '&' : '?');
        url.append("response_type=code")
                .append("&client_id=").append(enc(registration.clientId()))
                .append("&redirect_uri=").append(enc(redirectUri))
                .append("&state=").append(enc(state))
                .append("&code_challenge=").append(enc(challengeFor(codeVerifier)))
                .append("&code_challenge_method=S256");
        if (scope != null && !scope.isBlank()) url.append("&scope=").append(enc(scope));
        // Binds the token to this specific MCP endpoint. Without it an authorization server that
        // fronts several resources may issue a token the MCP server then refuses as not its own.
        if (endpoints.resource() != null && !endpoints.resource().isBlank()) {
            url.append("&resource=").append(enc(endpoints.resource()));
        }
        return url.toString();
    }

    /** S256: the URL carries a hash, never the verifier itself. */
    static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Exchanges the code the browser came back with for tokens. */
    public Tokens exchangeCode(Endpoints endpoints, Registration registration, String code,
                               String redirectUri, String codeVerifier) {
        StringBuilder form = new StringBuilder("grant_type=authorization_code")
                .append("&code=").append(enc(code))
                .append("&redirect_uri=").append(enc(redirectUri))
                .append("&client_id=").append(enc(registration.clientId()))
                .append("&code_verifier=").append(enc(codeVerifier));
        if (registration.clientSecret() != null && !registration.clientSecret().isBlank()) {
            form.append("&client_secret=").append(enc(registration.clientSecret()));
        }
        if (endpoints.resource() != null && !endpoints.resource().isBlank()) {
            form.append("&resource=").append(enc(endpoints.resource()));
        }
        return toTokens(postJson(endpoints.tokenUrl(), form.toString(),
                "application/x-www-form-urlencoded"), null);
    }

    /**
     * Renews an access token.
     *
     * <p>The old refresh token is kept when the server does not rotate it — overwriting with null
     * would discard the only thing that can renew access, and the failure surfaces days later as
     * a mysterious 401.
     */
    public Tokens refresh(Endpoints endpoints, Registration registration, String refreshToken) {
        StringBuilder form = new StringBuilder("grant_type=refresh_token")
                .append("&refresh_token=").append(enc(refreshToken))
                .append("&client_id=").append(enc(registration.clientId()));
        if (registration.clientSecret() != null && !registration.clientSecret().isBlank()) {
            form.append("&client_secret=").append(enc(registration.clientSecret()));
        }
        if (endpoints.resource() != null && !endpoints.resource().isBlank()) {
            form.append("&resource=").append(enc(endpoints.resource()));
        }
        return toTokens(postJson(endpoints.tokenUrl(), form.toString(),
                "application/x-www-form-urlencoded"), refreshToken);
    }

    private Tokens toTokens(JsonNode body, String previousRefreshToken) {
        String access = body.path("access_token").asText("");
        if (access.isBlank()) {
            throw new OAuthNotSupported("The authorization server returned no access token.");
        }
        String refresh = body.path("refresh_token").asText(null);
        long expiresIn = body.path("expires_in").asLong(0);
        return new Tokens(access,
                refresh == null || refresh.isBlank() ? previousRefreshToken : refresh,
                expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
    }

    // ---- HTTP ----

    private JsonNode getJson(String url) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new OAuthNotSupported("GET " + url + " returned " + res.statusCode());
            }
            return mapper.readTree(res.body());
        } catch (OAuthNotSupported e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthNotSupported("Interrupted reading " + url);
        } catch (Exception e) {
            throw new OAuthNotSupported("Could not read " + url + ": " + e.getMessage());
        }
    }

    private JsonNode postJson(String url, String body, String contentType) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                // The request body carries a code or a refresh token, so only the response is
                // quoted — and that is redacted, since some servers echo the request back.
                throw new OAuthNotSupported("The authorization server returned " + res.statusCode()
                        + ": " + com.concentus.integration.Redact.secrets(Texts.brief(res.body(), 300)));
            }
            return mapper.readTree(res.body());
        } catch (OAuthNotSupported e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthNotSupported("Interrupted calling " + url);
        } catch (Exception e) {
            throw new OAuthNotSupported("Call to " + url + " failed: " + e.getMessage());
        }
    }


    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
