package com.concentus.llm;

import com.concentus.secrets.CredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * A credential that holds an entire OAuth authorization, and hands out its pieces one at a time.
 *
 * <p>It exists to delete a manual step that has no place in an automated product: running a
 * vendor's auth CLI on the machine that will run the flows. That step is merely annoying on a
 * laptop and impossible in a container, where a CLI that opens a browser and waits on its own
 * loopback port has neither a browser nor a reachable port. Here the app performs the browser
 * dance once — in the system browser, as RFC 8252 requires — keeps the grant encrypted with every
 * other credential, and feeds a local MCP server through its environment.
 *
 * <p>Which is why a grant is read field by field rather than as a value. One Google authorization
 * answers four environment variables of the Google Ads server: client id, client secret, refresh
 * token, and an access token for anything that simply wants a bearer. Handing over the stored
 * document instead would hand over all of them at once, to a process that asked for one.
 *
 * @see OAuthCredentialFlow the sign-in that fills one of these in
 */
@Component
public class OAuthCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(OAuthCredentialStore.class);

    /** Credential kind, so a picker can tell these from a pasted token. */
    public static final String KIND = "oauth";

    /** What {@code credential:<id>} means with no suffix. Never the stored document. */
    public static final String DEFAULT_FIELD = "access_token";

    private final CredentialStore credentials;
    private final McpOAuth oauth;
    private final ObjectMapper mapper;

    public OAuthCredentialStore(CredentialStore credentials, McpOAuth oauth, ObjectMapper mapper) {
        this.credentials = credentials;
        this.oauth = oauth;
        this.mapper = mapper;
    }

    /**
     * One authorization: the client half, which is configuration, and the token half, which is
     * the grant and is absent until somebody signs in.
     */
    public record Grant(String authorizationUrl, String tokenUrl, String clientId,
                        String clientSecret, String scope, String authParams,
                        String accessToken, String refreshToken, Instant expiresAt) {

        public boolean connected() {
            return refreshToken != null && !refreshToken.isBlank()
                    || accessToken != null && !accessToken.isBlank();
        }

        public Grant withTokens(McpOAuth.Tokens tokens) {
            return new Grant(authorizationUrl, tokenUrl, clientId, clientSecret, scope, authParams,
                    tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
        }
    }

    /** True when this credential is an OAuth grant rather than a pasted secret. */
    public boolean isOAuth(String organizationId, String credentialId) {
        return credentials.list(organizationId).stream()
                .anyMatch(c -> c.id().equals(credentialId) && KIND.equals(c.kind()));
    }

    public Optional<Grant> load(String organizationId, String credentialId) {
        if (!isOAuth(organizationId, credentialId)) return Optional.empty();
        return credentials.reveal(organizationId, credentialId).flatMap(this::parse);
    }

    public void save(String organizationId, String credentialId, Grant grant) {
        credentials.updateSecret(organizationId, credentialId, toJson(grant));
    }

    /**
     * One piece of a stored authorization.
     *
     * <p>{@code access_token} is renewed on the way out when it has aged past its expiry, so a
     * long run does not hand a dead token to a process with no way to ask for another. The client
     * half needs no grant: it is readable from the moment the credential is created, which is what
     * lets a node be wired up before anyone has signed in.
     *
     * @param field one of access_token, refresh_token, client_id, client_secret, scope; null means
     *              {@link #DEFAULT_FIELD}
     * @return empty when the credential is not an OAuth one, the field is unknown, or the piece
     *         asked for is not there yet
     */
    public Optional<String> field(String organizationId, String credentialId, String field) {
        Optional<Grant> loaded = load(organizationId, credentialId);
        if (loaded.isEmpty()) return Optional.empty();
        Grant grant = loaded.get();

        String name = (field == null || field.isBlank() ? DEFAULT_FIELD : field.trim())
                .toLowerCase(Locale.ROOT);
        return switch (name) {
            case "access_token" -> nonBlank(freshAccessToken(organizationId, credentialId, grant));
            case "refresh_token" -> nonBlank(grant.refreshToken());
            case "client_id" -> nonBlank(grant.clientId());
            case "client_secret" -> nonBlank(grant.clientSecret());
            case "scope" -> nonBlank(grant.scope());
            // Refused rather than guessed: a typo that quietly resolved to the access token would
            // put a bearer where the server expected something else, and the failure would surface
            // as the vendor's own generic 400, days later.
            default -> Optional.empty();
        };
    }

    private String freshAccessToken(String organizationId, String credentialId, Grant grant) {
        McpOAuth.Tokens tokens = new McpOAuth.Tokens(grant.accessToken(), grant.refreshToken(),
                grant.expiresAt());
        if (tokens.isFresh()) return grant.accessToken();
        if (grant.refreshToken() == null || grant.refreshToken().isBlank()) return grant.accessToken();

        try {
            McpOAuth.Tokens renewed = oauth.refresh(endpointsOf(grant), registrationOf(grant),
                    grant.refreshToken());
            save(organizationId, credentialId, grant.withTokens(renewed));
            return renewed.accessToken();
        } catch (RuntimeException e) {
            // The old token is returned rather than nothing: it may still work — providers are
            // lenient about their own margins — and the caller's 401 handling is a better place to
            // give up than a guess made here.
            log.warn("Could not renew the OAuth credential {}: {}", credentialId, e.getMessage());
            return grant.accessToken();
        }
    }

    /** The shape {@link McpOAuth} exchanges against. No discovery: these endpoints are typed in. */
    static McpOAuth.Endpoints endpointsOf(Grant grant) {
        return new McpOAuth.Endpoints(grant.authorizationUrl(), grant.tokenUrl(), null, null);
    }

    static McpOAuth.Registration registrationOf(Grant grant) {
        return new McpOAuth.Registration(grant.clientId(), grant.clientSecret());
    }

    public String toJson(Grant grant) {
        ObjectNode node = mapper.createObjectNode();
        node.put("authorizationUrl", grant.authorizationUrl());
        node.put("tokenUrl", grant.tokenUrl());
        node.put("clientId", grant.clientId());
        node.put("clientSecret", grant.clientSecret());
        node.put("scope", grant.scope());
        node.put("authParams", grant.authParams());
        node.put("accessToken", grant.accessToken());
        node.put("refreshToken", grant.refreshToken());
        node.put("expiresAt", grant.expiresAt() == null ? null : grant.expiresAt().toString());
        return node.toString();
    }

    public Optional<Grant> parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            String expiresAt = n.path("expiresAt").asText(null);
            return Optional.of(new Grant(
                    n.path("authorizationUrl").asText(null),
                    n.path("tokenUrl").asText(null),
                    n.path("clientId").asText(null),
                    n.path("clientSecret").asText(null),
                    n.path("scope").asText(null),
                    n.path("authParams").asText(null),
                    n.path("accessToken").asText(null),
                    n.path("refreshToken").asText(null),
                    expiresAt == null || expiresAt.isBlank() ? null : Instant.parse(expiresAt)));
        } catch (Exception e) {
            log.warn("Stored OAuth credential could not be read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
