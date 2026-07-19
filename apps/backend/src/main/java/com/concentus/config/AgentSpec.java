package com.concentus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Root of the YAML agent specification. Sub-specs are nested static classes so the
 * whole schema lives in one file. Populated by Jackson (public fields), then
 * {@link #validate()} enforces required fields and normalizes defaults.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSpec {

    /** managed | local */
    public String mode = "managed";
    public String name = "agent";
    /** Canvas node id this spec came from (for per-node execution reporting). */
    public String nodeId;
    /**
     * Name this agent is registered under with the CLI: sanitized and made unique across the flow.
     * Two canvas nodes may legitimately share a display name ("Code Reviewer" for backend and for
     * frontend); they must not share this, or they would overwrite each other's definition file
     * and become indistinguishable in the logs.
     */
    public String cliName;
    /**
     * The agents this one may hand work to, by {@link #cliName} — its directly linked downstream
     * agents. Lets a sub-agent run its own review step on just its own output, instead of every
     * agent being a peer under the coordinator.
     */
    public List<String> delegatesTo = new ArrayList<>();
    /** When (and for what) the coordinator should delegate to this agent — its routing signal. */
    public String description = "";
    public String systemPrompt = "";

    public ModelSpec model = new ModelSpec();
    public List<SkillSpec> skills = new ArrayList<>();
    public List<McpServerSpec> mcpServers = new ArrayList<>();
    public List<RepoSpec> repositories = new ArrayList<>();
    public List<SqlSourceSpec> ragSources = new ArrayList<>();
    /**
     * Local host folders this agent should read as context, passed to the CLI as {@code --add-dir}.
     * Without these an agent only sees its scratch workspace and has to guess from names — which
     * is how a "WireJ" agent ends up assuming some other checkout is the WireJ one.
     */
    public List<String> contextFolders = new ArrayList<>();
    /** Path to an existing CLAUDE.md (or a folder containing one) to load as project context. */
    public String claudeMdPath = "";
    public CacheSpec cache = new CacheSpec();
    public ContextSpec context = new ContextSpec();
    public EnvironmentSpec environment = new EnvironmentSpec();

    public RunMode runMode() {
        return RunMode.from(mode);
    }

    /** Fail fast on an unusable spec before we touch the API. */
    public void validate() {
        RunMode.from(mode); // throws on unknown mode
        require(name != null && !name.isBlank(), "`name` is required");
        require(model != null && model.id != null && !model.id.isBlank(), "`model.id` is required");
        require(model.maxTokens > 0, "`model.maxTokens` must be > 0");

        for (McpServerSpec s : mcpServers) {
            require(s.name != null && !s.name.isBlank(), "each mcpServer needs a `name`");
            require(s.url != null && !s.url.isBlank(), "mcpServer '" + s.name + "' needs a `url`");
        }
        for (RepoSpec r : repositories) {
            require(r.url != null && !r.url.isBlank(), "each repository needs a `url`");
            RepoProvider.from(r.provider); // throws on unknown provider
        }
        for (SqlSourceSpec q : ragSources) {
            require(q.jdbcUrl != null && !q.jdbcUrl.isBlank(), "each SQL source needs a `jdbcUrl`");
            require(q.query != null && !q.query.isBlank(), "SQL source '" + q.label() + "' needs a `query`");
        }
        cache.normalize();
        context.normalize();
        environment.normalize();
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new IllegalArgumentException("Invalid agent spec: " + message);
    }

    // ------------------------------------------------------- env-var allowlist (WIR-7)

    // AgentSpec instances are plain Jackson POJOs (deserialized from YAML/JSON, not
    // Spring-managed), so they can't take @Value config directly. EnvAllowlistConfig is a
    // tiny bean that wires the config-driven allowlist from application.properties into this
    // static holder at startup. Without this, any caller that can set `passwordEnv`/`tokenEnv`
    // (e.g. the unauthenticated RAG preview endpoint) could read an arbitrary server env var,
    // such as ANTHROPIC_API_KEY, by naming it.
    private static volatile Set<String> allowedEnvVars = Set.of();
    private static volatile Set<String> allowedEnvVarPrefixes = Set.of("WIREJ_DB_");

    @Component
    static class EnvAllowlistConfig {
        EnvAllowlistConfig(
                @Value("${rag.allowed-env-vars:}") String allowedEnvVarsCsv,
                @Value("${rag.allowed-env-var-prefixes:WIREJ_DB_}") String allowedEnvVarPrefixesCsv) {
            allowedEnvVars = toSet(allowedEnvVarsCsv);
            allowedEnvVarPrefixes = toSet(allowedEnvVarPrefixesCsv);
        }

        private static Set<String> toSet(String csv) {
            if (csv == null || csv.isBlank()) return Set.of();
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /** True if {@code name} may be resolved via {@link System#getenv(String)} by a RAG/SQL spec. */
    static boolean isEnvVarAllowed(String name) {
        if (name == null || name.isBlank()) return false;
        if (allowedEnvVars.contains(name)) return true;
        for (String prefix : allowedEnvVarPrefixes) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Shared by {@link McpServerSpec#resolveToken()}, {@link RepoSpec#resolveToken()}, and {@link
     * SqlSourceSpec#resolvePassword()}: resolves a named env var via {@code envLookup}, but only if
     * it's on the allowlist (see {@link #isEnvVarAllowed}).
     */
    private static String resolveAllowedEnvVar(String envVarName, Function<String, String> envLookup) {
        if (envVarName == null || envVarName.isBlank() || !isEnvVarAllowed(envVarName)) return null;
        return emptyToNull(envLookup.apply(envVarName));
    }

    // ---------------------------------------------------------------- enums

    public enum RunMode {
        MANAGED, LOCAL;

        static RunMode from(String raw) {
            if (raw == null) throw new IllegalArgumentException("`mode` is required (managed | local)");
            switch (raw.trim().toLowerCase()) {
                case "managed": return MANAGED;
                case "local": return LOCAL;
                default: throw new IllegalArgumentException("Unknown mode '" + raw + "' (expected managed | local)");
            }
        }
    }

    public enum RepoProvider {
        GITHUB, GITLAB;

        static RepoProvider from(String raw) {
            if (raw == null) throw new IllegalArgumentException("repository `provider` is required (github | gitlab)");
            switch (raw.trim().toLowerCase()) {
                case "github": return GITHUB;
                case "gitlab": return GITLAB;
                default: throw new IllegalArgumentException("Unknown repo provider '" + raw + "' (expected github | gitlab)");
            }
        }
    }

    // ------------------------------------------------------------- sub-specs

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelSpec {
        public String id = "claude-opus-4-8";
        public long maxTokens = 16000;
        /** low | medium | high | xhigh | max */
        public String effort = "high";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillSpec {
        /** anthropic | custom */
        public String type = "anthropic";
        public String id;
        public String version; // custom skills only
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServerSpec {
        /** Canvas node id this spec came from (for per-node execution reporting). */
        public String nodeId;
        public String name;
        public String url;
        /** Name of an env var holding a bearer token (optional). */
        public String tokenEnv;

        /**
         * Resolves the token from the environment, or null if none configured/set. Only names on
         * the config-driven allowlist ({@code rag.allowed-env-vars} / {@code
         * rag.allowed-env-var-prefixes}) are ever read — a non-allowlisted name silently yields no
         * value rather than the actual env var (e.g. {@code tokenEnv=ANTHROPIC_API_KEY} sent to an
         * attacker-controlled MCP {@code url} via the unauthenticated {@code /api/mcp/servers}
         * endpoint must not exfiltrate the real key).
         */
        public String resolveToken() {
            return resolveToken(System::getenv);
        }

        /** Same as {@link #resolveToken()}, but resolving env vars via {@code envLookup} (for tests). */
        public String resolveToken(Function<String, String> envLookup) {
            return resolveAllowedEnvVar(tokenEnv, envLookup);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoSpec {
        public String provider = "github";
        public String url;
        public String tokenEnv;
        public String mountPath;
        public String branch;

        public RepoProvider provider() {
            return RepoProvider.from(provider);
        }

        /** Same allowlist guard as {@link McpServerSpec#resolveToken()} — see its javadoc. */
        public String resolveToken() {
            return resolveToken(System::getenv);
        }

        /** Same as {@link #resolveToken()}, but resolving env vars via {@code envLookup} (for tests). */
        public String resolveToken(Function<String, String> envLookup) {
            return resolveAllowedEnvVar(tokenEnv, envLookup);
        }
    }

    /** A generic JDBC-backed RAG source: run a SQL query and inject the rows into agent context. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SqlSourceSpec {
        /** Canvas node id this spec came from (for per-node execution reporting). */
        public String nodeId;
        public String label;
        /** Any JDBC URL, e.g. jdbc:postgresql://host:5432/db (driver must be on the classpath). */
        public String jdbcUrl;
        public String username;
        /** Name of an env var holding the DB password (never store the password in the flow). */
        public String passwordEnv;
        public String query;
        public int maxRows = 50;

        public String label() {
            return (label == null || label.isBlank()) ? "sql" : label;
        }

        /**
         * Resolves the DB password from the environment. Only names on the config-driven
         * allowlist ({@code rag.allowed-env-vars} / {@code rag.allowed-env-var-prefixes}) are
         * ever read; a non-allowlisted name silently yields no value rather than the actual
         * env var. Callers should check {@link #hasDisallowedPasswordEnv()} before connecting
         * to reject with a clear error instead of silently authenticating with no password.
         */
        public String resolvePassword() {
            return resolvePassword(System::getenv);
        }

        /** Same as {@link #resolvePassword()}, but resolving env vars via {@code envLookup} (for tests). */
        public String resolvePassword(Function<String, String> envLookup) {
            return resolveAllowedEnvVar(passwordEnv, envLookup);
        }

        /** True if {@code passwordEnv} is set but not on the allowlist (used to reject with a clear 400). */
        public boolean hasDisallowedPasswordEnv() {
            return passwordEnv != null && !passwordEnv.isBlank() && !isEnvVarAllowed(passwordEnv);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CacheSpec {
        public boolean enabled = false;
        /** 5m | 1h */
        public String ttl = "5m";
        public int minTokens = 4096;
        /** system | tools-and-system */
        public String breakpoints = "system";

        void normalize() {
            if (ttl == null) ttl = "5m";
            ttl = ttl.trim().toLowerCase();
            if (!ttl.equals("5m") && !ttl.equals("1h")) {
                throw new IllegalArgumentException("cache.ttl must be '5m' or '1h' (got '" + ttl + "')");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextSpec {
        /** none | compaction | context-editing */
        public String strategy = "none";
        public long triggerTokens = 150000;
        public boolean clearToolResults = true;
        public boolean clearToolInputs = false;

        void normalize() {
            if (strategy == null) strategy = "none";
            strategy = strategy.trim().toLowerCase();
            if (!strategy.equals("none") && !strategy.equals("compaction") && !strategy.equals("context-editing")) {
                throw new IllegalArgumentException(
                        "context.strategy must be none | compaction | context-editing (got '" + strategy + "')");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnvironmentSpec {
        public String name = "agent-env";
        /** unrestricted | limited */
        public String networking = "unrestricted";

        void normalize() {
            if (name == null || name.isBlank()) name = "agent-env";
            if (networking == null) networking = "unrestricted";
            networking = networking.trim().toLowerCase();
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
