package com.concentus.service;

import com.concentus.integration.content.AttachmentExtractionService;
import com.concentus.integration.content.AttachmentExtractionService.RawAttachment;
import com.concentus.llm.LocalModelClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ingesting documents into a knowledge base, and finding the passages relevant to a prompt.
 *
 * <p>Assembled from parts that already existed for other features, which is why it is small: the
 * mail pipeline's extractors turn PDF/Word/Excel/images into text, and the local model client's
 * embedding call ranks tool search. This service only adds the chunking in between.
 *
 * <p><b>Degradation is layered, never fatal.</b> No embedding server: chunks are stored without
 * vectors and retrieval ranks by word overlap. Embedding server appears later: re-uploading a
 * document re-embeds it. Nothing here can make an upload fail for reasons the user cannot see.
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /**
     * Chunk size in characters, with overlap so a sentence cut at a boundary still appears whole
     * in one of its two chunks. ~1500 chars is a few paragraphs: big enough to carry an idea,
     * small enough that a hit does not drag a page of noise into the prompt with it.
     */
    private static final int CHUNK_CHARS = 1500;
    private static final int CHUNK_OVERLAP = 200;
    /** Ingest ceiling per document, matching the attachment policy's spirit: bounded, stated. */
    private static final int MAX_CHUNKS_PER_DOC = 2000;

    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private final JdbcTemplate jdbc;
    private final AttachmentExtractionService extraction;
    private final LocalModelClient models;
    private final com.concentus.llm.BuiltInEmbedder builtIn;
    private final ObjectMapper mapper;
    private final String embeddingModel;

    public KnowledgeService(JdbcTemplate jdbc, AttachmentExtractionService extraction,
                            LocalModelClient models, com.concentus.llm.BuiltInEmbedder builtIn,
                            ObjectMapper mapper,
                            @Value("${local-model.embedding-model:bge-m3}") String embeddingModel) {
        this.jdbc = jdbc;
        this.extraction = extraction;
        this.models = models;
        this.builtIn = builtIn;
        this.mapper = mapper;
        this.embeddingModel = embeddingModel;
    }

    /** What ingest did, told to the user rather than assumed: chunk count and how it will rank. */
    public record IngestResult(String docName, int chunks, boolean embedded, String detail) {
    }

    public record DocInfo(String name, int chunks, boolean embedded, long createdAt) {
    }

    public record Hit(String docName, int seq, String content, double score) {
    }

    /** Extracts, chunks, embeds when possible, and replaces any previous version of the document. */
    public IngestResult ingest(String baseId, String filename, byte[] bytes) {
        var extracted = extraction.extractAll(List.of(new RawAttachment(filename, bytes)));
        // The per-file text, not combinedText(): the combined form carries "=== attachment ==="
        // framing meant for a mail-triggered prompt, which here would pollute every chunk.
        String text = extracted.files().stream()
                .filter(f -> f.hasText())
                .map(f -> f.text())
                .collect(Collectors.joining("\n\n"));
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "No text could be extracted from '" + filename + "'. Supported: PDF, Word, "
                            + "Excel/CSV, plain text and HTML; images need OCR to be installed.");
        }

        List<String> chunks = chunk(text);
        boolean truncated = chunks.size() > MAX_CHUNKS_PER_DOC;
        if (truncated) chunks = chunks.subList(0, MAX_CHUNKS_PER_DOC);

        Embeddings embedding = tryEmbed(chunks, false);
        boolean embedded = embedding != null;

        // Replace-then-insert, so re-uploading a corrected document does not leave stale chunks of
        // the old one answering queries beside the new.
        long now = System.currentTimeMillis();
        jdbc.update("delete from knowledge_chunks where base_id = ? and doc_name = ?", baseId, filename);
        for (int i = 0; i < chunks.size(); i++) {
            jdbc.update("""
                    insert into knowledge_chunks (base_id, doc_name, seq, content, embedding, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, baseId, filename, i, chunks.get(i),
                    embedded ? toJson(embedding.vectors().get(i)) : null, now);
        }

        String detail = (embedded
                ? "Indexed with semantic embeddings (" + embedding.model() + ")."
                : "Indexed without embeddings — the embedding model is not reachable, so retrieval "
                        + "ranks by word overlap. Re-upload once it is to upgrade.")
                + (truncated ? " Document was capped at " + MAX_CHUNKS_PER_DOC + " chunks." : "");
        log.info("Knowledge base {}: '{}' -> {} chunk(s), embedded={}", baseId, filename, chunks.size(), embedded);
        return new IngestResult(filename, chunks.size(), embedded, detail);
    }

    public List<DocInfo> documents(String baseId) {
        return jdbc.query("""
                select doc_name, count(*) as chunks,
                       bool_and(embedding is not null) as embedded,
                       max(created_at) as created_at
                  from knowledge_chunks where base_id = ?
                 group by doc_name order by doc_name
                """,
                (rs, i) -> new DocInfo(rs.getString("doc_name"), rs.getInt("chunks"),
                        rs.getBoolean("embedded"), rs.getLong("created_at")),
                baseId);
    }

    public boolean deleteDocument(String baseId, String docName) {
        return jdbc.update("delete from knowledge_chunks where base_id = ? and doc_name = ?",
                baseId, docName) > 0;
    }

    /**
     * Deletes every document stored under a folder, and reports how many there were.
     *
     * <p>The count is of documents, not chunks — that is the unit the user sees — and it is
     * gathered before the delete because afterwards there is nothing left to count.
     *
     * <p>The folder name goes into a LIKE pattern, so its wildcards are escaped: a folder called
     * {@code 100%_done} would otherwise match siblings it has no business deleting. Trailing
     * slashes are trimmed, and an empty prefix is refused rather than being allowed to expand into
     * "everything" through a {@code '/%'} pattern that matches every nested document.
     */
    public int deleteFolder(String baseId, String folder) {
        String prefix = folder == null ? "" : folder.replaceAll("/+$", "");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("A folder is required.");
        }
        String pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%";
        Integer docs = jdbc.queryForObject("""
                select count(distinct doc_name) from knowledge_chunks
                 where base_id = ? and doc_name like ? escape '\\'
                """, Integer.class, baseId, pattern);
        jdbc.update("delete from knowledge_chunks where base_id = ? and doc_name like ? escape '\\'",
                baseId, pattern);
        return docs == null ? 0 : docs;
    }

    /** Everything a deleted base would otherwise leave behind. */
    public void deleteAll(String baseId) {
        jdbc.update("delete from knowledge_chunks where base_id = ?", baseId);
    }

    /**
     * The chunks most relevant to a query.
     *
     * <p>A linear scan over the base, on purpose: a desktop knowledge base is thousands of chunks,
     * not millions, and a scan at that size costs single-digit milliseconds — while an ANN index
     * would tie the schema to pgvector, which an external database may not have. Chunks without
     * embeddings (or a query that cannot be embedded) fall back to word overlap, and both kinds
     * can coexist in one base.
     */
    public List<Hit> search(String baseId, String query, int topK) {
        int k = Math.max(1, Math.min(topK, 20));
        record Row(String doc, int seq, String content, String embedding) {}
        List<Row> rows = jdbc.query(
                "select doc_name, seq, content, embedding from knowledge_chunks where base_id = ?",
                (rs, i) -> new Row(rs.getString("doc_name"), rs.getInt("seq"),
                        rs.getString("content"), rs.getString("embedding")),
                baseId);
        if (rows.isEmpty()) return List.of();

        float[] queryVector = null;
        if (rows.stream().anyMatch(r -> r.embedding() != null)) {
            Embeddings embedded = tryEmbed(List.of(query), true);
            if (embedded != null && !embedded.vectors().isEmpty()) queryVector = embedded.vectors().get(0);
        }

        Set<String> queryWords = words(query);
        List<Hit> hits = new ArrayList<>(rows.size());
        for (Row row : rows) {
            double score;
            float[] vector = row.embedding() == null ? null : fromJson(row.embedding());
            if (queryVector != null && vector != null && vector.length == queryVector.length) {
                score = cosine(queryVector, vector);
            } else {
                score = overlap(queryWords, row.content());
            }
            hits.add(new Hit(row.doc(), row.seq(), row.content(), score));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(k, hits.size()));
    }

    /**
     * The embedding pipeline's actual state, checked rather than inferred.
     *
     * <p>Three things must hold for semantic ranking, and telling the user <em>which one</em> is
     * missing is the difference between "start Ollama" and a shrug: a server must be configured,
     * it must answer, and it must serve the embedding model.
     */
    public record EmbeddingStatus(boolean semantic, String detail) {
    }

    public EmbeddingStatus status() {
        switch (builtIn.state()) {
            case READY -> {
                return new EmbeddingStatus(true, "Semantic ranking with the built-in model (multilingual-e5-small).");
            }
            case DOWNLOADING -> {
                return new EmbeddingStatus(false, "Downloading the built-in embedding model… "
                        + builtIn.progressPercent() + "%");
            }
            case ERROR -> {
                return new EmbeddingStatus(false, "The built-in model download failed: "
                        + builtIn.error() + ". Try again, or use a model server.");
            }
            default -> { /* NOT_DOWNLOADED: fall through to the server checks */ }
        }
        if (!models.isConfigured()) {
            return new EmbeddingStatus(false,
                    "Download the built-in model below for semantic search — or install Ollama "
                            + "and pull " + embeddingModel + ".");
        }
        java.util.Set<String> served;
        try {
            served = models.listModels();
        } catch (RuntimeException e) {
            return new EmbeddingStatus(false,
                    "The model server at " + models.baseUrl() + " is not answering. Start it — "
                            + "or download the built-in model below, which needs no server.");
        }
        boolean present = served.stream().anyMatch(m ->
                m.equalsIgnoreCase(embeddingModel)
                        || m.toLowerCase(Locale.ROOT).startsWith(embeddingModel.toLowerCase(Locale.ROOT) + ":"));
        if (!present) {
            return new EmbeddingStatus(false,
                    "The model server answers but does not serve '" + embeddingModel
                            + "'. Run: ollama pull " + embeddingModel);
        }
        return new EmbeddingStatus(true, "Semantic ranking with '" + embeddingModel + "'.");
    }

    // ---------------------------------------------------------------- internals

    /** Splits on paragraph boundaries where possible, hard-wrapping only oversized paragraphs. */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (current.length() + p.length() + 2 > CHUNK_CHARS && current.length() > 0) {
                out.add(current.toString());
                // Overlap: carry the tail of the finished chunk into the next.
                String tail = current.substring(Math.max(0, current.length() - CHUNK_OVERLAP));
                current = new StringBuilder(tail).append('\n');
            }
            while (p.length() > CHUNK_CHARS) {
                current.append(p, 0, CHUNK_CHARS);
                out.add(current.toString());
                current = new StringBuilder();
                p = p.substring(CHUNK_CHARS - CHUNK_OVERLAP);
            }
            if (current.length() > 0) current.append('\n');
            current.append(p);
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    /**
     * The embeddings, or null when nothing can produce them — never an exception.
     *
     * <p>The built-in model wins when ready: it is the one the user explicitly downloaded, it runs
     * in-process, and it needs no server to be up. Ollama (or any OpenAI-shaped server) remains
     * the alternative for anyone wanting a larger model such as bge-m3.
     */
    /** The vectors and which model produced them, so the UI can name it instead of guessing. */
    private record Embeddings(List<float[]> vectors, String model) {
    }

    private Embeddings tryEmbed(List<String> texts, boolean queries) {
        if (builtIn.isReady()) {
            try {
                return new Embeddings(builtIn.embed(texts, queries), "built-in multilingual-e5-small");
            } catch (RuntimeException e) {
                log.warn("Built-in embedding failed ({}); trying the model server.", e.getMessage());
            }
        }
        if (!models.isConfigured()) return null;
        try {
            return new Embeddings(models.embed(embeddingModel, texts), embeddingModel);
        } catch (RuntimeException e) {
            log.warn("Embedding unavailable ({}); falling back to word overlap.", e.getMessage());
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

    private static Set<String> words(String text) {
        return WORDS.matcher(text.toLowerCase(Locale.ROOT)).results()
                .map(m -> m.group()).collect(Collectors.toSet());
    }

    private static double overlap(Set<String> queryWords, String content) {
        if (queryWords.isEmpty()) return 0;
        Set<String> contentWords = words(content);
        long matches = queryWords.stream().filter(contentWords::contains).count();
        return (double) matches / queryWords.size();
    }

    private String toJson(float[] vector) {
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception e) {
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
