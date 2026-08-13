package com.concentus.llm;

import com.concentus.secrets.CredentialStore;
import com.concentus.support.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Holds an MCP server's OAuth authorization, and hands out a usable access token.
 *
 * <p>Everything for one server — the dynamic client registration and the tokens — is kept as a
 * single encrypted credential, because they are useless apart: a refresh token can only be
 * redeemed by the client id it was issued to. Storing them as one row also means one thing to
 * delete when someone disconnects a server.
 *
 * <p>Keyed by the MCP <em>URL</em>, not the node's display name. Two flows naming the same server
 * differently should share one authorization; renaming a node should not silently require a fresh
 * sign-in.
 */
@Component
public class McpOAuthStore {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthStore.class);
    /** Prefix for the credential label, so these are recognisable in the credentials list. */
    static final String LABEL_PREFIX = "mcp-oauth:";

    private final CredentialStore credentials;
    private final McpOAuth oauth;
    private final ObjectMapper mapper;
    /** Access tokens in memory, so a run does not decrypt and refresh on every tool call. */
    private final Map<String, McpOAuth.Tokens> cache = new ConcurrentHashMap<>();

    public McpOAuthStore(CredentialStore credentials, McpOAuth oauth, ObjectMapper mapper) {
        this.credentials = credentials;
        this.oauth = oauth;
        this.mapper = mapper;
    }

    /** Everything needed to use, and to renew, one server's authorization. */
    public record Session(McpOAuth.Endpoints endpoints, McpOAuth.Registration registration,
                          McpOAuth.Tokens tokens) {
    }

    /**
     * The key a grant is filed under.
     *
     * <p>One place, used by save, load, the cache and forget alike. Normalising in some of them
     * and not others is how a grant gets stored under one spelling and looked up under another —
     * which shows as "connected" in the UI and 401 in every run, with nothing to connect the two.
     */
    static String normalize(String mcpUrl) {
        return Texts.trimTrailingSlashes(mcpUrl);
    }

    static String labelFor(String mcpUrl) {
        return LABEL_PREFIX + normalize(mcpUrl);
    }

    /**
     * The credentials filed under one grant's label. A stream rather than a single result because
     * the callers differ on purpose: save and load take the first match, while forget must delete
     * every one of them.
     */
    private Stream<CredentialStore.Credential> filedUnder(String organizationId, String label) {
        return credentials.list(organizationId).stream()
                .filter(c -> label.equalsIgnoreCase(c.label()));
    }

    public void save(String organizationId, String mcpUrl, Session session) {
        ObjectNode node = mapper.createObjectNode();
        node.put("authorizationUrl", session.endpoints().authorizationUrl());
        node.put("tokenUrl", session.endpoints().tokenUrl());
        node.put("registrationUrl", session.endpoints().registrationUrl());
        node.put("resource", session.endpoints().resource());
        node.put("clientId", session.registration().clientId());
        node.put("clientSecret", session.registration().clientSecret());
        node.put("accessToken", session.tokens().accessToken());
        node.put("refreshToken", session.tokens().refreshToken());
        node.put("expiresAt", session.tokens().expiresAt() == null
                ? null : session.tokens().expiresAt().toString());

        String label = labelFor(mcpUrl);
        String json = node.toString();
        // Replace rather than accumulate: re-authorizing a server must not leave the previous
        // grant behind, both because it is dead weight and because the older one may still work.
        filedUnder(organizationId, label)
                .findFirst()
                .ifPresentOrElse(
                        existing -> credentials.updateSecret(organizationId, existing.id(), json),
                        () -> credentials.create(organizationId, label,
                                CredentialStore.Kind.API_TOKEN, json));
        cache.put(normalize(mcpUrl), session.tokens());
    }

    public Optional<Session> load(String organizationId, String mcpUrl) {
        String label = labelFor(mcpUrl);
        return filedUnder(organizationId, label)
                .findFirst()
                .flatMap(c -> credentials.reveal(organizationId, c.id()))
                .flatMap(this::parse);
    }

    private Optional<Session> parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            String expiresAt = n.path("expiresAt").asText(null);
            return Optional.of(new Session(
                    new McpOAuth.Endpoints(
                            n.path("authorizationUrl").asText(null),
                            n.path("tokenUrl").asText(null),
                            n.path("registrationUrl").asText(null),
                            n.path("resource").asText(null)),
                    new McpOAuth.Registration(
                            n.path("clientId").asText(null),
                            n.path("clientSecret").asText(null)),
                    new McpOAuth.Tokens(
                            n.path("accessToken").asText(null),
                            n.path("refreshToken").asText(null),
                            expiresAt == null || expiresAt.isBlank() ? null : Instant.parse(expiresAt))));
        } catch (Exception e) {
            log.warn("Stored MCP authorization could not be read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A usable access token for this server, refreshing it if it has aged out.
     *
     * @return empty when no authorization is stored, so the caller reports "sign in" rather than
     *         retrying an anonymous request that will 401 again
     */
    public Optional<String> accessToken(String organizationId, String mcpUrl) {
        McpOAuth.Tokens cached = cache.get(normalize(mcpUrl));
        if (cached != null && cached.isFresh()) return Optional.of(cached.accessToken());

        Optional<Session> stored = load(organizationId, mcpUrl);
        if (stored.isEmpty()) return Optional.empty();
        Session session = stored.get();

        if (session.tokens().isFresh()) {
            cache.put(normalize(mcpUrl), session.tokens());
            return Optional.of(session.tokens().accessToken());
        }
        if (session.tokens().refreshToken() == null || session.tokens().refreshToken().isBlank()) {
            // Expired with nothing to renew it. Reported as "not authorized" rather than silently
            // sending a dead token, which would come back as a confusing 401.
            return Optional.empty();
        }
        try {
            McpOAuth.Tokens fresh = oauth.refresh(session.endpoints(), session.registration(),
                    session.tokens().refreshToken());
            save(organizationId, mcpUrl, new Session(session.endpoints(), session.registration(), fresh));
            return Optional.of(fresh.accessToken());
        } catch (RuntimeException e) {
            log.warn("Could not refresh the authorization for {}: {}", mcpUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /** Drops a stored authorization, so the server has to be signed in to again. */
    public void forget(String organizationId, String mcpUrl) {
        cache.remove(normalize(mcpUrl));
        filedUnder(organizationId, labelFor(mcpUrl))
                .forEach(c -> credentials.delete(organizationId, c.id()));
    }
}
