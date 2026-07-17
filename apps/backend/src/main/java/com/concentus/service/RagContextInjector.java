package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
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
        if (spec.ragSources.isEmpty()) return;
        StringBuilder ctx = new StringBuilder();
        for (SqlSourceSpec q : spec.ragSources) {
            try {
                var result = retriever.query(q);
                ctx.append("\n\n# Retrieved context — ").append(q.label()).append('\n')
                        .append(retriever.asContextText(q, result));
                emit.accept("RAG: '" + q.label() + "' → " + result.rows().size()
                        + " row(s) injected into agent '" + spec.name + "'.");
            } catch (Exception e) {
                emit.accept("RAG: '" + q.label() + "' query failed: " + e.getMessage());
                ctx.append("\n\n# Retrieved context — ").append(q.label())
                        .append(" (unavailable: ").append(e.getMessage()).append(')');
            }
        }
        spec.systemPrompt = spec.systemPrompt + ctx;
    }
}
