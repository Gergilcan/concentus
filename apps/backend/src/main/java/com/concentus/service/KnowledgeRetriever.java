package com.concentus.service;

import com.concentus.llm.BuiltInEmbedder;
import com.concentus.llm.BuiltInReranker;
import com.concentus.llm.LocalModelClient;
import com.concentus.telemetry.Telemetry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finding the passages that answer a question, rather than the ones that resemble it.
 *
 * <p>Two searches run over every query and their results are fused, because each is blind where
 * the other sees. A vector search cannot pick out an exact token — a document reference, an error
 * code, a filename sits in embedding space beside all of its neighbours, so asking for a policy by
 * its number returns twelve other policies. A lexical search cannot match "what happens when
 * somebody leaves" against a document titled <em>Offboarding</em>, because they share no words.
 * Running both and fusing them is not hedging; it is the only way to answer both questions.
 *
 * <p>Fusion is by <b>rank, never by score</b>. A cosine similarity and a {@code ts_rank_cd} are
 * not commensurable quantities, and weighting them against each other would mean inventing an
 * exchange rate and then tuning it forever. Reciprocal Rank Fusion asks only "how near the top of
 * its own list did each branch put this?", which is comparable by construction.
 *
 * <p>A cross-encoder then reorders the survivors when one has been downloaded. It reads query and
 * passage <em>together</em>, which is what lets it notice that a passage describes the right
 * process for the wrong department — a distinction two separately-computed embeddings cannot
 * represent.
 *
 * <p><b>Every stage is optional except the query.</b> No embeddings: lexical alone, which is
 * strictly better than the word-overlap fallback this replaced. No reranker: the fused order. No
 * lexemes yet: semantic alone. Nothing here can leave a base unable to answer.
 */
