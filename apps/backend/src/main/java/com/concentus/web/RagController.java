package com.concentus.web;

import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.service.SqlRagRetriever;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG sources. Currently supports generic SQL (JDBC) sources: a SQL node connected to an
 * agent runs its query at run time and its rows are injected into that agent's context.
 * {@code /preview} lets the UI test a query before running the flow.
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final SqlRagRetriever retriever;

    public RagController(SqlRagRetriever retriever) {
        this.retriever = retriever;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", true,
                "message", "SQL (JDBC) RAG sources are supported. Connect a SQL node to an agent; its query "
                        + "rows are retrieved and injected into that agent's context when the flow runs.",
                "sources", List.of("sql"));
    }

    /** Runs a SQL source's query and returns the rows, or a 400 with the DB error. */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody SqlSourceSpec spec) {
        if (spec.jdbcUrl == null || spec.jdbcUrl.isBlank() || spec.query == null || spec.query.isBlank()) {
            throw new IllegalArgumentException("`jdbcUrl` and `query` are required.");
        }
        try {
            SqlRagRetriever.TableResult r = retriever.query(spec);
            return ResponseEntity.ok(Map.of(
                    "columns", r.columns(),
                    "rows", r.rows(),
                    "rowCount", r.rows().size(),
                    "truncated", r.truncated()));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
