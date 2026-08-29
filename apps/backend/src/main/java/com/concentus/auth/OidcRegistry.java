package com.concentus.auth;

import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every identity provider this deployment will sign people in with.
 *
 * <p>A list rather than one, because "which provider" is not a question a company answers once.
 * The people who run the flows are on the corporate directory; the contractor auditing them has a
 * Google account; the person who set the thing up wants a password. Making that a single choice
 * meant every account outside it needed a password issued by hand, which is the arrangement
 * passwords-plus-SSO exists to avoid.
 *
 * <p>Configured by naming the ones in use and then giving each its credentials:
 *
 * <pre>
 * app.auth.oidc.providers=microsoft,google
 * app.auth.oidc.microsoft.client-id=…
 * app.auth.oidc.microsoft.client-secret=…
 * app.auth.oidc.microsoft.tenant=…
 * app.auth.oidc.google.client-id=…
 * app.auth.oidc.google.client-secret=…
 * </pre>
 *
 * <p>Read through {@link com.concentus.config.Settings} rather than bound to a properties class:
 * the keys are chosen by whoever writes the configuration, and a fixed record cannot have a field
 * for a provider nobody has thought of yet. Each entry may also state its own endpoints and
 * claims, so a provider that is OAuth2 rather than OpenID Connect is configuration and not new
 * code.
 *
 * <p><b>Resolved on every read, not held from startup.</b> Registering an application with a
 * directory and pasting its client id is something somebody does once, in the app, and then wants
 * to try — and a value cached at startup would mean the button they just configured is not there
 * until they restart. That is also why the settings behind this are the only ones in the catalogue
 * that do not ask for one.
 *
 * <p>The single-provider keys that came before this still work unchanged. A deployment that set
 * {@code app.auth.oidc.enabled=true} with a client id and secret keeps exactly the provider it
 * had — an upgrade must not sign a company out of its own application.
 *
 * <p><b>On a Team license, the presets only.</b> Google, Microsoft and Discord are what a team of
 * up to ten signs in with; an OpenID Connect issuer of the company's own (Okta, a custom Entra
 * app, Keycloak) is {@link Feature#GENERIC_OIDC}, an Enterprise feature. A Team deployment that
 * has one configured — from before the license changed, or from the environment — keeps the rows
 * but is not offered it: {@link #all()} leaves it out, so the sign-in screen has no button for it
 * and a callback for it finds no provider, while the providers panel still lists it, marked
 * inactive, so nobody wonders where their registration went. Enterprise and free are unchanged;
 * free has no way to register a provider at all.
 */
@Component
public class OidcRegistry {

    private static final Logger log = LoggerFactory.getLogger(OidcRegistry.class);

    /**
     * The ways in this application knows how to speak to, in the order somebody wants them.
     *
     * <p>Offered on the sign-in screen whether or not they are registered — an unregistered one
     * explains itself rather than failing — so this list is what somebody is told is possible, not
     * only what is switched on. "generic" is left out: it is a shape, not a provider, and nothing
     * sensible goes on a button until somebody has given it a name.
     */
    public static final List<String> PRESETS = List.of("microsoft", "google", "discord");

    /** One configured provider: where it is, and what this deployment signs in to it as. */
    public record Configured(OidcProvider provider, String clientId, String clientSecret,
                             String defaultRole) {

        public String id() {
            return provider.id();
        }

        public String displayName() {
            return provider.displayName();
        }

        /**
         * A provider missing its credentials is a button that fails, which is worse than one that
         * is absent — so it is dropped here rather than shown and discovered at the redirect.
         */
        boolean isUsable() {
            return provider.isUsable() && !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    private static final String LICENSE_URL = "https://www.concentus-ai.com/#license";

    private final com.concentus.config.Settings settings;
    private final LicenseService licenseService;

    public OidcRegistry(com.concentus.config.Settings settings, LicenseService licenseService) {
        this.settings = settings;
        this.licenseService = licenseService;
    }

    /**
     * Refuses to add a NEW sign-in provider without an enterprise license. Called by
     * {@code SignInProviderController} right before it writes anything — reading through {@link
     * #all()} or {@link #byId} is unaffected either way, so a provider registered before a license
     * lapsed (or was never bought) keeps signing people in.
     */
    public void requireEnterpriseToRegister() {
        if (!licenseService.enterpriseActive()) {
            throw new IllegalStateException("SSO providers are an enterprise feature. Get an "
                    + "enterprise license at " + LICENSE_URL + " to register one.");
        }
    }

    /**
     * Refuses to write a registration for a provider this license does not cover — a custom
     * issuer on a Team deployment. Called by {@code SignInProviderController} after {@link
     * #requireEnterpriseToRegister()}, so the message a Team admin reads names the feature and
     * the tier that has it, not the generic "SSO is enterprise" a free install gets.
     */
    public void requireAllowedToRegister(String presetId) {
        String refusal = refusalFor(OidcProvider.of(presetId, null, null, null, null));
        if (refusal != null) throw new IllegalStateException(refusal);
    }

    /**
     * Why this provider is not offered under the current license — {@link Feature#GENERIC_OIDC}'s
     * refusal on a Team deployment for anything but a preset — or null when it is (or would be)
     * offered. Read by the providers panel to mark a registration inactive, and by {@link #accept}
     * to leave it out of what the sign-in screen sees; one answer for both, so the panel never
     * calls inactive something the screen still offers, or the other way round.
     */
    public String refusalFor(OidcProvider provider) {
        if (!licenseService.withheld(Feature.GENERIC_OIDC)) return null;
        return isPreset(provider) ? null : licenseService.refusal(Feature.GENERIC_OIDC);
    }

    /**
     * Whether the provider is one of the presets AS SHIPPED, not merely named after one.
     *
     * <p>The id alone is not enough: every endpoint and claim can be restated in configuration,
     * so a "google" whose token endpoint points at a company's own Keycloak is a custom issuer
     * wearing a preset's name. The issuer is compared by host rather than verbatim because
     * Microsoft's carries the tenant, which a preset legitimately changes.
     */
    private static boolean isPreset(OidcProvider provider) {
        if (!PRESETS.contains(provider.id())) return false;
        OidcProvider shipped = OidcProvider.of(provider.id(), null, null, null, null);
        return Objects.equals(host(shipped.issuer()), host(provider.issuer()))
                && Objects.equals(shipped.authorizationUrl(), provider.authorizationUrl())
                && Objects.equals(shipped.tokenUrl(), provider.tokenUrl())
                && Objects.equals(shipped.userinfoUrl(), provider.userinfoUrl())
                && Objects.equals(shipped.subjectClaim(), provider.subjectClaim())
                && Objects.equals(shipped.emailClaim(), provider.emailClaim());
    }

    private static String host(String issuer) {
        if (issuer == null || issuer.isBlank()) return "";
        try {
            String host = URI.create(issuer).getHost();
            return host == null ? issuer : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            // Not a URL at all: compared as written, which can only ever fail to match a preset's.
            return issuer;
        }
    }

    /** Everything configured right now. Rebuilt per call — see the note about restarts above. */
    private Map<String, Configured> current() {
        Map<String, Configured> byId = new LinkedHashMap<>();
        for (String key : namedProviders()) {
            Configured configured = read(key, "app.auth.oidc." + key + ".");
            if (accept(configured, key)) byId.put(configured.id(), configured);
        }
        if (byId.isEmpty() && Boolean.parseBoolean(value("app.auth.oidc.enabled", "false"))) {
            // The shape this application had before it could hold more than one. Kept working, not
            // migrated: an upgrade that quietly switched off a company's sign-in would lock out
            // everyone whose account has no password.
            String preset = value("app.auth.oidc.provider", "microsoft");
            Configured legacy = read(preset, "app.auth.oidc.");
            if (accept(legacy, preset)) byId.put(legacy.id(), legacy);
        }
        return byId;
    }

    private String value(String key, String fallback) {
        // Installation-wide, not per-organization: the screen that needs these has nobody signed
        // in, so there is no organization to scope them by. Scoping them anyway meant a
        // registration saved by an admin was written under their organization and read back under
        // the default one — stored correctly, and invisible.
        return settings.installationWide(key, fallback);
    }

    private boolean accept(Configured configured, String key) {
        if (!configured.isUsable()) {
            // At debug, not warn: this is read on every sign-in screen now, and a half-filled
            // provider somebody is in the middle of configuring would otherwise fill the log.
            log.debug("Not offering the '{}' sign-in provider: it needs a client id, a client secret, "
                    + "and either a known preset or an issuer.", key);
            return false;
        }
        String refusal = refusalFor(configured.provider());
        if (refusal != null) {
            log.debug("Not offering the '{}' sign-in provider: {}", key, refusal);
            return false;
        }
        return true;
    }

    private List<String> namedProviders() {
        String listed = value("app.auth.oidc.providers", "");
        List<String> names = new ArrayList<>();
        for (String part : listed.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private Configured read(String preset, String prefix) {
        OidcProvider provider = OidcProvider.of(
                preset,
                value(prefix + "issuer", null),
                value(prefix + "tenant", null),
                value(prefix + "display-name", null),
                value(prefix + "scope", null))
                .withEndpoints(
                        value(prefix + "authorization-url", null),
                        value(prefix + "token-url", null),
                        value(prefix + "userinfo-url", null))
                .withClaims(
                        value(prefix + "subject-claim", null),
                        value(prefix + "email-claim", null));
        return new Configured(provider,
                trimmed(value(prefix + "client-id", null)),
                trimmed(value(prefix + "client-secret", null)),
                // Falls back to the shared setting, so "everyone arrives as a Viewer" is said once
                // rather than repeated under every provider.
                value(prefix + "default-role", value("app.auth.oidc.default-role", "VIEWER")));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Every provider a person may sign in with, in the order they were configured. */
    public List<Configured> all() {
        return List.copyOf(current().values());
    }

    public Optional<Configured> byId(String id) {
        return Optional.ofNullable(id == null ? null
                : current().get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean any() {
        return !current().isEmpty();
    }
}
