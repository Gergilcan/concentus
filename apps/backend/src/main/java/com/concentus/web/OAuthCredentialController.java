package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.llm.OAuthCredentialFlow;
import com.concentus.llm.OAuthCredentialStore;
import com.concentus.secrets.CredentialStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Credentials the app signs in to, rather than ones somebody pastes.
 *
 * <p>Its own controller because the shape differs from every other credential in one dangerous
 * way: the stored value is a document — client id, client secret, refresh token, access token —
 * rather than a single string. {@link CredentialController} can promise "values go in and never
 * come back" through its types alone; here the promise has to be kept by hand, which is why the
 * status endpoint reports <em>about</em> the grant and never any part of it that is secret.
 *
 * <p>Sign-in happens in the system browser and returns to {@code /api/credentials/oauth/callback}
 * on this backend. That is what makes it work in a container: the vendor's own auth CLI would open
 * a browser that does not exist there and listen on a port nobody can reach, while this callback
 * lands on the address the browser already used to reach the app.
 */
@RestController
@RequestMapping("/api/credentials")
public class OAuthCredentialController {

    private final CredentialStore credentials;
    private final OAuthCredentialStore store;
    private final OAuthCredentialFlow flow;
    private final OrgContext orgContext;

    public OAuthCredentialController(CredentialStore credentials, OAuthCredentialStore store,
                                     OAuthCredentialFlow flow, OrgContext orgContext) {
        this.credentials = credentials;
        this.store = store;
        this.flow = flow;
        this.orgContext = orgContext;
    }

    /**
     * @param authParams provider-specific query parameters for the authorization URL. Google needs
     *                   {@code access_type=offline&prompt=consent} or it returns no refresh token.
     */
    public record NewOAuthCredential(String label, String authorizationUrl, String tokenUrl,
                                     String clientId, String clientSecret, String scope,
                                     String authParams) {
    }

    @PostMapping("/oauth")
    public Map<String, Object> create(@RequestBody NewOAuthCredential body) {
        orgContext.requireAdmin();
        OAuthCredentialStore.Grant grant = grantOf(body, null);
        CredentialStore.Credential created = credentials.create(organizationId(), body.label(),
                OAuthCredentialStore.KIND, store.toJson(grant));
        return Map.of("id", created.id(), "label", body.label());
    }

    /**
     * Replaces the configuration half, keeping the tokens.
     *
     * <p>A corrected client id or an added scope must not silently sign the user out — and the
     * opposite is true too, which is why the UI says a scope change needs connecting again.
     */
    @PutMapping("/{id}/oauth")
    public Map<String, Object> update(@PathVariable String id, @RequestBody NewOAuthCredential body) {
        orgContext.requireAdmin();
        OAuthCredentialStore.Grant existing = store.load(organizationId(), id).orElse(null);
        if (existing == null) return Map.of("ok", false, "error", "No such OAuth credential.");
        store.save(organizationId(), id, grantOf(body, existing));
        return Map.of("ok", true);
    }

    /** What the credential's card shows: enough to act on, nothing secret. */
    @GetMapping("/{id}/oauth")
    public Map<String, Object> status(@PathVariable String id) {
        orgContext.requireAdmin();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redirectUri", flow.redirectUri(requestBase()));

        OAuthCredentialStore.Grant grant = store.load(organizationId(), id).orElse(null);
        if (grant == null) {
            out.put("connected", false);
            out.put("error", "No such OAuth credential.");
            return out;
        }
        out.put("connected", grant.connected());
        out.put("clientId", grant.clientId());
        out.put("authorizationUrl", grant.authorizationUrl());
        out.put("tokenUrl", grant.tokenUrl());
        out.put("scope", grant.scope());
        out.put("authParams", grant.authParams());
        // Reported separately from "connected" on purpose: without a refresh token the grant works
        // until the access token expires and then stops, which is a different state from working.
        out.put("hasRefreshToken", grant.refreshToken() != null && !grant.refreshToken().isBlank());
        out.put("expiresAt", grant.expiresAt() == null ? null : grant.expiresAt().toString());
        return out;
    }

    @PostMapping("/{id}/oauth/start")
    public Map<String, Object> start(@PathVariable String id) {
        orgContext.requireAdmin();
        OAuthCredentialFlow.StartResult result = flow.start(organizationId(), id, requestBase());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("redirectUri", result.redirectUri());
        if (result.ok()) out.put("authorizationUrl", result.authorizationUrl());
        else out.put("error", result.error());
        return out;
    }

    /**
     * Where the browser lands after the person approves.
     *
     * <p>HTML rather than JSON: a human is looking at this tab. Unauthenticated by necessity — the
     * provider redirects a bare browser here — which is exactly what {@code state} is for.
     */
    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false)
                                           String errorDescription) {
        OAuthCredentialFlow.CallbackOutcome outcome = flow.finish(code, state, error, errorDescription);
        return page(outcome.title(), outcome.detail());
    }

    /** Keeps the tokens of {@code existing} when there is one; a new credential starts with none. */
    private static OAuthCredentialStore.Grant grantOf(NewOAuthCredential body,
                                                      OAuthCredentialStore.Grant existing) {
        return new OAuthCredentialStore.Grant(
                trim(body.authorizationUrl()), trim(body.tokenUrl()), trim(body.clientId()),
                body.clientSecret() == null ? null : body.clientSecret().trim(),
                trim(body.scope()), trim(body.authParams()),
                existing == null ? null : existing.accessToken(),
                existing == null ? null : existing.refreshToken(),
                existing == null ? null : existing.expiresAt());
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String organizationId() {
        return orgContext.defaultOrganizationId();
    }

    /** The address this browser reached the backend through — one it can, by definition, reach again. */
    private static String requestBase() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
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

    /** The detail can carry a provider's error text, which is not ours to trust as markup. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
