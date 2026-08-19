package com.concentus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
    /**
     * How the coordinator distributes work: {@code ""} (Claude Code subagents inside one CLI
     * session — the default and the only behaviour that existed before this field) or
     * {@code "fanout"} (one independent {@code claude} process per sub-agent). Read from the
     * coordinator only; meaningless on sub-agents.
     *
     * <p>Normalized in {@link #validate()} so an unrecognized value falls back to the default
     * rather than enabling anything — a typo must never switch a flow onto the experimental path.
     */
    public String execution = "";
    /**
     * The facade profile this agent runs behind when it executes as an independent worker —
     * which MCP tools it may see, whether writes are blocked (read-only) or simulated (dry-run).
     * A worker with MCP servers wired but no profile selected gets <b>no</b> MCP tools at all:
     * "never the full tool set" is the rule, and an absent profile must not widen anything.
     */
    public String facadeProfileId = "";
    /**
     * What the fan-out coordinator's own process may do. Coordinator only, fan-out only — the
     * single-session path is governed by the flow's permission mode and gets no second knob.
     *
     * <p>{@code ""} (auto, the default): read-only exactly when the coordinator has sub-agents
     * wired to it — a coordinator with workers exists to distribute, not to touch things, while
     * a solo coordinator is the one doing the work and may act. {@code "read-only"} and
     * {@code "may-act"} force either shape from the node's config. Delegation (Task) is denied
     * in every case, or the fan-out stops being one level deep.
     *
     * <p>Normalized in {@link #validate()}: an unrecognized value means auto, so a typo can
     * only ever land on the computed default, never silently force a widening.
     */
    public String coordinatorAccess = "";

    public ModelSpec model = new ModelSpec();
    /**
     * The model to escalate to when the adversarial verifier rejects this agent's output. Empty
     * (the default) means no escalation: one attempt, on {@link #model}.
     *
     * <p>The cheap-first idea, made honest: the primary model does the work, and only an output a
     * verifier actually refused is worth paying the stronger model for. It applies to FAN-OUT
     * WORKERS only, because they are the one path with a verifier — an agent nobody judges has no
     * signal to escalate on, and re-running it "in case" would spend more, not less.
     */
    public String fallbackModelId = "";
    public List<SkillSpec> skills = new ArrayList<>();
    /**
     * Claude Code plugin ids ({@code name@marketplace}) this agent runs with — exactly these and
     * nothing else. The run's settings enable the selection and disable every other installed
     * plugin, so a flow behaves the same on any machine with the plugins installed; empty means
     * no plugins at all, not "whatever this machine happens to enable". Claude backend only.
     */
    public List<String> plugins = new ArrayList<>();
    public List<McpServerSpec> mcpServers = new ArrayList<>();
    public List<RepoSpec> repositories = new ArrayList<>();
    /**
     * Tools this agent may use, written into the subagent's frontmatter. Empty means "inherit
     * everything", which is the CLI's own default. This is the one per-agent permission Claude
     * Code actually enforces — unlike the permission mode, which is per process.
     */
    public List<String> tools = new ArrayList<>();
    public List<SqlSourceSpec> ragSources = new ArrayList<>();
    /** REST APIs this agent may call as typed tools, from OpenAPI specs on API nodes. */
    public List<ApiSourceSpec> apiSources = new ArrayList<>();
    /** Other flows this agent may run as a tool, from the flow nodes wired to it. */
    public List<SubflowSpec> subflows = new ArrayList<>();
    public List<KnowledgeSourceSpec> knowledgeSources = new ArrayList<>();
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
            require((s.url != null && !s.url.isBlank()) || s.isStdio(),
                    "mcpServer '" + s.name + "' needs a `url` or a `command`");
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
        // Only the one non-default value passes through; anything else means "subagents".
        execution = "fanout".equalsIgnoreCase(execution == null ? "" : execution.trim())
                ? "fanout" : "";
        String access = coordinatorAccess == null ? "" : coordinatorAccess.trim().toLowerCase();
        coordinatorAccess = switch (access) {
            case "read-only", "may-act" -> access;
            default -> "";
        };
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new IllegalArgumentException("Invalid agent spec: " + message);
    }

    // ------------------------------------------------------- credential resolution

    // AgentSpec instances are plain Jackson POJOs (deserialized from YAML/JSON, not
    // Spring-managed), so they can't be injected with the credential store. A tiny bean in the
    // secrets package installs the lookup here at startup, which is the same shape the previous
    // env-var allowlist used.
    //
    // The allowlist this replaced existed because a node named an arbitrary environment variable:
    // without a guard, `tokenEnv=ANTHROPIC_API_KEY` on a node pointed at an attacker-controlled
    // MCP url would have exfiltrated the real key. A credential id has no such reach — it either
    // names a row in this deployment's credential table or it resolves to nothing — so the whole
    // allowlist mechanism is gone rather than carried forward.
    private static volatile Function<String, String> credentialLookup = id -> null;

    /** Installs the resolver. Called once at startup; see {@code secrets.CredentialResolver}. */
    public static void setCredentialLookup(Function<String, String> lookup) {
        credentialLookup = lookup == null ? id -> null : lookup;
    }

    /**
     * Resolves a stored credential outside a spec, for the designer.
     *
     * <p>The tool picker has to authenticate exactly as a run does, or it shows a list the run
     * cannot reproduce — which is worse than showing nothing.
     */
    public static String resolveCredentialForLookup(String credentialId) {
        return resolveCredential(credentialId, credentialLookup);
    }

    /**
     * Shared by {@link McpServerSpec#resolveToken()}, {@link RepoSpec#resolveToken()} and {@link
     * SqlSourceSpec#resolvePassword()}: decrypts the credential a node references.
     *
     * <p>A missing or unknown id yields null rather than throwing, so a node pointing at a deleted
     * credential behaves like one with none configured — the caller reports "no credential" rather
     * than failing in a way that looks like a bug.
     */
    private static String resolveCredential(String credentialId, Function<String, String> lookup) {
        if (credentialId == null || credentialId.isBlank()) return null;
        return emptyToNull(lookup.apply(credentialId));
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

    /**
     * Another flow, run from this one.
     *
     * <p>The same shape serves both drawings, because they differ only in who decides. Wired to an
     * agent it is a tool the agent may call; left terminal it fires when the run finishes. What
     * both need is which flow and whether to wait for its answer.
     *
     * <p>Note what is deliberately absent: a permission mode. The child runs under its own, so a
     * parent in plan mode cannot act through a child that has bypass — the loophole a well-meaning
     * "inherit the parent's" would open.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubflowSpec {
        public String nodeId;
        public String label = "flow";
        /** Id of the flow to run. Never blank — the compiler refuses a node without one. */
        public String flowId = "";
        /**
         * Whether the caller waits for the child's answer.
         *
         * <p>True by default: the common case is "summarise this", "check that", where the answer
         * IS the reason for the call. False turns it into a hand-off for work nobody is waiting on.
         */
        public boolean waitForResult = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiSourceSpec {
        public String nodeId;
        public String label = "api";
        public String specUrl = "";
        /** A pasted spec, for APIs whose document is not fetchable from the app. */
        public String specInline = "";
        /** Overrides the spec's own servers[0].url when set. */
        public String baseUrl = "";
        public String credentialId = "";
        /** Header the credential goes in; blank = Authorization: Bearer. */
        public String authHeader = "";
        /** Operation keys ("GET /pets") the agent may call. Empty = none — allow is explicit. */
        public List<String> ops = new ArrayList<>();

        /**
         * {@code spec} reads an OpenAPI document; {@code endpoint} is the four fields below and no
         * document at all. Blank means spec, so every node saved before this existed is unchanged.
         */
        public String mode = "";
        /** endpoint mode: the URL to call, {placeholders} included. */
        public String url = "";
        /** endpoint mode: GET, POST, PUT, PATCH or DELETE. */
        public String method = "GET";
        /**
         * endpoint mode: what this endpoint does, in the agent's words. It is the only description
         * the model gets — with no specification there is nothing else to read.
         */
        public String description = "";
        /** endpoint mode: whether the agent may send a JSON body. */
        public boolean sendsBody;

        public boolean isEndpoint() {
            return "endpoint".equalsIgnoreCase(mode);
        }

        public String resolveToken() {
            return resolveCredential(credentialId, credentialLookup);
        }
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
        /** Id of a stored credential holding the token (optional). */
        public String credentialId;
        /**
         * Header the credential is sent in. Defaults to {@code Authorization} with a {@code Bearer }
         * prefix, which is what most MCP servers expect — but GitLab reads its access tokens from
         * {@code PRIVATE-TOKEN}, unprefixed, so this has to be per-server rather than fixed.
         */
        public String authHeader;

        /**
         * Substrings that select which of the server's tools an agent is given. Empty means all.
         *
         * <p>Exists because a real MCP server can be enormous — Holded's exposes 338 tools — and
         * every one of them is a JSON schema in the prompt. That is tens of thousands of tokens
         * before the conversation starts, which a self-hosted model's context simply will not hold;
         * the server then truncates silently and the model reports having only whatever survived.
         * Choosing 20 relevant tools is also what makes a smaller model pick the right one.
         *
         * <p>Matched as case-insensitive substrings, not exact names, so {@code contact} covers
         * {@code create_contact} and {@code list_contacts} without anyone transcribing a list.
         */
        public List<String> tools = new ArrayList<>();

        /**
         * The stdio transport: a local process speaking MCP over its stdin/stdout — the
         * npx/python invocation a server's README says to put in {@code mcp.json}. A spec with a
         * command has no URL; the CLI launches the process itself, per run.
         */
        public String command;
        public List<String> args = new ArrayList<>();
        /**
         * Environment for the launched process. A VALUE of the form {@code credential:<id>} is
         * resolved from the credential store when the run config is written — that is how a
         * developer token reaches a stdio server without ever being stored in a flow.
         */
        public Map<String, String> env = new LinkedHashMap<>();

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isStdio() {
            return command != null && !command.isBlank();
        }

        /** The decrypted bearer token, or null when none is configured or the id is unknown. */
        public String resolveToken() {
            return resolveToken(credentialLookup);
        }

        /** Same as {@link #resolveToken()}, resolving through {@code lookup} (for tests). */
        public String resolveToken(Function<String, String> lookup) {
            return resolveCredential(credentialId, lookup);
        }

        /** {@link #env} with {@code credential:<id>} values decrypted; typed values pass through. */
        public Map<String, String> resolveEnv() {
            return resolveEnv(credentialLookup);
        }

        /** Same as {@link #resolveEnv()}, resolving through {@code lookup} (for tests). */
        public Map<String, String> resolveEnv(Function<String, String> lookup) {
            Map<String, String> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : env.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                if (value.startsWith("credential:")) {
                    String secret = resolveCredential(value.substring("credential:".length()), lookup);
                    resolved.put(e.getKey(), secret == null ? "" : secret);
                } else {
                    resolved.put(e.getKey(), value);
                }
            }
            return resolved;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoSpec {
        public String provider = "github";
        public String url;
        /** Id of a stored credential holding the Git provider token. */
        public String credentialId;
        public String mountPath;
        public String branch;

        /**
         * A GitHub organization or GitLab group path. When set, this one node stands for every
         * repository inside it, resolved when the run starts rather than when the flow is saved —
         * so a repository added to the group next week is picked up without editing the flow.
         *
         * <p>Mutually exclusive with {@link #url} in practice: a node is either one repository or
         * a whole group.
         */
        public String group;

        /**
         * Restricts a group to these repositories, by full name (e.g. {@code acme/api}). Empty
         * means every repository in the group.
         */
        public List<String> only = new ArrayList<>();

        /** Self-hosted GitLab or GitHub Enterprise API base. Blank uses the public cloud. */
        public String baseUrl;

        /** Archived repositories are read-only, so they are noise in a flow that exists to push. */
        public boolean includeArchived = false;

        public boolean isGroup() {
            return group != null && !group.isBlank();
        }

        public RepoProvider provider() {
            return RepoProvider.from(provider);
        }

        public String resolveToken() {
            return resolveToken(credentialLookup);
        }

        /** Same as {@link #resolveToken()}, resolving through {@code lookup} (for tests). */
        public String resolveToken(Function<String, String> lookup) {
            return resolveCredential(credentialId, lookup);
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
        /** Id of a stored credential holding the DB password (never the password itself). */
        public String credentialId;
        public String query;
        public int maxRows = 50;

        public String label() {
            return (label == null || label.isBlank()) ? "sql" : label;
        }

        /** The decrypted DB password, or null when none is configured or the id is unknown. */
        public String resolvePassword() {
            return resolvePassword(credentialLookup);
        }

        /** Same as {@link #resolvePassword()}, resolving through {@code lookup} (for tests). */
        public String resolvePassword(Function<String, String> lookup) {
            return resolveCredential(credentialId, lookup);
        }
    }

    /**
     * A knowledge base wired to this agent: the passages relevant to the run's prompt are
     * retrieved and injected as context, alongside the SQL sources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnowledgeSourceSpec {
        /** Canvas node id this spec came from (for per-node execution reporting). */
        public String nodeId;
        public String baseId;
        public String label;
        public int topK = 5;

        public String label() {
            return (label == null || label.isBlank()) ? "knowledge" : label;
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
