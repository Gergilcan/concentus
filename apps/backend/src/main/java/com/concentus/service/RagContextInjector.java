package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.NodeExec;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Runs each attached SQL source and appends its rows to an agent's system prompt. */
@Component
public class RagContextInjector {

    private final SqlRagRetriever retriever;

    public RagContextInjector(SqlRagRetriever retriever) {
        this.retriever = retriever;
    }

    public void inject(AgentSpec spec, Consumer<String> emit) {
        inject(spec, null, emit);
    }

    /**
     * Runs the agent's SQL sources. When {@code run} is provided, each source's query/rows/status
     * are recorded as a node execution so the UI can show a formatted table and pass/fail per box.
     */
    public void inject(AgentSpec spec, AgentRun run, Consumer<String> emit) {
        if (spec.ragSources.isEmpty()) return;
        StringBuilder ctx = new StringBuilder();
        for (SqlSourceSpec q : spec.ragSources) {
            NodeExec ne = run == null ? null : run.nodeExec(q.nodeId, "sql", q.label());
            if (ne != null) {
                ne.status = "running";
                ne.input = q.query;
            }
            try {
                var result = retriever.query(q);
                ctx.append("\n\n# Retrieved context — ").append(q.label()).append('\n')
                        .append(retriever.asContextText(q, result));
                emit.accept("RAG: '" + q.label() + "' → " + result.rows().size()
                        + " row(s) injected into agent '" + spec.name + "'.");
                if (ne != null) {
                    ne.format = "table";
                    ne.columns = result.columns();
                    ne.rows = result.rows();
                    ne.output = result.rows().size() + " row(s)"
                            + (result.truncated() ? " (truncated)" : "");
                    ne.status = "passed";
                    ne.endedAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                emit.accept("RAG: '" + q.label() + "' query failed: " + e.getMessage());
                ctx.append("\n\n# Retrieved context — ").append(q.label())
                        .append(" (unavailable: ").append(e.getMessage()).append(')');
                if (ne != null) {
                    ne.status = "failed";
                    ne.error = e.getMessage();
                    ne.endedAt = System.currentTimeMillis();
                }
            }
        }
        spec.systemPrompt = spec.systemPrompt + ctx;
    }
}
