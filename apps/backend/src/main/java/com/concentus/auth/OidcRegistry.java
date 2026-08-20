package com.concentus.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>Read from the {@link Environment} rather than bound to a properties class: the keys are
 * chosen by whoever writes the configuration, and a fixed record cannot have a field for a
 * provider nobody has thought of yet. Each entry may also state its own endpoints and claims, so
 * a provider that is OAuth2 rather than OpenID Connect is configuration and not new code.
 *
 * <p>The single-provider keys that came before this still work unchanged. A deployment that set
 * {@code app.auth.oidc.enabled=true} with a client id and secret keeps exactly the provider it
 * had — an upgrade must not sign a company out of its own application.
 */
@Component
public class OidcRegistry {

    private static final Logger log = LoggerFactory.getLogger(OidcRegistry.class);

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

    private final Map<String, Configured> byId = new LinkedHashMap<>();

    public OidcRegistry(Environment env) {
        for (String key : namedProviders(env)) {
            Configured configured = read(env, key, "app.auth.oidc." + key + ".");
            if (accept(configured, key)) byId.put(configured.id(), configured);
        }
        if (byId.isEmpty() && env.getProperty("app.auth.oidc.enabled", Boolean.class, false)) {
            // The shape this application had before it could hold more than one. Kept working, not
            // migrated: an upgrade that quietly switched off a company's sign-in would lock out
            // everyone whose account has no password.
            String preset = env.getProperty("app.auth.oidc.provider", "microsoft");
            Configured legacy = read(env, preset, "app.auth.oidc.");
            if (accept(legacy, preset)) byId.put(legacy.id(), legacy);
        }
        if (!byId.isEmpty()) {
            log.info("Sign-in providers configured: {}", String.join(", ", byId.keySet()));
        }
    }

    private boolean accept(Configured configured, String key) {
        if (configured.isUsable()) return true;
        log.warn("Ignoring the '{}' sign-in provider: it needs a client id, a client secret, and "
                + "either a known preset or an issuer.", key);
        return false;
    }

    private static List<String> namedProviders(Environment env) {
        String listed = env.getProperty("app.auth.oidc.providers", "");
        List<String> names = new ArrayList<>();
        for (String part : listed.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private static Configured read(Environment env, String preset, String prefix) {
        OidcProvider provider = OidcProvider.of(
                preset,
                env.getProperty(prefix + "issuer"),
                env.getProperty(prefix + "tenant"),
                env.getProperty(prefix + "display-name"),
                env.getProperty(prefix + "scope"))
                .withEndpoints(
                        env.getProperty(prefix + "authorization-url"),
                        env.getProperty(prefix + "token-url"),
                        env.getProperty(prefix + "userinfo-url"))
                .withClaims(
                        env.getProperty(prefix + "subject-claim"),
                        env.getProperty(prefix + "email-claim"));
        return new Configured(provider,
                trimmed(env.getProperty(prefix + "client-id")),
                trimmed(env.getProperty(prefix + "client-secret")),
                // Falls back to the shared setting, so "everyone arrives as a Viewer" is said once
                // rather than repeated under every provider.
                env.getProperty(prefix + "default-role",
                        env.getProperty("app.auth.oidc.default-role", "VIEWER")));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Every provider a person may sign in with, in the order they were configured. */
    public List<Configured> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Configured> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean any() {
        return !byId.isEmpty();
    }
}
