package com.concentus.web;

import com.concentus.auth.OidcProvider;
import com.concentus.auth.OidcRegistry;
import com.concentus.auth.OidcSignIn;
import com.concentus.auth.OrgContext;
import com.concentus.config.SettingsStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registering the directories people sign in with, from inside the application.
 *
 * <p>Until this existed, adding "Continue with Microsoft" meant putting a client id and a secret
 * into the process's environment and restarting it — which on a desktop install is not a thing
 * anybody can do, and on a server is a deploy for a value somebody pasted out of a browser tab.
 * The result was a feature that worked and that nobody could turn on.
 *
 * <p>What this needs to be usable is not a form: it is the <b>redirect URI</b>. Every registration
 * fails the same way the first time — the address in the directory does not match the one the
 * application asks for — so the address is computed from the request and handed over to be copied,
 * rather than described in documentation somebody would have to find.
 */
@RestController
@RequestMapping("/api/account/providers")
public class SignInProviderController {

    /** The presets, in the order somebody is likely to want them. */
    private static final List<String> PRESETS = List.of("microsoft", "google", "discord", "generic");

    private final SettingsStore store;
    private final OrgContext orgContext;
    private final OidcSignIn signIn;
    private final OidcRegistry registry;

    public SignInProviderController(SettingsStore store, OrgContext orgContext, OidcSignIn signIn,
                                    OidcRegistry registry) {
        this.store = store;
        this.orgContext = orgContext;
        this.signIn = signIn;
        this.registry = registry;
    }

    /**
     * @param clientSecret blank means "leave the stored one alone", so a form can be re-saved
     *                     without the secret ever being sent back to the browser first
     */
    public record ProviderUpdate(String id, boolean enabled, String clientId, String clientSecret,
                                 String tenant, String issuer, String displayName) {
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        orgContext.requireAdmin();
        // The installation's own organization, not the caller's: a sign-in provider is a property
        // of the installation, and the screen that offers it has nobody signed in to scope it by.
        String organizationId = orgContext.defaultOrganizationId();
        List<String> enabled = enabledIds(organizationId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : PRESETS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("name", OidcProvider.of(id, null, null, null, null).displayName());
            entry.put("enabled", enabled.contains(id));
            entry.put("clientId", setting(organizationId, id, "client-id"));
            // Never read back — what the screen needs is whether there is one, so it can show a
            // filled field without the value being reachable through this API.
            entry.put("hasSecret", !setting(organizationId, id, "client-secret").isBlank());
            entry.put("tenant", setting(organizationId, id, "tenant"));
            entry.put("issuer", setting(organizationId, id, "issuer"));
            entry.put("displayName", setting(organizationId, id, "display-name"));
            // Only Microsoft restricts by directory, and only "generic" needs an issuer. Said here
            // so the form asks for what this provider actually wants rather than for everything.
            entry.put("wantsTenant", "microsoft".equals(id));
            entry.put("wantsIssuer", "generic".equals(id));
            out.add(entry);
        }
        return Map.of(
                "providers", out,
                // The one value every registration needs and every first attempt gets wrong.
                "redirectUri", baseOf(request) + OidcSignIn.CALLBACK_PATH,
                "live", signIn.providers().stream()
                        .map(p -> Map.of("id", p.id(), "name", p.displayName())).toList());
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody ProviderUpdate body, HttpServletRequest request) {
        orgContext.requireAdmin();
        // A provider already signing people in keeps doing so however the license situation
        // changes — that's OidcSignIn reading straight from settings. This is the one place a NEW
        // registration is written, so it's the one place that needs the enterprise check.
        registry.requireEnterpriseToRegister();
        String organizationId = orgContext.defaultOrganizationId();
        String id = body.id() == null ? "" : body.id().trim().toLowerCase(Locale.ROOT);
        if (!PRESETS.contains(id)) {
            throw new IllegalArgumentException("Unknown sign-in provider '" + body.id() + "'.");
        }
        String who = orgContext.currentUser()
                .map(com.concentus.auth.ConcentusUserDetails::email).orElse(null);

        put(organizationId, id, "client-id", body.clientId(), false, who);
        // Blank leaves the stored secret alone. The alternative — treating an untouched field as
        // "clear it" — would silently break a working registration every time somebody edited the
        // tenant beside it.
        if (body.clientSecret() != null && !body.clientSecret().isBlank()) {
            put(organizationId, id, "client-secret", body.clientSecret(), true, who);
        }
        put(organizationId, id, "tenant", body.tenant(), false, who);
        put(organizationId, id, "issuer", body.issuer(), false, who);
        put(organizationId, id, "display-name", body.displayName(), false, who);

        List<String> enabled = new ArrayList<>(enabledIds(organizationId));
        enabled.remove(id);
        if (body.enabled()) enabled.add(id);
        store.put(organizationId, "app.auth.oidc.providers", String.join(",", enabled), false, who);

        return list(request);
    }

    private List<String> enabledIds(String organizationId) {
        String listed = store.get(organizationId, "app.auth.oidc.providers").orElse("");
        List<String> ids = new ArrayList<>();
        for (String part : listed.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !ids.contains(name)) ids.add(name);
        }
        return ids;
    }

    private String setting(String organizationId, String id, String key) {
        return store.get(organizationId, "app.auth.oidc." + id + "." + key).orElse("");
    }

    private void put(String organizationId, String id, String key, String value, boolean secret,
                     String who) {
        store.put(organizationId, "app.auth.oidc." + id + "." + key, value, secret, who);
    }

    /**
     * The origin this request arrived on, which is what a registration's redirect URI has to match.
     *
     * <p>Read from the request rather than configured, so the same build gives the right address on
     * localhost, on a LAN address and behind a domain. Forwarded headers are honoured because a
     * reverse proxy is the normal shape of a deployment that has a domain at all.
     */
    private static String baseOf(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto", request.getScheme());
        String host = header(request, "X-Forwarded-Host", request.getServerName());
        String port = header(request, "X-Forwarded-Port", String.valueOf(request.getServerPort()));
        boolean defaultPort = ("https".equals(proto) && "443".equals(port))
                || ("http".equals(proto) && "80".equals(port));
        boolean hostHasPort = host.contains(":");
        return proto + "://" + host + (defaultPort || hostHasPort ? "" : ":" + port);
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) return fallback;
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }
}
