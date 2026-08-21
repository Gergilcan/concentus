package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.NodeExec;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Runs each attached SQL and knowledge source and appends the results to an agent's context.
 *
 * <p>Knowledge passages arrive numbered, with a citation key and an instruction to use it. An
 * answer nobody can check against its sources is the failure mode this whole subsystem exists to
 * avoid, and the numbering is what makes checking a matter of looking rather than of trusting.
 */
@Component
public class RagContextInjector {

    private final SqlRagRetriever retriever;
    private final KnowledgeRetriever knowledge;
    private final ContextAssembler assembler;

    public RagContextInjector(SqlRagRetriever retriever, KnowledgeRetriever knowledge,
                              ContextAssembler assembler) {
        this.retriever = retriever;
        this.knowledge = knowledge;
        this.assembler = assembler;
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

        // Embedded once for every source: three knowledge nodes used to mean three identical
        // inferences over the same prompt. Costs one embed even when every base is overlap-only,
        // which at one short batched pass is cheaper than plumbing per-base awareness up here.
        float[] queryVector = knowledge.embedQuery(query);

        StringBuilder ctx = new StringBuilder();
        for (AgentSpec.KnowledgeSourceSpec source : spec.knowledgeSources) {
            NodeExec ne = run == null ? null : run.nodeExec(source.nodeId, "knowledge", source.label());
            if (ne != null) {
                ne.status = "running";
                ne.input = com.concentus.support.Texts.brief(query, 400);
            }
            try {
                var hits = knowledge.search(source.baseId, query, queryVector, source.topK);
                var assembled = assembler.assemble(hits);
                ctx.append("\n\n# Retrieved context — ").append(source.label()).append('\n')
                        .append(assembler.asPromptText(source.label(), assembled));
                emit.accept("Knowledge: '" + source.label() + "' → " + hits.size()
                        + " passage(s) injected into agent '" + spec.name + "'.");
                if (ne != null) {
                    // The assembled spans, not the raw hits: what the box reports and what the
                    // agent was handed have to be the same thing, or the box is decoration.
                    ne.output = assembled.isEmpty()
                            ? "No matching passages"
                            : assembled.passages().stream()
                                    .map(ContextAssembler.Passage::citation)
                                    .collect(java.util.stream.Collectors.joining(", "));
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
