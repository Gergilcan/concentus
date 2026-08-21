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
 *
 * <p><b>The semantic branch is a scan, and that is a measurement rather than a hope.</b> Every
 * embedding of a base lives in one contiguous array, normalised when it is cached, so scoring is a
 * dot product over memory the processor can stream. On a 50,000-chunk base that is tens of
 * milliseconds; the same data as fifty thousand separate arrays cost 213 ms, which is what made an
 * approximate index look necessary. It was the layout, not the algorithm.
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
        return search(baseId, query, precomputed, topK, true);
    }

    /**
     * @param rerank whether the cross-encoder may reorder the result. Only the evaluation harness
     *               passes false, and only so a person can see what the reranker is actually worth
     *               on their own documents before deciding to keep 282 MB of it on disk. Retrieval
     *               itself always wants it when it is there.
     */
    public List<Hit> search(String baseId, String query, float[] precomputed, int topK, boolean rerank) {
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

            List<Ranked> ordered = rerank ? rerank(query, fused, span) : fused;
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
        CachedBase base = loadBase(baseId);
        if (base.chunks().isEmpty() || base.dims() == 0) return List.of();

        float[] queryVector = precomputed;
        if (queryVector == null) queryVector = embedQuery(query);
        if (queryVector == null || queryVector.length != base.dims()) return List.of();

        // The query is normalised once here so the inner loop is a plain dot product: the stored
        // vectors were normalised when the base was cached, and a cosine over two unit vectors is
        // their dot product. That removes two accumulations and a square root per chunk — two
        // thirds of the arithmetic — from the one loop that runs fifty thousand times.
        float[] q = normalised(queryVector.clone());

        int dims = base.dims();
        float[] all = base.vectors();
        boolean[] present = base.hasVector();
        List<CachedChunk> chunks = base.chunks();

        // Only the best BRANCH_CANDIDATES are ever used, so only they are ever built. Scoring
        // fifty thousand chunks into fifty thousand objects and sorting all of them, to keep
        // fifty, was most of what a search cost: the arithmetic is nineteen million multiply-adds,
        // the allocation and the sort were the rest. A running floor does the same job in one
        // pass, with no allocation in the loop at all.
        int wanted = BRANCH_CANDIDATES;
        double[] bestScore = new double[wanted];
        int[] bestIndex = new int[wanted];
        java.util.Arrays.fill(bestScore, Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(bestIndex, -1);
        double floor = Double.NEGATIVE_INFINITY;
        int held = 0;

        for (int i = 0; i < chunks.size(); i++) {
            if (!present[i]) continue;
            int offset = i * dims;
            // Accumulated in float, not double. Widening each product to add it into a double
            // accumulator stops the JIT vectorising the one loop that runs nineteen million times;
            // over 384 unit-normalised terms the float error is around 1e-5, which is four orders
            // of magnitude below any ranking decision this feeds.
            float dot = 0;
            for (int d = 0; d < dims; d++) dot += q[d] * all[offset + d];
            if (held == wanted && dot <= floor) continue;

            // Insertion into a short sorted run. At fifty entries this beats a heap: it is
            // branch-predictable, allocates nothing, and after the first few hundred chunks the
            // floor rejects almost everything before the insert is reached at all.
            int at = held < wanted ? held : wanted - 1;
            while (at > 0 && bestScore[at - 1] < dot) {
                bestScore[at] = bestScore[at - 1];
                bestIndex[at] = bestIndex[at - 1];
                at--;
            }
            bestScore[at] = dot;
            bestIndex[at] = i;
            if (held < wanted) held++;
            floor = bestScore[held - 1];
        }

        List<Ranked> out = new ArrayList<>(held);
        for (int r = 0; r < held; r++) {
            CachedChunk row = chunks.get(bestIndex[r]);
            out.add(new Ranked(row.doc(), row.seq(), row.content(), r + 1, bestScore[r]));
        }
        return out;
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
            // The query is built once and joined in, rather than spelled out twice — once to
            // filter and once to rank. Parsing and constructing a tsquery four times per search
            // is measurable on a large base, and this is the branch that dominates a search
            // there: ranking cannot use the index, so every matching row is scored.
            List<Ranked> rows = jdbc.query("""
                    with q as (select to_tsquery('spanish', ?) || to_tsquery('english', ?) as tsq)
                    select doc_name, seq, content, ts_rank_cd(lexeme, q.tsq) as rank
                      from knowledge_chunks, q
                     where base_id = ? and lexeme @@ q.tsq
                     order by rank desc
                     limit ?
                    """,
                    (rs, i) -> new Ranked(rs.getString("doc_name"), rs.getInt("seq"),
                            rs.getString("content"), i + 1, rs.getDouble("rank")),
                    terms, terms, baseId, BRANCH_CANDIDATES);
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

    private record CachedChunk(String doc, int seq, String content) {
    }

    /**
     * A base's chunks, with every embedding in <b>one</b> contiguous array.
     *
     * <p>Fifty thousand separate {@code float[384]} objects is fifty thousand array headers, fifty
     * thousand pointers to chase, and a scan that misses cache on every chunk. Measured on a
     * 50,000-chunk base, that layout cost 213 ms per search — while the arithmetic itself is
     * nineteen million multiply-adds, which contiguous memory does in a fraction of that. The
     * bottleneck was never the algorithm; an approximate index would have been the wrong answer to
     * a question about pointer chasing.
     *
     * @param vectors  {@code dims} floats per chunk, chunk {@code i} at offset {@code i * dims},
     *                 already L2-normalised so scoring is a dot product
     * @param hasVector whether chunk {@code i} was embedded at all — a base can hold both kinds
     */
    private record CachedBase(String stamp, List<CachedChunk> chunks, float[] vectors,
                              boolean[] hasVector, int dims) {
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
    private CachedBase loadBase(String baseId) {
        String stamp = stampOf(baseId);
        CachedBase cached = baseCache.get(baseId);
        if (cached != null && cached.stamp().equals(stamp)) return cached;

        record Row(String doc, int seq, String content, float[] vector) {
        }
        List<Row> rows = jdbc.query(
                "select doc_name, seq, content, embedding from knowledge_chunks where base_id = ? order by doc_name, seq",
                (rs, i) -> {
                    String embedding = rs.getString("embedding");
                    return new Row(rs.getString("doc_name"), rs.getInt("seq"),
                            rs.getString("content"), embedding == null ? null : fromJson(embedding));
                },
                baseId);

        int dims = rows.stream().filter(r -> r.vector() != null).findFirst()
                .map(r -> r.vector().length).orElse(0);
        List<CachedChunk> chunks = new ArrayList<>(rows.size());
        boolean[] hasVector = new boolean[rows.size()];
        float[] all = new float[dims == 0 ? 0 : rows.size() * dims];
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            chunks.add(new CachedChunk(row.doc(), row.seq(), row.content()));
            // A vector of a different length is a chunk embedded by a different model — kept as a
            // chunk, skipped by the semantic branch, still found lexically. Dropping it would make
            // a document silently unfindable after somebody changed models.
            if (row.vector() == null || dims == 0 || row.vector().length != dims) continue;
            hasVector[i] = true;
            System.arraycopy(normalised(row.vector()), 0, all, i * dims, dims);
        }

        CachedBase base = new CachedBase(stamp, chunks, all, hasVector, dims);
        baseCache.put(baseId, base);
        return base;
    }

    /**
     * The base's freshness, cached for a moment.
     *
     * <p>A run with three knowledge blocks asks three times within a second, and each ask is a
     * count over every row of the base — 32 ms on a 50,000-chunk base, spent proving that nothing
     * changed since the last time it proved that. One second of staleness is invisible to a person
     * and to an agent; a document uploaded now is retrievable on the next search either way.
     */
    private String stampOf(String baseId) {
        long now = System.currentTimeMillis();
        Stamp seen = stampCache.get(baseId);
        if (seen != null && now - seen.at() < STAMP_TTL_MILLIS) return seen.value();
        String value = jdbc.queryForObject(
                "select count(*) || ':' || coalesce(max(created_at), 0) from knowledge_chunks where base_id = ?",
                String.class, baseId);
        stampCache.put(baseId, new Stamp(value == null ? "" : value, now));
        return value == null ? "" : value;
    }

    private record Stamp(String value, long at) {
    }

    private static final long STAMP_TTL_MILLIS = 1000;

    private final ConcurrentHashMap<String, Stamp> stampCache = new ConcurrentHashMap<>();

    /** In place, and returned for convenience. A zero vector is left alone rather than dividing by 0. */
    private static float[] normalised(float[] v) {
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        }
        return v;
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

    private float[] fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<float[]>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
