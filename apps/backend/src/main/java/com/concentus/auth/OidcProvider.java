package com.concentus.auth;

import java.util.Locale;

/**
 * Where an identity provider's endpoints live, what to call it on the button, and which claims
 * carry the person's identity.
 *
 * <p>Presets and an escape hatch, because the alternative — a Microsoft-shaped implementation —
 * is a decision about somebody's supplier hidden inside an application they bought for something
 * else. Anything that speaks OpenID Connect works: Entra ID, Google, Auth0, a Keycloak or
 * Authentik somebody runs themselves.
 *
 * <p>Endpoints are discovered rather than configured wherever the provider publishes them, which
 * every OpenID Connect provider does. Three URLs copied by hand into configuration are three
 * chances to paste the wrong one, and the symptom of the wrong token endpoint is a sign-in that
 * fails after the person has already typed their password.
 *
 * <p>They can also be stated outright, for the providers that are OAuth2 but not OpenID Connect —
 * Discord is the preset here, GitHub would be another. Those publish no discovery document and
 * name their fields differently, so the endpoints and the two claims are configuration rather
 * than code: {@code subjectClaim} is the provider's own immutable id for the person, and
 * {@code emailClaim} is where their address lives in the same answer.
 */
public record OidcProvider(String id, String displayName, String issuer, String scope,
                           String authorizationUrl, String tokenUrl, String userinfoUrl,
                           String subjectClaim, String emailClaim) {

    /** The tenant placeholder in Microsoft's issuer — substituted from configuration. */
    private static final String MS_ISSUER = "https://login.microsoftonline.com/%s/v2.0";

    /** The standard OpenID Connect claims, which is what a discovered provider answers with. */
    public static final String STANDARD_SUBJECT = "sub";
    public static final String STANDARD_EMAIL = "email";

    /**
     * @param preset  microsoft | google | discord | generic
     * @param issuer  required by {@code generic}; ignored by the others, which know their own
     * @param tenant  Microsoft only: a directory id restricts sign-in to that directory, which is
     *                what an organization wants. "organizations" allows any work or school
     *                account; "common" also allows personal ones.
     */
    public static OidcProvider of(String preset, String issuer, String tenant, String displayName,
                                  String scope) {
        // Sanitised because it becomes part of a URL and a database key: a provider id is
        // written into user_identities beside every subject, and it arrives from configuration.
        String id = preset == null || preset.isBlank()
                ? "generic"
                : preset.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return switch (id) {
            case "microsoft" -> discovered(id, orDefault(displayName, "Microsoft"),
                    MS_ISSUER.formatted(orDefault(tenant, "organizations")),
                    orDefault(scope, "openid profile email"));
            case "google" -> discovered(id, orDefault(displayName, "Google"),
                    "https://accounts.google.com", orDefault(scope, "openid profile email"));
            // OAuth2, not OpenID Connect: no discovery document, and the person's id is "id"
            // rather than "sub". Stated here so it costs a client id and a secret like the others,
            // rather than being a shape this application cannot express.
            case "discord" -> new OidcProvider(id, orDefault(displayName, "Discord"), "",
                    orDefault(scope, "identify email"),
                    "https://discord.com/oauth2/authorize",
                    "https://discord.com/api/oauth2/token",
                    "https://discord.com/api/users/@me",
                    "id", STANDARD_EMAIL);
            // Anything else keeps the name the deployment gave it. Not folded into one
            // "generic": two providers configured that way would answer to the same id, so the
            // second would shadow the first and its button would send people to the wrong one.
            default -> discovered(id, orDefault(displayName, "your organization"),
                    issuer == null ? "" : issuer.trim().replaceAll("/+$", ""),
                    orDefault(scope, "openid profile email"));
        };
    }

    /** A provider that publishes its endpoints, so only the issuer is known up front. */
    private static OidcProvider discovered(String id, String displayName, String issuer, String scope) {
        return new OidcProvider(id, displayName, issuer, scope, null, null, null,
                STANDARD_SUBJECT, STANDARD_EMAIL);
    }

    /** The same provider with endpoints stated in configuration instead of discovered. */
    public OidcProvider withEndpoints(String authorization, String token, String userinfo) {
        return new OidcProvider(id, displayName, issuer, scope,
                blankToNull(authorization) == null ? authorizationUrl : authorization.trim(),
                blankToNull(token) == null ? tokenUrl : token.trim(),
                blankToNull(userinfo) == null ? userinfoUrl : userinfo.trim(),
                subjectClaim, emailClaim);
    }

    /** The same provider reading identity from different fields, for one that is not OIDC. */
    public OidcProvider withClaims(String subject, String email) {
        return new OidcProvider(id, displayName, issuer, scope, authorizationUrl, tokenUrl,
                userinfoUrl,
                blankToNull(subject) == null ? subjectClaim : subject.trim(),
                blankToNull(email) == null ? emailClaim : email.trim());
    }

    /** The configured value, trimmed, or the preset's own when configuration says nothing. */
    private static String orDefault(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Where the provider publishes its endpoints. Standard, and the same word for all of them. */
    public String discoveryUrl() {
        return issuer + "/.well-known/openid-configuration";
    }

    /** True when the endpoints are stated outright and there is nothing to discover. */
    public boolean hasStatedEndpoints() {
        return authorizationUrl != null && tokenUrl != null && userinfoUrl != null;
    }

    /**
     * Whether this provider can be reached at all: either it publishes its endpoints from an
     * issuer, or all three are stated. Half-stated endpoints are not usable — a sign-in that gets
     * as far as the token exchange and then fails has already cost the person their password.
     */
    public boolean isUsable() {
        return !issuer.isBlank() || hasStatedEndpoints();
    }
}
