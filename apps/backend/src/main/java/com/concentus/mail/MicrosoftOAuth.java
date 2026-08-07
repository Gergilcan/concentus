package com.concentus.mail;

import com.concentus.integration.Redact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * The OAuth2 device code flow against Microsoft Entra ID, for IMAP access to Microsoft 365.
 *
 * <h2>Why this exists</h2>
 * Microsoft retired Basic authentication for IMAP in Exchange Online, so a username and password
 * are refused before they are even evaluated — which is why a correct password returns
 * {@code AUTHENTICATE failed}. A mail client on a phone is not doing anything different or
 * privileged: it runs an OAuth sign-in in a browser and keeps a refresh token. This is the same
 * thing, arranged so it can be completed once from any device.
 *
 * <h2>Why the device code flow</h2>
 * It needs no redirect URI and no browser on the machine running the code: the server asks for a
 * code, a person enters it at {@code microsoft.com/devicelogin} on whatever device they like, and
 * the server collects the tokens by polling. That is what makes it usable from a container, where
 * there is neither a browser nor a desktop.
 *
 * <p>It is also a <b>public client</b> flow, so the app registration carries no client secret.
 * The only long-lived secret is the refresh token, which is stored encrypted like any other
 * credential.
 */
@Component
public class MicrosoftOAuth {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftOAuth.class);

    /**
     * IMAP access on behalf of the signed-in user, plus a refresh token.
     *
     * <p>{@code offline_access} is what makes this a one-time sign-in rather than an hourly one —
     * without it Entra returns an access token and nothing to renew it with.
     */
    public static final String IMAP_SCOPE =
            "https://outlook.office365.com/IMAP.AccessAsUser.All offline_access";

    /** Refresh a little before expiry, so a token cannot lapse mid-poll. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /** Entra's public cloud. Overridable so tests can point at a local stub. */
    static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String authority;
    private final String defaultTenantId;
    private final String defaultClientId;

    // Two constructors, so Spring needs to be told which one: the other exists for tests.
    @org.springframework.beans.factory.annotation.Autowired
    public MicrosoftOAuth(ObjectMapper mapper,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${mail.microsoft.tenant-id:}") String defaultTenantId,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${mail.microsoft.client-id:}") String defaultClientId) {
        this(mapper, DEFAULT_AUTHORITY, defaultTenantId, defaultClientId);
    }

    MicrosoftOAuth(ObjectMapper mapper, String authority) {
        this(mapper, authority, "", "");
    }

    MicrosoftOAuth(ObjectMapper mapper, String authority, String defaultTenantId, String defaultClientId) {
        this.mapper = mapper;
        this.authority = authority.endsWith("/")
                ? authority.substring(0, authority.length() - 1) : authority;
        this.defaultTenantId = trim(defaultTenantId);
        this.defaultClientId = trim(defaultClientId);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /**
     * The app registration this deployment uses when a node names none.
     *
     * <p>One Entra app registration serves every mailbox in a deployment — it identifies the
     * <em>application</em>, not the mailbox — so asking for the same two ids on every node is
     * asking the same question repeatedly. Neither is a secret: the client id is public by design
     * in a public-client flow, and the tenant id is in every sign-in URL. They are configuration.
     *
     * <p>A node may still override them, for the deployment that talks to two tenants.
     */
    public String defaultTenantId() {
        return defaultTenantId;
    }

    public String defaultClientId() {
        return defaultClientId;
    }

    /** True when a sign-in needs nothing typed on the node. */
    public boolean hasDefaults() {
        return !defaultTenantId.isBlank() && !defaultClientId.isBlank();
    }

    private String tenantOr(String tenantId) {
        return trim(tenantId).isBlank() ? defaultTenantId : trim(tenantId);
    }

    private String clientOr(String clientId) {
        return trim(clientId).isBlank() ? defaultClientId : trim(clientId);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public static class OAuthException extends RuntimeException {
        public OAuthException(String message) {
            super(message);
        }
    }

    /**
     * What the person has to do, and what we need to keep to finish the flow.
     *
     * @param deviceCode the value polled with; not shown to anyone
     * @param userCode   the short code the person types
     * @param interval   seconds Entra asks us to wait between polls; polling faster earns a
     *                   {@code slow_down} and makes the sign-in take longer, not shorter
     */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             String message, int expiresIn, int interval) {
    }

    /** Tokens as Entra returns them. The refresh token is what gets stored. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {

        public boolean isFresh() {
            return accessToken != null && expiresAt != null
                    && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }
    }

    /** Starts a sign-in. The caller shows {@code userCode} and {@code verificationUri} to a person. */
    public DeviceCode startDeviceCode(String tenantId, String clientId) {
        tenantId = tenantOr(tenantId);
        clientId = clientOr(clientId);
        requireIds(tenantId, clientId);
        JsonNode body = post(endpoint(tenantId, "devicecode"),
                "client_id=" + enc(clientId) + "&scope=" + enc(IMAP_SCOPE));
        return new DeviceCode(
                text(body, "device_code"),
                text(body, "user_code"),
                text(body, "verification_uri"),
                text(body, "message"),
                body.path("expires_in").asInt(900),
                Math.max(1, body.path("interval").asInt(5)));
    }

    /**
     * Checks whether the person has finished signing in.
     *
     * @return the tokens once approved, or empty while still pending
     * @throws OAuthException if the sign-in was declined, expired, or the registration is wrong
     */
    public java.util.Optional<Tokens> pollDeviceCode(String tenantId, String clientId, String deviceCode) {
        tenantId = tenantOr(tenantId);
        clientId = clientOr(clientId);
        requireIds(tenantId, clientId);
        JsonNode body;
        try {
            body = post(endpoint(tenantId, "token"),
                    "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                            + "&client_id=" + enc(clientId)
                            + "&device_code=" + enc(deviceCode));
        } catch (OAuthException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            // "Not finished yet" and "slow down" are the normal states of a flow in progress, not
            // failures — reporting them as errors would make a working sign-in look broken.
            if (message.contains("authorization_pending") || message.contains("slow_down")) {
                return java.util.Optional.empty();
            }
            throw new OAuthException(explainDeviceError(message));
        }
        return java.util.Optional.of(toTokens(body));
    }

    /**
     * Exchanges a refresh token for a fresh access token.
     *
     * <p>Entra rotates refresh tokens, so the returned one must replace the stored one. Keeping
     * the old one works until it doesn't, and then the mailbox stops being polled with no obvious
     * cause.
     */
    public Tokens refresh(String tenantId, String clientId, String refreshToken) {
        tenantId = tenantOr(tenantId);
        clientId = clientOr(clientId);
        requireIds(tenantId, clientId);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new OAuthException("No refresh token stored — sign in again.");
        }
        JsonNode body;
        try {
            body = post(endpoint(tenantId, "token"),
                    "grant_type=refresh_token"
                            + "&client_id=" + enc(clientId)
                            + "&refresh_token=" + enc(refreshToken)
                            + "&scope=" + enc(IMAP_SCOPE));
        } catch (OAuthException e) {
            throw new OAuthException("The stored Microsoft sign-in is no longer valid — this "
                    + "usually means the account's password changed or a policy revoked it. "
                    + "Sign in again from the Input node. (" + e.getMessage() + ")");
        }
        Tokens tokens = toTokens(body);
        // Entra may omit the refresh token when it has not rotated; keep the existing one so the
        // caller does not overwrite a working value with null.
        return tokens.refreshToken() == null
                ? new Tokens(tokens.accessToken(), refreshToken, tokens.expiresAt())
                : tokens;
    }

    private Tokens toTokens(JsonNode body) {
        String access = text(body, "access_token");
        if (access == null) throw new OAuthException("Entra returned no access token.");
        long expiresIn = body.path("expires_in").asLong(3600);
        return new Tokens(access, text(body, "refresh_token"), Instant.now().plusSeconds(expiresIn));
    }

    // ---- HTTP ----

    private String endpoint(String tenantId, String leaf) {
        return authority + "/" + enc(tenantId) + "/oauth2/v2.0/" + leaf;
    }

    private JsonNode post(String url, String form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                // The body echoes the request, so redact before it reaches a log or an error field.
                throw new OAuthException(Redact.secrets(res.body()));
            }
            return body;
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("Could not reach Entra ID: " + e.getMessage());
        }
    }

    /** Turns Entra's error codes into something an operator can act on. */
    private static String explainDeviceError(String raw) {
        if (raw.contains("expired_token")) {
            return "The code expired before it was entered. Start the sign-in again.";
        }
        if (raw.contains("authorization_declined")) {
            return "The sign-in was declined.";
        }
        if (raw.contains("unauthorized_client")) {
            return "The app registration does not allow this flow. In Entra ID, open the app's "
                    + "Authentication page and set \"Allow public client flows\" to Yes.";
        }
        if (raw.contains("invalid_client")) {
            return "Entra did not recognise the client id, or the app expects a secret. The device "
                    + "code flow needs a public client, which has none.";
        }
        if (raw.contains("consent")) {
            return "Consent is required for IMAP.AccessAsUser.All and this tenant does not let a "
                    + "user grant it. An administrator has to consent to the app once.";
        }
        return raw;
    }

    private static void requireIds(String tenantId, String clientId) {
        if (tenantId == null || tenantId.isBlank() || clientId == null || clientId.isBlank()) {
            throw new OAuthException("No Microsoft app registration is configured. Set "
                    + "MAIL_MICROSOFT_TENANT_ID and MAIL_MICROSOFT_CLIENT_ID for the deployment, "
                    + "or fill both in under Advanced on this node.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
