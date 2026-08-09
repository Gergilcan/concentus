package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.NodeExec;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Runs each attached SQL and knowledge source and appends the results to an agent's context. */
@Component
public class RagContextInjector {

    private final SqlRagRetriever retriever;
    private final KnowledgeService knowledge;

    public RagContextInjector(SqlRagRetriever retriever, KnowledgeService knowledge) {
        this.retriever = retriever;
        this.knowledge = knowledge;
    }

    public void inject(AgentSpec spec, Consumer<String> emit) {
        inject(spec, null, emit);
    }

    /**
     * Runs the agent's SQL sources. When {@code run} is provided, each source's query/rows/status
     * are recorded as a node execution so the UI can show a formatted table and pass/fail per box.
     */
    public void inject(AgentSpec spec, AgentRun run, Consumer<String> emit) {
        injectKnowledge(spec, run, emit);
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

    /**
     * Retrieves from each knowledge base wired to this agent, using the run's initial prompt as
     * the query.
     *
     * <p>The prompt rather than a fixed query is the point of a knowledge base: which passages
     * matter depends on what this run is about. By the time injection happens the prompt exists
     * for every trigger — prompt, cron, webhook and mail runs carry one, and a manual run records
     * its first command before the first turn is built. If it is somehow blank the base's own
     * description is used, which at least selects the base's central topic over nothing.
     */
    private void injectKnowledge(AgentSpec spec, AgentRun run, Consumer<String> emit) {
        if (spec.knowledgeSources.isEmpty()) return;
        String query = run != null && run.initialPrompt != null && !run.initialPrompt.isBlank()
                ? run.initialPrompt
                : spec.systemPrompt;

        StringBuilder ctx = new StringBuilder();
        for (AgentSpec.KnowledgeSourceSpec source : spec.knowledgeSources) {
            NodeExec ne = run == null ? null : run.nodeExec(source.nodeId, "knowledge", source.label());
            if (ne != null) {
                ne.status = "running";
                ne.input = query.length() > 400 ? query.substring(0, 400) + "…" : query;
            }
            try {
                var hits = knowledge.search(source.baseId, query, source.topK);
                ctx.append("\n\n# Retrieved context — ").append(source.label()).append('\n');
                if (hits.isEmpty()) {
                    ctx.append("(no matching passages)");
                } else {
                    for (var hit : hits) {
                        ctx.append("\n## ").append(hit.docName()).append(" — passage ")
                                .append(hit.seq() + 1).append('\n').append(hit.content()).append('\n');
                    }
                }
                emit.accept("Knowledge: '" + source.label() + "' → " + hits.size()
                        + " passage(s) injected into agent '" + spec.name + "'.");
                if (ne != null) {
                    ne.output = hits.isEmpty()
                            ? "No matching passages"
                            : hits.stream()
                                    .map(h -> h.docName() + " #" + (h.seq() + 1))
                                    .distinct().collect(java.util.stream.Collectors.joining(", "));
                    ne.status = "passed";
                    ne.endedAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                emit.accept("Knowledge: '" + source.label() + "' failed: " + e.getMessage());
                ctx.append("\n\n# Retrieved context — ").append(source.label())
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