@Service
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    /** Candidates drawn from each branch before fusion. */
    private static final int BRANCH_CANDIDATES = 50;

    /**
     * RRF's damping constant, 60 as in the paper that introduced it. It decides how quickly the
     * contribution of a rank decays: large enough that ranks 1 and 2 are not wildly apart, small
     * enough that rank 50 barely counts. Nothing here is tuned to it, so it stays where the
     * literature put it rather than becoming a knob nobody could evaluate.
     */
    private static final int RRF_K = 60;

    /** Tokens fed to the lexical query. A long prompt past this contributes noise, not recall. */
    private static final int MAX_QUERY_TERMS = 40;

    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private final JdbcTemplate jdbc;
    private final LocalModelClient models;
    private final BuiltInEmbedder builtIn;
    private final BuiltInReranker reranker;
    private final ObjectMapper mapper;
    private final Telemetry telemetry;
    private final String embeddingModel;

    public KnowledgeRetriever(JdbcTemplate jdbc, LocalModelClient models, BuiltInEmbedder builtIn,
                              BuiltInReranker reranker, ObjectMapper mapper, Telemetry telemetry,
                              @Value("${local-model.embedding-model:bge-m3}") String embeddingModel) {
        this.jdbc = jdbc;
        this.models = models;
        this.builtIn = builtIn;
        this.reranker = reranker;
        this.mapper = mapper;
        this.telemetry = telemetry;
        this.embeddingModel = embeddingModel;
    }

    /**
     * One retrieved passage.
     *
     * @param score the fused rank score, or the reranker's when one ran. Comparable within a
     *              result set and meaningless across them, which is all any caller needs
     */
    public record Hit(String docName, int seq, String content, double score) {
    }

    public List<Hit> search(String baseId, String query, int topK) {
        return search(baseId, query, null, topK);
    }

    /**
     * @param precomputed the query's vector from {@link #embedQuery}, so a run with three knowledge
     *                    nodes embeds its prompt once rather than three times; null re-embeds here
     */
    public List<Hit> search(String baseId, String query, float[] precomputed, int topK) {
        int k = Math.max(1, Math.min(topK, 20));
        if (query == null || query.isBlank()) return List.of();

        try (Telemetry.Scope span = telemetry.start(Telemetry.SPAN_RETRIEVAL)) {
            span.tag(Telemetry.ATTR_KNOWLEDGE_BASE, baseId).tag(Telemetry.ATTR_RETRIEVAL_K, k);

            List<Ranked> semantic = semanticBranch(baseId, query, precomputed);
            List<Ranked> lexical = lexicalBranch(baseId, query);
            span.tag(Telemetry.ATTR_RETRIEVAL_SEMANTIC, semantic.size())
                    .tag(Telemetry.ATTR_RETRIEVAL_LEXICAL, lexical.size());

            List<Ranked> fused = fuse(List.of(semantic, lexical));
            if (fused.isEmpty()) return List.of();

            List<Ranked> ordered = rerank(query, fused, span);
            return ordered.stream().limit(k)
                    .map(r -> new Hit(r.doc(), r.seq(), r.content(), r.score()))
                    .toList();
        }
    }

    /** The query's vector, or null when nothing can embed. For callers searching several bases. */
    public float[] embedQuery(String query) {
        List<float[]> embedded = tryEmbed(List.of(query), true);
        return embedded == null || embedded.isEmpty() ? null : embedded.get(0);
    }

    /** Embeds passages for ingestion, or null when nothing can. Never throws. */
    public List<float[]> embedPassages(List<String> passages) {
        return tryEmbed(passages, false);
    }

    /** Which model would embed, for the status line — null when none would. */
    public String embeddingModelName() {
        if (builtIn.isReady()) return "built-in " + BuiltInEmbedder.MODEL_NAME;
        return models.isConfigured() ? embeddingModel : null;
    }

    /** Whether a cross-encoder is available to reorder results, for the same status line. */
    public boolean reranking() {
        return reranker.isReady();
    }

    // ------------------------------------------------------------------ branches

    /** A candidate with the position its own branch gave it. Position, not score — see the class doc. */
    record Ranked(String doc, int seq, String content, int rank, double score) {
        Key key() {
            return new Key(doc, seq);
        }
    }

    /** Identity of a chunk across the two branches. A record, so no separator has to be safe. */
    record Key(String doc, int seq) {
    }

    private List<Ranked> semanticBranch(String baseId, String query, float[] precomputed) {
        List<CachedChunk> rows = loadBase(baseId);
        if (rows.isEmpty()) return List.of();

        float[] queryVector = precomputed;
        if (queryVector == null && rows.stream().anyMatch(r -> r.vector() != null)) {
            queryVector = embedQuery(query);
        }
        if (queryVector == null) return List.of();

        final float[] vector = queryVector;
        List<Ranked> scored = new ArrayList<>();
        for (CachedChunk row : rows) {
            if (row.vector() == null || row.vector().length != vector.length) continue;
            scored.add(new Ranked(row.doc(), row.seq(), row.content(), 0, cosine(vector, row.vector())));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return numbered(scored);
    }

    /**
     * The lexical branch, resolved in SQL against the GIN index.
     *
     * <p>Terms are ORed rather than ANDed, and that is not a detail: the preload path searches with
     * the run's whole prompt, and {@code plainto_tsquery} would AND three hundred words together
     * into a query matching nothing at all. {@code ts_rank_cd} then does the work ANDing was
     * pretending to do — the more of the query's terms a passage carries, and the closer together,
     * the higher it ranks.
     *
     * <p>The query is built from word tokens only, so it cannot carry tsquery operators into
     * {@code to_tsquery}: not merely an injection concern but a correctness one, since a stray
     * {@code !} would silently invert a clause.
     */
    private List<Ranked> lexicalBranch(String baseId, String query) {
        String terms = WORDS.matcher(query.toLowerCase(Locale.ROOT)).results()
                .map(m -> m.group())
                .distinct()
                .limit(MAX_QUERY_TERMS)
                .collect(Collectors.joining(" | "));
        if (terms.isBlank()) return List.of();

        try {
            List<Ranked> rows = jdbc.query("""
                    select doc_name, seq, content,
                           ts_rank_cd(lexeme, to_tsquery('spanish', ?) || to_tsquery('english', ?)) as rank
                      from knowledge_chunks
                     where base_id = ?
                       and lexeme @@ (to_tsquery('spanish', ?) || to_tsquery('english', ?))
                     order by rank desc
                     limit ?
                    """,
                    (rs, i) -> new Ranked(rs.getString("doc_name"), rs.getInt("seq"),
                            rs.getString("content"), i + 1, rs.getDouble("rank")),
                    terms, terms, baseId, terms, terms, BRANCH_CANDIDATES);
            return rows;
        } catch (RuntimeException e) {
            // A database that has not run V12, or an external one where the column is missing.
            // Semantic search still works, so this is a degraded search rather than a failed one.
            log.debug("Lexical branch unavailable for base {}: {}", baseId, e.getMessage());
            return List.of();
        }
    }

    private static List<Ranked> numbered(List<Ranked> scored) {
        List<Ranked> out = new ArrayList<>(Math.min(scored.size(), BRANCH_CANDIDATES));
        for (int i = 0; i < scored.size() && i < BRANCH_CANDIDATES; i++) {
            Ranked r = scored.get(i);
            out.add(new Ranked(r.doc(), r.seq(), r.content(), i + 1, r.score()));
        }
        return out;
    }

    /**
     * Reciprocal Rank Fusion: {@code score = Σ 1/(k + rank)} over the branches that found it.
     *
     * <p>A passage found by only one branch still surfaces, which is the entire point — the two
     * branches disagree precisely on the cases the other one is blind to. Being found by both is
     * simply strong evidence, and the sum expresses that without anybody choosing a weight.
     */
    static List<Ranked> fuse(List<List<Ranked>> branches) {
        Map<Key, Ranked> byKey = new LinkedHashMap<>();
        Map<Key, Double> scores = new HashMap<>();
        for (List<Ranked> branch : branches) {
            for (Ranked r : branch) {
                byKey.putIfAbsent(r.key(), r);
                scores.merge(r.key(), 1.0 / (RRF_K + r.rank()), Double::sum);
            }
        }
        List<Ranked> fused = byKey.values().stream()
                .map(r -> new Ranked(r.doc(), r.seq(), r.content(), 0, scores.get(r.key())))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .collect(Collectors.toList());
        return numbered(fused);
    }

    /** Reorders with the cross-encoder when one is downloaded; returns the input order otherwise. */
    private List<Ranked> rerank(String query, List<Ranked> fused, Telemetry.Scope span) {
        if (!reranker.isReady()) {
            span.tag(Telemetry.ATTR_RETRIEVAL_RERANKED, "no");
            return fused;
        }
        List<Ranked> candidates = fused.size() > BuiltInReranker.MAX_CANDIDATES
                ? fused.subList(0, BuiltInReranker.MAX_CANDIDATES)
                : fused;
        try {
            float[] scores = reranker.score(query, candidates.stream().map(Ranked::content).toList());
            List<Ranked> reranked = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                Ranked r = candidates.get(i);
                reranked.add(new Ranked(r.doc(), r.seq(), r.content(), 0, scores[i]));
            }
            reranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            span.tag(Telemetry.ATTR_RETRIEVAL_RERANKED, "yes");
            return reranked;
        } catch (RuntimeException e) {
            // The fused order is a good answer. Losing it because an optional model misbehaved
            // would turn an optional improvement into a single point of failure.
            log.warn("Reranking failed ({}); keeping the fused order.", e.getMessage());
            span.tag(Telemetry.ATTR_RETRIEVAL_RERANKED, "failed");
            return fused;
        }
    }

    // ------------------------------------------------------------------ internals

    private record CachedChunk(String doc, int seq, String content, float[] vector) {
    }

    private record CachedBase(String stamp, List<CachedChunk> chunks) {
    }

    private final ConcurrentHashMap<String, CachedBase> baseCache = new ConcurrentHashMap<>();

    /**
     * The base's chunks with their vectors already parsed.
     *
     * <p>Reading the whole base and re-parsing every embedding on every query meant, for a
     * 10k-chunk base, some 15 MB of text and four million float parses per search, repeated per
     * knowledge node per run.
     *
     * <p>Freshness is a stamp, not explicit invalidation: one cheap {@code count + max(created_at)}
     * per search. Chosen over invalidating from ingest because an external database can be written
     * by another instance entirely — a stamp notices that; local hooks never would.
     */
    private List<CachedChunk> loadBase(String baseId) {
        String stamp = jdbc.queryForObject(
                "select count(*) || ':' || coalesce(max(created_at), 0) from knowledge_chunks where base_id = ?",
                String.class, baseId);
        CachedBase cached = baseCache.get(baseId);
        if (cached != null && cached.stamp().equals(stamp)) return cached.chunks();

        List<CachedChunk> rows = jdbc.query(
                "select doc_name, seq, content, embedding from knowledge_chunks where base_id = ?",
                (rs, i) -> {
                    String embedding = rs.getString("embedding");
                    return new CachedChunk(rs.getString("doc_name"), rs.getInt("seq"),
                            rs.getString("content"), embedding == null ? null : fromJson(embedding));
                },
                baseId);
        baseCache.put(baseId, new CachedBase(stamp, rows));
        return rows;
    }

    /**
     * The embeddings, or null when nothing can produce them — never an exception.
     *
     * <p>The built-in model wins when ready: it is the one the user explicitly downloaded, it runs
     * in-process, and it needs no server to be up. Ollama (or any OpenAI-shaped server) remains the
     * alternative for anyone wanting a larger model such as bge-m3.
     */
    private List<float[]> tryEmbed(List<String> texts, boolean queries) {
        if (builtIn.isReady()) {
            try {
                return builtIn.embed(texts, queries);
            } catch (RuntimeException e) {
                log.warn("Built-in embedding failed ({}); trying the model server.", e.getMessage());
            }
        }
        if (!models.isConfigured()) return null;
        try {
            return models.embed(embeddingModel, texts);
        } catch (RuntimeException e) {
            log.warn("Embedding unavailable ({}); ranking lexically.", e.getMessage());
            return null;
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private float[] fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<float[]>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
