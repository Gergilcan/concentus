package com.concentus.service;

import com.concentus.config.AgentSpec.SqlSourceSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic JDBC-backed retrieval for RAG. Runs a SQL query against any {@code jdbc:} URL
 * (the matching driver must be on the classpath — PostgreSQL is bundled) and returns the
 * rows, both as structured data (for a UI preview) and as text (for agent context).
 *
 * <p>Both the target (scheme + host) and the query are validated <em>before</em> any connection
 * is opened, to close an SSRF path: {@link SqlSourceSpec} is accepted from request bodies
 * (including the unauthenticated {@code /api/rag/preview} endpoint), so a caller could otherwise
 * point {@code jdbcUrl} at an arbitrary internal host (e.g. the cloud metadata IP) or run a
 * data-modifying statement. See {@code rag.allowed-jdbc-drivers} / {@code rag.allowed-jdbc-hosts}
 * in application.properties.
 */
@Component
public class SqlRagRetriever {

    private static final int HARD_ROW_CAP = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 20;
    private static final int MAX_CELL_CHARS = 200;

    /** Cloud-metadata IPs blocked regardless of allowlist config (AWS/GCP/Azure/etc). */
    private static final Set<String> ALWAYS_BLOCKED_HOSTS = Set.of("169.254.169.254");

    private final Set<String> allowedDrivers;
    private final Set<String> allowedHosts;

    public SqlRagRetriever(
            @Value("${rag.allowed-jdbc-drivers:postgresql}") String allowedDriversCsv,
            @Value("${rag.allowed-jdbc-hosts:}") String allowedHostsCsv) {
        this.allowedDrivers = toSet(allowedDriversCsv);
        this.allowedHosts = toSet(allowedHostsCsv);
    }

    private static Set<String> toSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public record TableResult(List<String> columns, List<List<String>> rows, boolean truncated) {}

    public TableResult query(SqlSourceSpec spec) throws Exception {
        validateTarget(spec.jdbcUrl);
        validateReadOnly(spec.query);
        if (spec.hasDisallowedPasswordEnv()) {
            throw new IllegalArgumentException(
                    "`passwordEnv` '" + spec.passwordEnv + "' is not on the allowed list of environment "
                            + "variables (see rag.allowed-env-vars / rag.allowed-env-var-prefixes).");
        }

        int cap = Math.min(spec.maxRows <= 0 ? 50 : spec.maxRows, HARD_ROW_CAP);
        String password = spec.resolvePassword();
        try (Connection conn = DriverManager.getConnection(spec.jdbcUrl, spec.username, password);
             Statement st = conn.createStatement()) {
            st.setMaxRows(cap + 1);
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(spec.query)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> cols = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));

                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= cap) {
                        truncated = true;
                        break;
                    }
                    List<String> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? "" : String.valueOf(v));
                    }
                    rows.add(row);
                }
                return new TableResult(cols, rows, truncated);
            }
        }
    }

    /** Validates scheme + host BEFORE any connection attempt (SSRF guard). */
    private void validateTarget(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("`jdbcUrl` must start with 'jdbc:'.");
        }
        URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("`jdbcUrl` is not a valid URL.");
        }
        String driver = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (driver == null || !allowedDrivers.contains(driver)) {
            throw new IllegalArgumentException(
                    "JDBC driver '" + driver + "' is not allowed (see rag.allowed-jdbc-drivers).");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("`jdbcUrl` must include a host.");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        // ALWAYS_BLOCKED_HOSTS must hold no matter what — checked before, and independently of,
        // the allowedHosts allowlist below. Otherwise an operator adding a host to
        // rag.allowed-jdbc-hosts that happens to be the cloud metadata IP would silently bypass
        // this block, contradicting the "regardless of allowlist config" guarantee documented on
        // ALWAYS_BLOCKED_HOSTS.
        if (ALWAYS_BLOCKED_HOSTS.contains(lowerHost)) {
            throw new IllegalArgumentException(
                    "Host '" + host + "' is not allowed (cloud metadata hosts are always blocked).");
        }
        if (!allowedHosts.contains(lowerHost) && isBlockedHost(host)) {
            throw new IllegalArgumentException(
                    "Host '" + host + "' is not allowed (localhost, link-local and cloud metadata hosts are "
                            + "blocked by default — see rag.allowed-jdbc-hosts).");
        }
    }

    /** Loopback / link-local / cloud-metadata hosts are blocked unless explicitly allowlisted. */
    private boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || ALWAYS_BLOCKED_HOSTS.contains(h)) return true;
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (Exception e) {
            // Unresolvable host — fail closed rather than risk a surprising SSRF target.
            return true;
        }
    }

    /** Enforces read-only, single-statement queries (defense in depth alongside DB-level grants). */
    private void validateReadOnly(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("`query` is required.");
        }
        String q = query.trim().replaceAll(";\\s*$", "");
        if (q.contains(";")) {
            throw new IllegalArgumentException("Only a single SELECT statement is allowed.");
        }
        if (!q.toLowerCase(Locale.ROOT).matches("(?s)^select\\b.*")) {
            throw new IllegalArgumentException("Only read-only SELECT queries are allowed.");
        }
    }

    /** Formats a result as a compact table for injection into an agent's system prompt. */
    public String asContextText(SqlSourceSpec spec, TableResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: ").append(spec.label())
                .append(" (").append(r.rows().size()).append(" row(s)");
        if (r.truncated()) sb.append(", truncated");
        sb.append(")\n");
        sb.append(String.join(" | ", r.columns())).append('\n');
        sb.append("---\n");
        for (List<String> row : r.rows()) {
            sb.append(String.join(" | ", row.stream().map(SqlRagRetriever::oneLine).toList())).append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_CELL_CHARS ? t.substring(0, MAX_CELL_CHARS) + "…" : t;
    }
}
