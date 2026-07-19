package com.concentus.service;

import com.concentus.config.AgentSpec.SqlSourceSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SqlRagRetriever}'s SSRF and read-only-query guardrails (WIR-7).
 * {@link SqlRagRetriever#query} validates the target and the query text <em>before</em> ever
 * opening a JDBC connection, so every case here is expected to fail validation and must never
 * reach {@code DriverManager.getConnection} — no test here touches the network. Cases that need
 * to prove a host or driver is *not* blocked use an explicit {@code allowedHosts}/host literal
 * plus a passwordEnv guard failure to still short-circuit before any connection attempt.
 */
class SqlRagRetrieverTest {

    private static SqlSourceSpec spec(String jdbcUrl, String query) {
        SqlSourceSpec s = new SqlSourceSpec();
        s.jdbcUrl = jdbcUrl;
        s.query = query;
        return s;
    }

    // ---------------------------------------------------------------- driver allowlist

    @Test
    void rejectsAJdbcUrlNotStartingWithJdbcScheme() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("postgresql://db.example.com/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with 'jdbc:'");
    }

    @Test
    void rejectsADriverNotOnTheAllowlist() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:mysql://db.example.com:3306/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsAJdbcUrlWithNoHost() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql:///app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include a host");
    }

    // ---------------------------------------------------------------- SSRF host guard

    @Test
    void rejectsLocalhostByHostname() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql://localhost:5432/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsTheLoopbackIpLiteral() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql://127.0.0.1:5432/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsTheCloudMetadataIpEvenThoughItIsNotLoopbackOrLinkLocalByRfcDefinition() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql://169.254.169.254:5432/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsALinkLocalIpLiteral() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql://169.254.1.1:5432/app", "select 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void theCloudMetadataIpNeverReachesAConnectionAttemptEvenWhenExplicitlyAllowlisted() {
        // ALWAYS_BLOCKED_HOSTS's javadoc promises the metadata IP is blocked "regardless of
        // allowlist config" (e.g. an operator's copy-paste mistake in rag.allowed-jdbc-hosts must
        // not unblock it). This forces a disallowed passwordEnv too, so the assertion holds — and
        // stays network-safe — even if that specific guarantee regresses: either guard failing
        // closed is enough to guarantee query() never opens a connection to it.
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "169.254.169.254");
        SqlSourceSpec s = spec("jdbc:postgresql://169.254.169.254:5432/app", "select 1");
        s.passwordEnv = "ANTHROPIC_API_KEY";

        assertThatThrownBy(() -> retriever.query(s)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExplicitlyAllowlistedHostPassesTargetValidation() {
        // Configure the host as explicitly allowed so isBlockedHost() (which would otherwise do a
        // DNS/loopback check) is never consulted. The passwordEnv guard then fails closed before
        // any connection is attempted, proving target validation itself passed without a network
        // call ever occurring.
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.internal.example.com");
        SqlSourceSpec s = spec("jdbc:postgresql://db.internal.example.com:5432/app", "select 1");
        s.passwordEnv = "ANTHROPIC_API_KEY"; // not on the AgentSpec allowlist -> guaranteed to fail closed

        assertThatThrownBy(() -> retriever.query(s))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordEnv");
    }

    // ---------------------------------------------------------------- read-only query guard

    @Test
    void rejectsABlankQuery() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.example.com");

        assertThatThrownBy(() -> retriever.query(spec("jdbc:postgresql://db.example.com:5432/app", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("`query` is required");
    }

    @Test
    void rejectsAMultiStatementQuery() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.example.com");

        assertThatThrownBy(() -> retriever.query(
                spec("jdbc:postgresql://db.example.com:5432/app", "select 1; drop table users")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single SELECT");
    }

    @Test
    void allowsATrailingSemicolonOnASingleStatement() {
        // A single trailing ';' is stripped before the "no ';'" check, so it alone must not be
        // rejected as multi-statement. Forcing a disallowed passwordEnv makes the call fail
        // closed right after validation passes, without ever attempting a connection.
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.example.com");
        SqlSourceSpec s = spec("jdbc:postgresql://db.example.com:5432/app", "select 1;");
        s.passwordEnv = "ANTHROPIC_API_KEY"; // forces a fail-closed exception before any connection

        assertThatThrownBy(() -> retriever.query(s))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordEnv"); // not rejected for "multi-statement"
    }

    @Test
    void rejectsANonSelectQuery() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.example.com");

        assertThatThrownBy(() -> retriever.query(
                spec("jdbc:postgresql://db.example.com:5432/app", "delete from users")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only SELECT");
    }

    // ---------------------------------------------------------------- passwordEnv guard (defense in depth)

    @Test
    void rejectsADisallowedPasswordEnvBeforeConnecting() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "db.example.com");
        SqlSourceSpec s = spec("jdbc:postgresql://db.example.com:5432/app", "select 1");
        s.passwordEnv = "SOME_SECRET";

        assertThatThrownBy(() -> retriever.query(s))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordEnv")
                .hasMessageContaining("SOME_SECRET");
    }

    // ---------------------------------------------------------------- asContextText formatting (pure)

    @Test
    void asContextTextFormatsColumnsRowsAndTruncationFlag() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");
        SqlSourceSpec s = spec("jdbc:postgresql://db.example.com:5432/app", "select 1");
        s.label = "Customers";
        SqlRagRetriever.TableResult result = new SqlRagRetriever.TableResult(
                java.util.List.of("id", "name"),
                java.util.List.of(java.util.List.of("1", "Ada")),
                true);

        String text = retriever.asContextText(s, result);

        assertThat(text).contains("Source: Customers (1 row(s), truncated)");
        assertThat(text).contains("id | name");
        assertThat(text).contains("1 | Ada");
    }

    @Test
    void asContextTextCollapsesWhitespaceAndTruncatesLongCells() {
        SqlRagRetriever retriever = new SqlRagRetriever("postgresql", "");
        SqlSourceSpec s = spec("jdbc:postgresql://db.example.com:5432/app", "select 1");
        String longValue = "x".repeat(250);
        SqlRagRetriever.TableResult result = new SqlRagRetriever.TableResult(
                java.util.List.of("col"),
                java.util.List.of(java.util.List.of("line1\n  line2   line3")),
                false);
        SqlRagRetriever.TableResult longResult = new SqlRagRetriever.TableResult(
                java.util.List.of("col"),
                java.util.List.of(java.util.List.of(longValue)),
                false);

        assertThat(retriever.asContextText(s, result)).contains("line1 line2 line3");
        String longText = retriever.asContextText(s, longResult);
        assertThat(longText).contains("…");
        assertThat(longText.lines().filter(l -> l.startsWith("x"))).allSatisfy(
                l -> assertThat(l.length()).isLessThanOrEqualTo(201));
    }
}
