package com.concentus.config;

import com.concentus.config.AgentSpec.CacheSpec;
import com.concentus.config.AgentSpec.ContextSpec;
import com.concentus.config.AgentSpec.EnvironmentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.ModelSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentSpec}: default construction, {@link AgentSpec#validate()}'s
 * required-field/normalization rules, and the env-var allowlist guard (WIR-7) that
 * {@link SqlSourceSpec#resolvePassword()}, {@link McpServerSpec#resolveToken()} and
 * {@link RepoSpec#resolveToken()} share.
 *
 * <p>The allowlist itself ({@code AgentSpec.allowedEnvVars} / {@code allowedEnvVarPrefixes}) is
 * static state normally populated by the Spring-managed {@code EnvAllowlistConfig} bean from
 * {@code rag.allowed-env-vars} / {@code rag.allowed-env-var-prefixes}. This test runs without a
 * Spring context, so it relies on those fields' hard-coded defaults (no explicit allowed names,
 * "WIREJ_DB_" as the allowed prefix) — the same defaults application.properties falls back to
 * when the env vars driving them are unset.
 */
class AgentSpecTest {

    private static AgentSpec minimalValidSpec() {
        AgentSpec s = new AgentSpec();
        s.name = "agent";
        s.model = new ModelSpec();
        s.model.id = "claude-opus-4-8";
        s.model.maxTokens = 16000;
        return s;
    }

    // ---------------------------------------------------------------- defaults / run mode

    @Test
    void defaultSpecIsManagedMode() {
        AgentSpec s = new AgentSpec();

        assertThat(s.runMode()).isEqualTo(AgentSpec.RunMode.MANAGED);
    }

    @Test
    void runModeAcceptsLocalCaseInsensitively() {
        AgentSpec s = new AgentSpec();
        s.mode = " Local ";

        assertThat(s.runMode()).isEqualTo(AgentSpec.RunMode.LOCAL);
    }

    @Test
    void runModeThrowsForAnUnknownMode() {
        AgentSpec s = new AgentSpec();
        s.mode = "bogus";

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown mode");
    }

    @Test
    void runModeThrowsWhenModeIsNull() {
        AgentSpec s = new AgentSpec();
        s.mode = null;

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("`mode` is required");
    }

    // ---------------------------------------------------------------- validate(): required fields

    @Test
    void validatePassesForAMinimalValidSpec() {
        assertThatCode(minimalValidSpec()::validate).doesNotThrowAnyException();
    }

    @Test
    void validateThrowsWhenNameIsBlank() {
        AgentSpec s = minimalValidSpec();
        s.name = "  ";

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("`name` is required");
    }

    @Test
    void validateThrowsWhenModelIdIsBlank() {
        AgentSpec s = minimalValidSpec();
        s.model.id = "";

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model.id");
    }

    @Test
    void validateThrowsWhenMaxTokensIsNotPositive() {
        AgentSpec s = minimalValidSpec();
        s.model.maxTokens = 0;

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTokens");
    }

    @Test
    void validateThrowsWhenAnMcpServerHasNoName() {
        AgentSpec s = minimalValidSpec();
        McpServerSpec mcp = new McpServerSpec();
        mcp.url = "https://example.com/mcp";
        s.mcpServers.add(mcp);

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a `name`");
    }

    @Test
    void validateThrowsWhenAnMcpServerHasNoUrl() {
        AgentSpec s = minimalValidSpec();
        McpServerSpec mcp = new McpServerSpec();
        mcp.name = "github";
        s.mcpServers.add(mcp);

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a `url`");
    }

    @Test
    void validateThrowsWhenARepositoryHasNoUrl() {
        AgentSpec s = minimalValidSpec();
        s.repositories.add(new RepoSpec());

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("each repository needs a `url`");
    }

    @Test
    void validateThrowsForAnUnknownRepoProvider() {
        AgentSpec s = minimalValidSpec();
        RepoSpec repo = new RepoSpec();
        repo.url = "https://github.com/acme/repo";
        repo.provider = "bitbucket";
        s.repositories.add(repo);

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown repo provider");
    }

    @Test
    void validateThrowsWhenASqlSourceHasNoJdbcUrl() {
        AgentSpec s = minimalValidSpec();
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.query = "select 1";
        s.ragSources.add(sql);

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a `jdbcUrl`");
    }

    @Test
    void validateThrowsWhenASqlSourceHasNoQuery() {
        AgentSpec s = minimalValidSpec();
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.jdbcUrl = "jdbc:postgresql://db.example.com:5432/app";
        s.ragSources.add(sql);

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a `query`");
    }

    // ---------------------------------------------------------------- validate(): normalization

    @Test
    void validateNormalizesCacheTtlToLowercase() {
        AgentSpec s = minimalValidSpec();
        s.cache.ttl = " 1H ";

        s.validate();

        assertThat(s.cache.ttl).isEqualTo("1h");
    }

    @Test
    void validateRejectsAnInvalidCacheTtl() {
        AgentSpec s = minimalValidSpec();
        s.cache.ttl = "10m";

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cache.ttl");
    }

    @Test
    void validateRejectsAnInvalidContextStrategy() {
        AgentSpec s = minimalValidSpec();
        s.context.strategy = "bogus-strategy";

        assertThatThrownBy(s::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context.strategy");
    }

    @Test
    void validateAcceptsEachKnownContextStrategy() {
        for (String strategy : new String[] {"none", "compaction", "context-editing"}) {
            AgentSpec s = minimalValidSpec();
            s.context.strategy = strategy;
            assertThatCode(s::validate).doesNotThrowAnyException();
        }
    }

    @Test
    void validateDefaultsEnvironmentNameAndNetworkingWhenBlank() {
        AgentSpec s = minimalValidSpec();
        s.environment.name = "  ";
        s.environment.networking = null;

        s.validate();

        assertThat(s.environment.name).isEqualTo("agent-env");
        assertThat(s.environment.networking).isEqualTo("unrestricted");
    }

    // ---------------------------------------------------------------- env-var allowlist guard (WIR-7)
    // A prior security story closed a hole where any caller-supplied env var name (e.g.
    // ANTHROPIC_API_KEY) could be read via passwordEnv/tokenEnv. These assert the guard still
    // holds: a name is only ever resolved if it's on the allowlist or matches an allowed prefix.

    @Test
    void resolvePasswordReturnsNullWhenPasswordEnvIsUnset() {
        SqlSourceSpec sql = new SqlSourceSpec();

        assertThat(sql.resolvePassword()).isNull();
        assertThat(sql.hasDisallowedPasswordEnv()).isFalse();
    }

    @Test
    void resolvePasswordReturnsNullWhenPasswordEnvIsBlank() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.passwordEnv = "   ";

        assertThat(sql.resolvePassword()).isNull();
        assertThat(sql.hasDisallowedPasswordEnv()).isFalse();
    }

    @Test
    void resolvePasswordRefusesANonAllowlistedEnvVarNameEvenIfItWouldResolve() {
        // Not on the allowlist and doesn't match the WIREJ_DB_ prefix — must never be read,
        // regardless of whether such a variable happens to be set in the process environment.
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.passwordEnv = "ANTHROPIC_API_KEY";

        assertThat(sql.resolvePassword()).isNull();
        assertThat(sql.hasDisallowedPasswordEnv()).isTrue();
    }

    @Test
    void hasDisallowedPasswordEnvIsFalseForANameMatchingTheAllowedPrefix() {
        // The prefix match itself is what's under test here (not the actual env var value, which
        // this test environment doesn't control) — a WIREJ_DB_-prefixed name must be treated as
        // allowed, not rejected.
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.passwordEnv = "WIREJ_DB_PASSWORD";

        assertThat(sql.hasDisallowedPasswordEnv()).isFalse();
        // Resolves to null only because no such variable is actually set in this process — the
        // allowlist did not block it.
        assertThat(sql.resolvePassword()).isNull();
    }

    @Test
    void resolveTokenOnMcpServerSpecRefusesANonAllowlistedName() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.tokenEnv = "ANTHROPIC_API_KEY";

        assertThat(mcp.resolveToken()).isNull();
    }

    @Test
    void resolveTokenOnMcpServerSpecReturnsNullWhenTokenEnvIsUnset() {
        McpServerSpec mcp = new McpServerSpec();

        assertThat(mcp.resolveToken()).isNull();
    }

    @Test
    void resolveTokenOnRepoSpecRefusesANonAllowlistedName() {
        RepoSpec repo = new RepoSpec();
        repo.tokenEnv = "ANTHROPIC_API_KEY";

        assertThat(repo.resolveToken()).isNull();
    }

    @Test
    void resolveTokenOnRepoSpecReturnsNullWhenTokenEnvIsUnset() {
        RepoSpec repo = new RepoSpec();

        assertThat(repo.resolveToken()).isNull();
    }

    // ------------------------------------------------------- unified resolution via injected env lookup (A.3)
    // These use the Function<String,String> overloads added alongside the shared
    // resolveAllowedEnvVar() helper, so the actual resolved value can be asserted without touching
    // real process environment variables.

    @Test
    void resolveTokenOnMcpServerSpecReturnsTheLookedUpValueWhenAllowlisted() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.tokenEnv = "WIREJ_DB_TOKEN";

        assertThat(mcp.resolveToken(name -> "secret-value")).isEqualTo("secret-value");
    }

    @Test
    void resolveTokenOnMcpServerSpecNeverInvokesTheLookupForANonAllowlistedName() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.tokenEnv = "ANTHROPIC_API_KEY";

        assertThat(mcp.resolveToken(name -> {
            throw new AssertionError("lookup must not be called for a non-allowlisted name");
        })).isNull();
    }

    @Test
    void resolveTokenOnMcpServerSpecTreatsAnEmptyLookedUpValueAsNull() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.tokenEnv = "WIREJ_DB_TOKEN";

        assertThat(mcp.resolveToken(name -> "")).isNull();
    }

    @Test
    void resolveTokenOnRepoSpecReturnsTheLookedUpValueWhenAllowlisted() {
        RepoSpec repo = new RepoSpec();
        repo.tokenEnv = "WIREJ_DB_TOKEN";

        assertThat(repo.resolveToken(name -> "gh-token")).isEqualTo("gh-token");
    }

    @Test
    void resolvePasswordReturnsTheLookedUpValueWhenAllowlisted() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.passwordEnv = "WIREJ_DB_PASSWORD";

        assertThat(sql.resolvePassword(name -> "db-pass")).isEqualTo("db-pass");
    }

    @Test
    void resolvePasswordNeverInvokesTheLookupWhenPasswordEnvIsBlank() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.passwordEnv = "   ";

        assertThat(sql.resolvePassword(name -> {
            throw new AssertionError("lookup must not be called when passwordEnv is blank");
        })).isNull();
    }

    // ------------------------------------------------------------------------- label() / provider()

    @Test
    void sqlSourceLabelFallsBackToSqlWhenBlank() {
        SqlSourceSpec sql = new SqlSourceSpec();

        assertThat(sql.label()).isEqualTo("sql");

        sql.label = "   ";
        assertThat(sql.label()).isEqualTo("sql");
    }

    @Test
    void sqlSourceLabelReturnsTheExplicitValueWhenPresent() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.label = "orders-db";

        assertThat(sql.label()).isEqualTo("orders-db");
    }

    @Test
    void repoSpecProviderResolvesKnownProvidersCaseInsensitively() {
        RepoSpec repo = new RepoSpec();
        repo.provider = "GitHub";

        assertThat(repo.provider()).isEqualTo(AgentSpec.RepoProvider.GITHUB);

        repo.provider = "gitlab";
        assertThat(repo.provider()).isEqualTo(AgentSpec.RepoProvider.GITLAB);
    }

    @Test
    void repoSpecProviderThrowsForAnUnknownProvider() {
        RepoSpec repo = new RepoSpec();
        repo.provider = "bitbucket";

        assertThatThrownBy(repo::provider).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown repo provider");
    }
}
