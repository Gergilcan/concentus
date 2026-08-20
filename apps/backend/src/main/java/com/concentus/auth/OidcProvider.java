package com.concentus.auth;

import java.util.Locale;

/**
 * Where the identity provider's endpoints live, and what to call it on the button.
 *
 * <p>Three presets and an escape hatch, because the alternative — a Microsoft-shaped
 * implementation — is a decision about somebody's supplier hidden inside an application they
 * bought for something else. Anything that speaks OpenID Connect works: Entra ID, Google, Auth0,
 * a Keycloak or Authentik somebody runs themselves.
 *
 * <p>Endpoints are discovered rather than configured wherever the provider publishes them, which
 * every one of these does. Three URLs copied by hand into configuration are three chances to
 * paste the wrong one, and the symptom of the wrong token endpoint is a sign-in that fails after
 * the person has already typed their password.
 */
public record OidcProvider(String id, String displayName, String issuer, String scope) {

    /** The tenant placeholder in Microsoft's issuer — substituted from configuration. */
    private static final String MS_ISSUER = "https://login.microsoftonline.com/%s/v2.0";

    /**
     * @param preset  microsoft | google | generic
     * @param issuer  required by {@code generic}; ignored by the others, which know their own
     * @param tenant  Microsoft only: a directory id restricts sign-in to that directory, which is
     *                what an organization wants. "organizations" allows any work or school
     *                account; "common" also allows personal ones.
     */
    public static OidcProvider of(String preset, String issuer, String tenant, String displayName,
                                  String scope) {
        String id = preset == null || preset.isBlank()
                ? "generic" : preset.trim().toLowerCase(Locale.ROOT);
        String scopes = scope == null || scope.isBlank() ? "openid profile email" : scope.trim();
        return switch (id) {
            case "microsoft" -> new OidcProvider(id, name(displayName, "Microsoft"),
                    MS_ISSUER.formatted(tenant == null || tenant.isBlank()
                            ? "organizations" : tenant.trim()),
                    scopes);
            case "google" -> new OidcProvider(id, name(displayName, "Google"),
                    "https://accounts.google.com", scopes);
            default -> new OidcProvider("generic", name(displayName, "your organization"),
                    issuer == null ? "" : issuer.trim().replaceAll("/+$", ""), scopes);
        };
    }

    private static String name(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    /** Where the provider publishes its endpoints. Standard, and the same word for all of them. */
    public String discoveryUrl() {
        return issuer + "/.well-known/openid-configuration";
    }

    public boolean isUsable() {
        return !issuer.isBlank();
    }
}
