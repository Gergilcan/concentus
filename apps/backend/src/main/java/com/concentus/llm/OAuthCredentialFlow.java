package com.concentus.llm;

import com.concentus.support.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The browser sign-in that fills an {@link OAuthCredentialStore.Grant}.
 *
 * <p>Deliberately the same shape as {@link McpOAuthFlow}, and deliberately not the same class. That
 * one discovers its endpoints from an MCP server and registers a client on the fly; this one is
 * handed endpoints and a client someone created in the provider's console, because Google, Meta
 * and the rest support neither discovery nor dynamic registration. What the two share is the part
 * worth sharing — PKCE, the state check, the token exchange — and that lives in {@link McpOAuth}.
 *
 * <p>The sign-in runs in the <b>system browser</b>, never an embedded webview: Google answers a
 * webview with {@code disallowed_useragent}, and RFC 8252 forbids it for native apps regardless,
 * because a host application that can drive the login page can read the password typed into it.
 */
@Service
public class OAuthCredentialFlow {

    private static final Logger log = LoggerFactory.getLogger(OAuthCredentialFlow.class);

    /** A pending sign-in is short-lived; a stale one is a leak, not a feature. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    /** Where the provider sends the browser back. Public so the UI can show it to be registered. */
    public static final String CALLBACK_PATH = "/api/credentials/oauth/callback";

    private final OAuthCredentialStore store;
    private final McpOAuth oauth;
    private final String redirectBase;

    public OAuthCredentialFlow(OAuthCredentialStore store, McpOAuth oauth,
                               @Value("${mcp.oauth.redirect-base:}") String redirectBase) {
        this.store = store;
        this.oauth = oauth;
        // Blank means "derive from the request": the address the browser reached the app through
        // is, by definition, one it can reach again. Shared with the MCP sign-in on purpose — a
        // deployment behind a proxy has one answer to this question, not two.
        this.redirectBase = Texts.trimTrailingSlashes(redirectBase);
    }

    /**
     * A sign-in in flight, keyed by the {@code state} the provider will echo back.
     *
     * <p>Holds the exact redirect URI the authorization was started with: the token exchange must
     * repeat it verbatim (RFC 6749 §4.1.3), and recomputing it at callback time could differ if
     * the callback arrives through another spelling of the host.
     */
    private record Pending(String organizationId, String credentialId, String codeVerifier,
                           String redirectUri, Instant startedAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public record StartResult(boolean ok, String error, String authorizationUrl, String redirectUri) {
    }

    /** What the callback tab should say. {@code connected} distinguishes success from the rest. */
    public record CallbackOutcome(boolean connected, String title, String detail) {
    }

    public String redirectUri(String requestBase) {
        String base = redirectBase.isBlank() ? Texts.trimTrailingSlashes(requestBase) : redirectBase;
        return base + CALLBACK_PATH;
    }

    public StartResult start(String organizationId, String credentialId, String requestBase) {
        String redirect = redirectUri(requestBase);

        var loaded = store.load(organizationId, credentialId);
        if (loaded.isEmpty()) {
            return new StartResult(false, "That credential is not an OAuth authorization.", null, redirect);
        }
        OAuthCredentialStore.Grant grant = loaded.get();
        if (isBlank(grant.authorizationUrl()) || isBlank(grant.tokenUrl()) || isBlank(grant.clientId())) {
            return new StartResult(false,
                    "This credential still needs its authorization URL, token URL and client id.",
                    null, redirect);
        }

        String verifier = oauth.newCodeVerifier();
        String state = oauth.newState();
        expireStalePending();
        pending.put(state, new Pending(organizationId, credentialId, verifier, redirect, Instant.now()));

        String url = oauth.authorizationUrl(OAuthCredentialStore.endpointsOf(grant),
                OAuthCredentialStore.registrationOf(grant), redirect, verifier, state, grant.scope());
        return new StartResult(true, null, withAuthParams(url, grant.authParams()), redirect);
    }

    /**
     * Provider-specific query parameters, appended verbatim.
     *
     * <p>This is where the standard stops and each vendor begins. Google returns a refresh token
     * only for {@code access_type=offline}, and only on the first consent unless
     * {@code prompt=consent} forces it — miss either and the credential works today and is dead
     * tomorrow, which is the worst kind of broken. Carried as data on the credential rather than
     * as a rule in code, because the next provider will want something else.
     */
    private static String withAuthParams(String url, String authParams) {
        if (authParams == null || authParams.isBlank()) return url;
        String extra = authParams.trim();
        if (extra.startsWith("&") || extra.startsWith("?")) extra = extra.substring(1);
        return url + "&" + extra;
    }

    public CallbackOutcome finish(String code, String state, String error, String errorDescription) {
        if (error != null && !error.isBlank()) {
            if (state != null) pending.remove(state);
            return new CallbackOutcome(false, "Sign-in was not completed",
                    isBlank(errorDescription) ? error : errorDescription);
        }
        if (code == null || state == null) {
            return new CallbackOutcome(false, "Something is missing",
                    "The provider sent no authorization code.");
        }

        Pending p = pending.remove(state);
        if (p == null) {
            // Unknown state: expired, already used, or forged — and all three get the same answer,
            // because accepting an unrecognised state is exactly the CSRF this parameter prevents.
            // In practice the common cause is a backend restart mid-sign-in; this map is memory.
            return new CallbackOutcome(false, "This sign-in is no longer valid",
                    "Start it again from the credential. If the app restarted while the browser "
                            + "was open, that is why.");
        }

        var loaded = store.load(p.organizationId(), p.credentialId());
        if (loaded.isEmpty()) {
            return new CallbackOutcome(false, "That credential is gone",
                    "It was deleted while the browser was open.");
        }
        OAuthCredentialStore.Grant grant = loaded.get();

        try {
            McpOAuth.Tokens tokens = oauth.exchangeCode(OAuthCredentialStore.endpointsOf(grant),
                    OAuthCredentialStore.registrationOf(grant), code, p.redirectUri(), p.codeVerifier());
            if (isBlank(tokens.refreshToken())) {
                // Worth saying out loud rather than reporting success: without a refresh token the
                // credential stops working in an hour, and the run that discovers it will be a
                // 3am cron with nobody watching.
                log.warn("The provider returned no refresh token for credential {} — check the "
                        + "authorization parameters (Google needs access_type=offline).",
                        p.credentialId());
            }
            store.save(p.organizationId(), p.credentialId(), grant.withTokens(tokens));
            return new CallbackOutcome(true, "Connected",
                    isBlank(tokens.refreshToken())
                            ? "Signed in, but the provider returned no refresh token: this will "
                              + "stop working when the access token expires. Add "
                              + "access_type=offline&prompt=consent and connect again."
                            : "You can close this tab and go back to Concentus.");
        } catch (RuntimeException e) {
            log.warn("Token exchange failed for credential {}: {}", p.credentialId(), e.getMessage());
            return new CallbackOutcome(false, "The provider refused the exchange", e.getMessage());
        }
    }

    private void expireStalePending() {
        Instant cutoff = Instant.now().minus(PENDING_TTL);
        pending.entrySet().removeIf(e -> e.getValue().startedAt().isBefore(cutoff));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
