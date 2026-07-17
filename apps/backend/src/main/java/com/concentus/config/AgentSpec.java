package com.concentus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

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
    /** When (and for what) the coordinator should delegate to this agent — its routing signal. */
    public String description = "";
    public String systemPrompt = "";

    public ModelSpec model = new ModelSpec();
    public List<SkillSpec> skills = new ArrayList<>();
    public List<McpServerSpec> mcpServers = new ArrayList<>();
    public List<RepoSpec> repositories = new ArrayList<>();
    public List<SqlSourceSpec> ragSources = new ArrayList<>();
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
        public String name;
        public String url;
        /** Name of an env var holding a bearer token (optional). */
        public String tokenEnv;

        /** Resolves the token from the environment, or null if none configured/set. */
        public String resolveToken() {
            return (tokenEnv == null || tokenEnv.isBlank()) ? null : emptyToNull(System.getenv(tokenEnv));
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

        public String resolveToken() {
            return (tokenEnv == null || tokenEnv.isBlank()) ? null : emptyToNull(System.getenv(tokenEnv));
        }
    }

    /** A generic JDBC-backed RAG source: run a SQL query and inject the rows into agent context. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SqlSourceSpec {
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

        public String resolvePassword() {
            return (passwordEnv == null || passwordEnv.isBlank()) ? null : emptyToNull(System.getenv(passwordEnv));
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
