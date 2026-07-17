package com.concentus.service;

import com.concentus.config.AgentSpec.SqlSourceSpec;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic JDBC-backed retrieval for RAG. Runs a SQL query against any {@code jdbc:} URL
 * (the matching driver must be on the classpath — PostgreSQL is bundled) and returns the
 * rows, both as structured data (for a UI preview) and as text (for agent context).
 */
@Component
public class SqlRagRetriever {

    private static final int HARD_ROW_CAP = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 20;
    private static final int MAX_CELL_CHARS = 200;

    public record TableResult(List<String> columns, List<List<String>> rows, boolean truncated) {}

    public TableResult query(SqlSourceSpec spec) throws Exception {
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
