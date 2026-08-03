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

    // ---------------------------------------------------------------- credential resolution
    // Nodes reference a stored credential by id. The env-var allowlist these tests used to guard
    // is gone with the mechanism it protected: a node can no longer name an arbitrary environment
    // variable, so there is nothing to point at ANTHROPIC_API_KEY in the first place.

    
    void resolvePasswordReturnsNullWhenNoCredentialIsSelected() {
        SqlSourceSpec sql = new SqlSourceSpec();

        assertThat(sql.resolvePassword(id -> "should-not-be-reached")).isNull();
    }

    
    void resolvePasswordReturnsNullForABlankCredentialId() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.credentialId = "   ";

        assertThat(sql.resolvePassword(id -> "should-not-be-reached")).isNull();
    }

    
    void resolvePasswordNeverInvokesTheLookupWithoutACredentialId() {
        SqlSourceSpec sql = new SqlSourceSpec();

        assertThat(sql.resolvePassword(id -> {
            throw new AssertionError("lookup must not be called when no credential is selected");
        })).isNull();
    }

    
    void resolvePasswordReturnsTheDecryptedValue() {
        SqlSourceSpec sql = new SqlSourceSpec();
        sql.credentialId = "cred_db";

        assertThat(sql.resolvePassword(id -> "cred_db".equals(id) ? "s3cret" : null)).isEqualTo("s3cret");
    }

    
    void resolveTokenOnMcpServerSpecReturnsTheDecryptedValue() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.credentialId = "cred_mcp";

        assertThat(mcp.resolveToken(id -> "cred_mcp".equals(id) ? "tok" : null)).isEqualTo("tok");
    }

    
    void resolveTokenOnRepoSpecReturnsTheDecryptedValue() {
        RepoSpec repo = new RepoSpec();
        repo.credentialId = "cred_repo";

        assertThat(repo.resolveToken(id -> "cred_repo".equals(id) ? "ghp_x" : null)).isEqualTo("ghp_x");
    }

    
    void aDeletedCredentialResolvesToNullRatherThanThrowing() {
        // A node pointing at a credential someone removed must behave like one with none
        // configured: the caller already reports that in terms a user can act on.
        McpServerSpec mcp = new McpServerSpec();
        mcp.credentialId = "cred_gone";

        assertThat(mcp.resolveToken(id -> null)).isNull();
    }

    
    void anEmptyDecryptedValueIsTreatedAsNoCredential() {
        McpServerSpec mcp = new McpServerSpec();
        mcp.credentialId = "cred_blank";

        assertThat(mcp.resolveToken(id -> "")).isNull();
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
