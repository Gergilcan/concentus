package com.concentus.config;

import com.concentus.auth.OrgContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
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

    /**
     * Annotated, and the only other constructor is gone.
     *
     * <p>There used to be a private no-arg one behind {@link #none()}. With two constructors and
     * neither annotated, Spring falls back to the no-arg one — so the bean it built had a null
     * store and a null organization context, every lookup returned its caller's fallback, and the
     * whole settings feature was inert without a single error anywhere. It is the third time today
     * that two constructors on one bean have cost an afternoon.
     */
    @Autowired
    public Settings(SettingsStore store, Environment environment, OrgContext orgContext) {
        this.store = store;
        this.environment = environment;
        this.orgContext = orgContext;
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
        return new Settings(null, null, null);
    }

    /**
     * A Settings with these values and nothing else — for a test that is about what a limit does.
     *
     * <p>Values as strings, as they arrive from a form or an environment, so a test exercises the
     * same parsing the real thing does rather than a typed shortcut around it.
     */
    public static Settings of(Map<String, String> values) {
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
        return orgContext.currentOrganizationId();
    }

    public String get(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public Optional<String> get(String key) {
        if (store == null) return Optional.empty();
        return forOrganization(organizationId(), key);
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

    /**
     * What a setting is for one group of an organization: the group's own override, else the
     * organization's, else the deployment's. A null or blank {@code groupId} is the organization
     * itself — the answer for a flow that belongs to no group.
     *
     * <p>A stubbed Settings (one built by {@link #of}) has no store and answers every scope with
     * the one value it knows, which is what a test that is about a limit, not about where it came
     * from, wants.
     */
    public Optional<String> forGroup(String organizationId, String groupId, String key) {
        if (store == null) return get(key);
        if (groupId != null && !groupId.isBlank()) {
            Optional<String> own = store.groupSetting(groupId, key);
            if (own.isPresent()) return own;
        }
        return forOrganization(organizationId, key);
    }

    /**
     * The settings as one run sees them: the organization the run was launched in, and the group
     * its flow belongs to. For the readers that run per run — a worker's timeout, the permission
     * mode, the allowance meter — which are exactly the keys the catalogue marks
     * {@link SettingDef#groupScoped()}.
     */
    public Scope forRun(String organizationId, String groupId) {
        return new Scope(organizationId, groupId);
    }

    /** As {@link #forRun(String, String)}, read off the run itself. */
    public Scope forRun(com.concentus.service.AgentRun run) {
        return forRun(run.organizationId, run.groupId);
    }

    /** {@link Settings}, resolved for one organization and one group. Same parsing, same fallbacks. */
    public final class Scope {

        private final String organizationId;
        private final String groupId;

        private Scope(String organizationId, String groupId) {
            this.organizationId = organizationId;
            this.groupId = groupId;
        }

        public Optional<String> get(String key) {
            return forGroup(organizationId, groupId, key);
        }

        public String get(String key, String fallback) {
            return get(key).orElse(fallback);
        }

        public int number(String key, int fallback) {
            try {
                return get(key).map(Integer::parseInt).orElse(fallback);
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

        public boolean flag(String key, boolean fallback) {
            return get(key).map(v -> Boolean.parseBoolean(v.trim().toLowerCase(Locale.ROOT)))
                    .orElse(fallback);
        }
    }

    /**
     * A setting that belongs to the installation rather than to one of its tenants.
     *
     * <p>For the handful that have to be readable before anybody has said who they are — the
     * sign-in providers above all. Those are asked for by a screen with no session, so resolving
     * them against "the current organization" resolves them against the default one on the way in
     * and against the admin's own organization on the way out, and a registration saved by one is
     * invisible to the other. Both ends use this, so both mean the same rows.
     */
    public String installationWide(String key, String fallback) {
        if (store == null || orgContext == null) return fallback;
        return forOrganization(orgContext.defaultOrganizationId(), key).orElse(fallback);
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
