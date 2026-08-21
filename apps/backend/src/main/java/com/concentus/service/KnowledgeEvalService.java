package com.concentus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Questions with known answers, and what retrieval does with them.
 *
 * <p><b>Why this exists.</b> Every change to ranking sounds like an improvement. The evidence is
 * always the same — somebody types three queries after the change and reports that it feels
 * sharper — and under that regime a system gets quietly worse while everyone agrees it is getting
 * better. Twenty questions whose answers are known turn a feeling into a number, and a number can
 * go down.
 *
 * <p>What is measured is <b>retrieval</b>, not the answer: did the document that holds the answer
 * come back, and how near the top. Judging the generated answer needs a model to grade another
 * model, which is a second thing to be wrong and a bill per run; it is also a different question.
 * A wrong answer built on the right passage is a prompt problem. A right-sounding answer built on
 * the wrong passage is this one, and it is the one that ships silently.
 *
 * <p>The harness can run with the reranker turned off, which is the only honest way to answer "is
 * it worth 282 MB on my documents" — a question nobody can answer from a benchmark run on someone
 * else's corpus.
 */
@Service
public class KnowledgeEvalService {

    /** How deep a case counts as answered. Beyond this nobody scrolls, so nothing is credited. */
    private static final int DEFAULT_TOP_K = 5;

    private final JdbcTemplate jdbc;
    private final KnowledgeRetriever retriever;
    private final ObjectMapper mapper;

    public KnowledgeEvalService(JdbcTemplate jdbc, KnowledgeRetriever retriever, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.retriever = retriever;
        this.mapper = mapper;
    }

    /** One question and the document(s) that answer it. */
    public record EvalCase(String id, String question, List<String> expectedDocs, long createdAt) {
    }

    /**
     * What retrieval did with one case.
     *
     * @param rank     where the first expected document appeared, 1-based; 0 when it did not
     * @param found    which of the expected documents came back at all
     * @param returned what actually came back, in order, so a miss can be looked at rather than
     *                 merely counted — the retrieved documents ARE the explanation of the miss
     */
    public record CaseResult(String id, String question, List<String> expectedDocs, int rank,
                             List<String> found, List<String> returned) {
        public boolean hit() {
            return rank > 0;
        }
    }

    /**
     * The numbers, named for what they actually measure.
     *
     * @param hitRate  fraction of cases where at least one expected document made the top k. The
     *                 headline: it is the share of questions a person would have got an answer to
     * @param recall   fraction of all expected documents retrieved, across every case. Lower than
     *                 hit rate whenever a case expects two documents and one came back — which is
     *                 a real partial failure that the hit rate alone would hide
     * @param mrr      mean reciprocal rank: 1 for an answer at the top, 0.5 for second, 0 for a
     *                 miss. What separates "found it" from "found it first", which matters because
     *                 a passage at rank 5 competes for the context budget with four wrong ones
     */
    public record EvalRun(int topK, boolean reranked, int cases, double hitRate, double recall,
                          double mrr, List<CaseResult> results) {
    }

    public List<EvalCase> list(String baseId) {
        return jdbc.query("""
                select id, question, expected_docs, created_at
                  from knowledge_evals where base_id = ?
                 order by created_at
                """,
                (rs, i) -> new EvalCase(rs.getString("id"), rs.getString("question"),
                        readDocs(rs.getString("expected_docs")), rs.getLong("created_at")),
                baseId);
    }

    public EvalCase save(String baseId, String question, List<String> expectedDocs) {
        String q = question == null ? "" : question.strip();
        if (q.isBlank()) throw new IllegalArgumentException("A question is required.");
        List<String> docs = expectedDocs == null ? List.of() : expectedDocs.stream()
                .filter(d -> d != null && !d.isBlank()).map(String::strip).distinct().toList();
        if (docs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Name at least one document that answers it — a case with no expected answer "
                            + "cannot pass or fail.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
                insert into knowledge_evals (id, base_id, question, expected_docs, created_at)
                values (?, ?, ?, ?, ?)
                """, id, baseId, q, writeDocs(docs), now);
        return new EvalCase(id, q, docs, now);
    }

    public boolean delete(String baseId, String id) {
        return jdbc.update("delete from knowledge_evals where base_id = ? and id = ?", baseId, id) > 0;
    }

    /** Everything a deleted base would otherwise leave behind. */
    public void deleteAll(String baseId) {
        jdbc.update("delete from knowledge_evals where base_id = ?", baseId);
    }

    /**
     * Runs every case and scores them.
     *
     * <p>Sequential on purpose. This is a person pressing a button and watching, over tens of
     * cases, and the reranker is synchronized anyway — parallelism would buy contention and a less
     * predictable number, which is the opposite of what a measurement is for.
     */
    public EvalRun run(String baseId, int topK, boolean rerank) {
        int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 20);
        List<EvalCase> cases = list(baseId);
        List<CaseResult> results = new ArrayList<>(cases.size());

        int hits = 0;
        int expectedTotal = 0;
        int foundTotal = 0;
        double reciprocalRanks = 0;

        for (EvalCase c : cases) {
            List<KnowledgeRetriever.Hit> retrieved = retriever.search(baseId, c.question(), null, k, rerank);
            // Documents in the order they were first seen: a document whose passages 3 and 7 both
            // came back occupies one rank, not two, because a person opens the document once.
            List<String> returned = new ArrayList<>(new LinkedHashSet<>(
                    retrieved.stream().map(KnowledgeRetriever.Hit::docName).toList()));

            Set<String> expected = new LinkedHashSet<>(c.expectedDocs());
            List<String> found = returned.stream().filter(expected::contains).toList();

            int rank = 0;
            for (int i = 0; i < returned.size(); i++) {
                if (expected.contains(returned.get(i))) {
                    rank = i + 1;
                    break;
                }
            }

            if (rank > 0) {
                hits++;
                reciprocalRanks += 1.0 / rank;
            }
            expectedTotal += expected.size();
            foundTotal += found.size();

            results.add(new CaseResult(c.id(), c.question(), c.expectedDocs(), rank, found, returned));
        }

        int n = cases.size();
        return new EvalRun(k, rerank, n,
                n == 0 ? 0 : (double) hits / n,
                expectedTotal == 0 ? 0 : (double) foundTotal / expectedTotal,
                n == 0 ? 0 : reciprocalRanks / n,
                results);
    }

    // ---------------------------------------------------------------- internals

    private List<String> readDocs(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // A row written by a future version, or by hand. Losing the question would be worse
            // than showing it with no expected answer, which at least says something is wrong.
            return List.of();
        }
    }

    private String writeDocs(List<String> docs) {
        try {
            return mapper.writeValueAsString(docs);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the expected documents.", e);
        }
    }
}
