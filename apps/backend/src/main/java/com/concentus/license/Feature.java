package com.concentus.license;

/**
 * What the Enterprise tier has that Team does not.
 *
 * <p>Team is the tier for a team of up to ten that wants to work together: the shared database,
 * members and roles, the common sign-ins. Enterprise is scale and governance — the things an
 * organization asks for and a team of five never does. Each constant names one such thing, so a
 * gate reads {@code license.allows(Feature.X)} and the reason it refused is the feature's own
 * sentence, the same one the License panel and the site print.
 *
 * <p>A free installation is a single person on their own machine: none of this applies, and
 * nothing here purges or limits what is on somebody's own disk. The caps below bite only where
 * a Team license is active — a shared deployment with more than one person in it.
 */
public enum Feature {

    /** Any OpenID Connect provider (Okta, Entra custom, Keycloak). Team: the Google and Microsoft presets. */
    GENERIC_OIDC("Custom identity providers (any OpenID Connect issuer)"),
    /** Accounts created on first sign-in for an allowed email domain. Team: invitations only. */
    DOMAIN_JIT("Automatic accounts for an email domain"),
    /** Traces and metrics sent to a collector of yours. */
    OTEL_EXPORT("OpenTelemetry export to your collector"),
    /** Organization-wide rules: mandatory facades, a permission ceiling, a global budget, publishing approval. */
    ORG_POLICIES("Organization policies"),
    /** The audit trail exported as a file, and kept without limit. */
    AUDIT_EXPORT("Audit trail export"),
    /** Runs and flow versions kept forever. Team: ninety days. */
    UNLIMITED_RETENTION("Unlimited retention of runs and versions"),
    /** More than one organization on one deployment. */
    MULTI_ORG("Several organizations on one deployment"),
    /** Tokens for machines, beyond the two a Team gets. */
    SERVICE_ACCOUNTS("Unlimited service accounts"),
    /** Published endpoints without the per-token rate limit. */
    UNLIMITED_ENDPOINT_RATE("Published endpoints without a rate limit");

    /** How the feature is named to a person — on the panel, in a refusal, on the site. */
    public final String label;

    Feature(String label) {
        this.label = label;
    }

    /** Days of runs and flow versions a Team deployment keeps. Enterprise keeps everything. */
    public static final int TEAM_RETENTION_DAYS = 90;
    /** Service-account tokens a Team deployment may hold at once. */
    public static final int TEAM_SERVICE_ACCOUNTS = 2;
    /** Requests per minute one published endpoint token gets on Team. */
    public static final int TEAM_ENDPOINT_RATE_PER_MINUTE = 60;
}
