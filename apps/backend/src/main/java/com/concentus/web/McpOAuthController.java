package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.llm.McpOAuth;
import com.concentus.llm.McpOAuthStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs Concentus in to an OAuth-protected MCP server, so a self-hosted model can use its tools.
 *
 * <p>Needed because the {@code claude} CLI keeps its own MCP authorizations in its own credential
 * store: a server that works on the Claude backends returns <b>401</b> to anything else, which
 * reads as a broken flow rather than as a missing authorization. This gives the application its
 * own grant.
 *
 * <p>Two steps, because the flow goes through a browser. {@code /start} registers a client,
 * builds the authorization URL and hands it back; {@code /callback} is where the browser lands
 * afterwards, and is the only endpoint here that is reachable without a session — an OAuth
 * redirect arrives as a plain top-level navigation carrying no credentials of ours.
 */
@RestController
@RequestMapping("/api/mcp/oauth")
public class McpOAuthController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(McpOAuthController.class);

    /** A pending authorization is short-lived; a stale one is a leak, not a feature. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final McpOAuth oauth;
    private final McpOAuthStore store;
    private final OrgContext orgContext;
    private final String redirectBase;

    public McpOAuthController(McpOAuth oauth, McpOAuthStore store, OrgContext orgContext,
                              @Value("${mcp.oauth.redirect-base:http://localhost:3000}") String redirectBase) {
        this.oauth = oauth;
        this.store = store;
        this.orgContext = orgContext;
        this.redirectBase = redirectBase.replaceAll("/+$", "");
    }

    /**
     * An authorization in flight.
     *
     * <p>In memory, keyed by the {@code state} the authorization server will echo back. It must
     * not be persisted: the code verifier is the secret that makes PKCE work, it is useful for
     * minutes, and a restart mid-sign-in should simply mean starting again.
     */
    private record Pending(String mcpUrl, String organizationId, McpOAuth.Endpoints endpoints,
                           McpOAuth.Registration registration, String codeVerifier, Instant startedAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public record StartRequest(String url, String scope) {
    }

    private String redirectUri() {
        return redirectBase + "/api/mcp/oauth/callback";
    }

    /**
     * Which organization owns an MCP grant.
     *
     * <p>The deployment's, not the signed-in user's — deliberately, and this was a live bug. A run
     * happens on a background thread with no principal, so it resolves credentials against the
     * configured organization, exactly as the mail poller does. Storing the grant under whoever
     * happened to click the button meant the UI could report "connected" while every run still got
     * 401, with nothing on screen to explain the contradiction.
     *
     * <p>It is not a hole in the isolation: flows themselves are deployment-wide, so a credential a
     * flow needs at run time has to be too. Who may create one is still gated by
     * {@code requireAdmin()}.
     */
    private String grantOwner() {
        return orgContext.defaultOrganizationId();
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartRequest body) {
        orgContext.requireAdmin();
        String organizationId = grantOwner();
        if (body.url() == null || body.url().isBlank()) {
            return Map.of("ok", false, "error", "The MCP node has no URL yet.");
        }
        String mcpUrl = body.url().trim();

        try {
            McpOAuth.Endpoints endpoints = oauth.discover(mcpUrl);
            McpOAuth.Registration registration =
                    oauth.register(endpoints, redirectUri(), "Concentus AI");
            String verifier = oauth.newCodeVerifier();
            String state = oauth.newState();

            expireStalePending();
            pending.put(state, new Pending(mcpUrl, organizationId, endpoints, registration,
                    verifier, Instant.now()));

            return Map.of("ok", true,
                    "authorizationUrl", oauth.authorizationUrl(endpoints, registration,
                            redirectUri(), verifier, state, body.scope()),
                    "redirectUri", redirectUri());
        } catch (McpOAuth.OAuthNotSupported e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /**
     * Where the browser lands after the person approves.
     *
     * <p>Returns HTML rather than JSON: a human is looking at this tab, not a script. Errors are
     * shown here too — a redirect that lands on a blank page gives no way to tell "declined" from
     * "the exchange failed".
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false)
                                           String errorDescription) {
        if (error != null && !error.isBlank()) {
            if (state != null) pending.remove(state);
            return page("Sign-in was not completed",
                    errorDescription == null || errorDescription.isBlank() ? error : errorDescription);
        }
        if (code == null || state == null) {
            return page("Something is missing", "The authorization server sent no code.");
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
            return page("This sign-in is no longer valid",
                    "Start it again from the MCP node. If the backend restarted while you were "
                    + "signing in, that is why — the pending sign-in is held in memory.");
        }

        try {
            McpOAuth.Tokens tokens = oauth.exchangeCode(p.endpoints(), p.registration(), code,
                    redirectUri(), p.codeVerifier());
            store.save(p.organizationId(), p.mcpUrl(),
                    new McpOAuthStore.Session(p.endpoints(), p.registration(), tokens));
            log.info("Authorized MCP server at {} for organization {}.", p.mcpUrl(), p.organizationId());
            return page("Connected", "You can close this tab and go back to Concentus.");
        } catch (RuntimeException e) {
            // Logged as well as shown: the page is a dead-end tab someone closes, and this is the
            // one place the authorization server's own reason is visible.
            log.warn("MCP OAuth token exchange failed for {}: {}", p.mcpUrl(), e.getMessage());
            return page("Could not finish the sign-in", e.getMessage());
        }
    }

    /** Whether a server is already authorized, for the button's label. */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String url) {
        orgContext.requireAdmin();
        String organizationId = grantOwner();
        boolean connected = store.accessToken(organizationId, url.trim()).isPresent();
        return Map.of("connected", connected, "redirectUri", redirectUri());
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestBody StartRequest body) {
        orgContext.requireAdmin();
        store.forget(grantOwner(), body.url().trim());
        return Map.of("connected", false);
    }

    private void expireStalePending() {
        Instant cutoff = Instant.now().minus(PENDING_TTL);
        pending.values().removeIf(p -> p.startedAt().isBefore(cutoff));
    }

    /** Minimal self-contained page — this tab has no access to the SPA's assets. */
    private static ResponseEntity<String> page(String title, String detail) {
        String html = """
                <!doctype html><meta charset="utf-8">
                <title>%s</title>
                <body style="font-family:system-ui;margin:3rem auto;max-width:32rem;color:#111">
                <h1 style="font-size:1.25rem">%s</h1>
                <p style="color:#555">%s</p>
                </body>""".formatted(escape(title), escape(title), escape(detail));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** The detail can contain a server's error text, which is not ours to trust as markup. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
