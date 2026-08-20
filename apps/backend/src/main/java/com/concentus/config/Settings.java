package com.concentus.config;

import com.concentus.auth.OrgContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * What a setting is, right now, for the organization this request belongs to.
 *
 * <p>Three places, in order: what somebody set in the application, then what the deployment was
 * configured with, then the built-in default. The order is what makes both audiences work — a
 * container started by a pipeline still takes its environment, and the person running the desktop
 * app can change the same thing from a form without being told to edit an environment they have no
 * access to.
 *
 * <p>Ask for values <em>where they are used</em>, not once into a field at construction. A setting
 * read at startup cannot be changed without a restart no matter where it is kept, and this class
 * exists to stop that being the only option.
 */
@Component
public class Settings {

    private final SettingsStore store;
    private final Environment environment;
    private final OrgContext orgContext;

    public Settings(SettingsStore store, Environment environment, OrgContext orgContext) {
        this.store = store;
        this.environment = environment;
        this.orgContext = orgContext;
    }

    private Settings() {
        this(null, null, null);
    }

    /**
     * A Settings that knows nothing, so every lookup is its caller's fallback.
     *
     * <p>For constructing a service directly — in a test, or from the compatibility constructors
     * that let existing call sites keep their shape. The alternative was threading a real store
     * into every one of them, which would have made this change touch a hundred lines that have
     * nothing to say about settings.
     */
    public static Settings none() {
        return new Settings();
    }

    /**
     * A Settings with these values and nothing else — for a test that is about what a limit does.
     *
     * <p>Values as strings, as they arrive from a form or an environment, so a test exercises the
     * same parsing the real thing does rather than a typed shortcut around it.
     */
    public static Settings of(java.util.Map<String, String> values) {
        return new Settings(null, null, null) {
            @Override
            public Optional<String> get(String key) {
                String value = values.get(key);
                return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        };
    }

    /**
     * The organization to read settings for.
     *
     * <p>The current request's, when there is one; otherwise the default. Background work — a
     * scheduled run, a mail poller — has no principal and still has to be able to read a limit,
     * and on every deployment but a multi-tenant one those are the same organization anyway.
     */
    private String organizationId() {
        return orgContext.currentUser()
                .map(com.concentus.auth.ConcentusUserDetails::organizationId)
                .orElseGet(orgContext::defaultOrganizationId);
    }

    public String get(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public Optional<String> get(String key) {
        if (store == null) return Optional.empty();
        Optional<String> stored = store.get(organizationId(), key);
        if (stored.isPresent()) return stored;
        String configured = environment.getProperty(key);
        return configured == null || configured.isBlank() ? Optional.empty() : Optional.of(configured);
    }

    /**
     * For a caller that already knows which organization it is acting for.
     *
     * <p>Named apart from {@link #get(String)} rather than overloaded: a two-argument {@code get}
     * reading as either "key with a fallback" or "organization and key" is the kind of ambiguity
     * that compiles and then quietly returns the wrong thing.
     */
    public Optional<String> forOrganization(String organizationId, String key) {
        if (store == null) return Optional.empty();
        Optional<String> stored = store.get(organizationId, key);
        if (stored.isPresent()) return stored;
        String configured = environment.getProperty(key);
        return configured == null || configured.isBlank() ? Optional.empty() : Optional.of(configured);
    }

    /** A whole number, or the fallback when it is unset or not one. */
    public int number(String key, int fallback) {
        try {
            return get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            // A setting that cannot be parsed must not take the application down at the moment it
            // is read, which may be inside a run somebody is watching.
            return fallback;
        }
    }

    public long number(String key, long fallback) {
        try {
            return get(key).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double decimal(String key, double fallback) {
        try {
            return get(key).map(Double::parseDouble).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A flag. Anything but "true" — case aside — reads as false, as Spring's own binding does. */
    public boolean flag(String key, boolean fallback) {
        return get(key).map(v -> Boolean.parseBoolean(v.trim().toLowerCase(Locale.ROOT)))
                .orElse(fallback);
    }
}
