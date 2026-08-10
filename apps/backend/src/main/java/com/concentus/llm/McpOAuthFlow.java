package com.concentus.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OAuth sign-in flow for an MCP server: start, wait for the browser, finish.
 *
 * <p>Extracted from the controller that used to own it, so nothing but HTTP concerns live at the
 * web layer and the flow itself can be driven — and tested — without a servlet request. The
 * pending map's lifetime now belongs to this service rather than accidentally to the web layer.
 *
 * <p>Pending authorizations are held in memory on purpose: the code verifier is the secret that
 * makes PKCE work, it is useful for minutes, and a restart mid-sign-in should mean starting
 * again, not resuming from disk.
 */
@Service
public class McpOAuthFlow {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthFlow.class);

    /** A pending authorization is short-lived; a stale one is a leak, not a feature. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final McpOAuth oauth;
    private final McpOAuthStore store;
    private final String redirectBase;

    public McpOAuthFlow(McpOAuth oauth, McpOAuthStore store,
                        @Value("${mcp.oauth.redirect-base:http://localhost:3000}") String redirectBase) {
        this.oauth = oauth;
        this.store = store;
        this.redirectBase = redirectBase.replaceAll("/+$", "");
    }

    /** An authorization in flight, keyed by the {@code state} the server will echo back. */
    private record Pending(String mcpUrl, String organizationId, McpOAuth.Endpoints endpoints,
                           McpOAuth.Registration registration, String codeVerifier, Instant startedAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public record StartResult(boolean ok, String error, String authorizationUrl, String redirectUri) {
    }

    /** What the callback tab should say. {@code connected} distinguishes success from the rest. */
    public record CallbackOutcome(boolean connected, String title, String detail) {
    }

    public String redirectUri() {
        return redirectBase + "/api/mcp/oauth/callback";
    }

    public StartResult start(String organizationId, String url, String scope) {
        if (url == null || url.isBlank()) {
            return new StartResult(false, "The MCP node has no URL yet.", null, redirectUri());
        }
        String mcpUrl = url.trim();
        try {
            McpOAuth.Endpoints endpoints = oauth.discover(mcpUrl);
            McpOAuth.Registration registration = oauth.register(endpoints, redirectUri(), "Concentus AI");
            String verifier = oauth.newCodeVerifier();
            String state = oauth.newState();

            expireStalePending();
            pending.put(state, new Pending(mcpUrl, organizationId, endpoints, registration,
                    verifier, Instant.now()));

            return new StartResult(true, null,
                    oauth.authorizationUrl(endpoints, registration, redirectUri(), verifier, state, scope),
                    redirectUri());
        } catch (McpOAuth.OAuthNotSupported e) {
            return new StartResult(false, e.getMessage(), null, redirectUri());
        }
    }

    public CallbackOutcome finish(String code, String state, String error, String errorDescription) {
        if (error != null && !error.isBlank()) {
            if (state != null) pending.remove(state);
            return new CallbackOutcome(false, "Sign-in was not completed",
                    errorDescription == null || errorDescription.isBlank() ? error : errorDescription);
        }
        if (code == null || state == null) {
            return new CallbackOutcome(false, "Something is missing",
                    "The authorization server sent no code.");
        }

        Pending p = pending.remove(state);
        if (p == null) {
            // Unknown state: expired, already used, or forged. All three get the same answer —
            // accepting an unrecognised state is exactly the CSRF this parameter prevents.
            //
            // In practice the common cause is neither: the backend restarted between starting the
            // sign-in and coming back, and this map is in memory. Worth saying, because "expired"
            // on a sign-in that took twenty seconds is baffling otherwise.
            log.warn("MCP OAuth callback carried an unknown state. {} sign-in(s) are pending; a "
                    + "backend restart between starting and returning empties them.", pending.size());
            return new CallbackOutcome(false, "This sign-in is no longer valid",
                    "Start it again from the MCP node. If the backend restarted while you were "
                            + "signing in, that is why — the pending sign-in is held in memory.");
        }

        try {
            McpOAuth.Tokens tokens = oauth.exchangeCode(p.endpoints(), p.registration(), code,
                    redirectUri(), p.codeVerifier());
            store.save(p.organizationId(), p.mcpUrl(),
                    new McpOAuthStore.Session(p.endpoints(), p.registration(), tokens));
            log.info("Authorized MCP server at {} for organization {}.", p.mcpUrl(), p.organizationId());
            return new CallbackOutcome(true, "Connected",
                    "You can close this tab and go back to Concentus.");
        } catch (RuntimeException e) {
            // Logged as well as shown: the page is a dead-end tab someone closes, and this is the
            // one place the authorization server's own reason is visible.
            log.warn("MCP OAuth token exchange failed for {}: {}", p.mcpUrl(), e.getMessage());
            return new CallbackOutcome(false, "Could not finish the sign-in", e.getMessage());
        }
    }

    public boolean connected(String organizationId, String url) {
        return store.accessToken(organizationId, url.trim()).isPresent();
    }

    public void disconnect(String organizationId, String url) {
        store.forget(organizationId, url.trim());
    }

    private void expireStalePending() {
        Instant cutoff = Instant.now().minus(PENDING_TTL);
        pending.values().removeIf(p -> p.startedAt().isBefore(cutoff));
    }
}
