package com.concentus.service;

import com.concentus.integration.content.AttachmentExtractionService;
import com.concentus.integration.content.AttachmentExtractionService.RawAttachment;
import com.concentus.llm.BuiltInEmbedder;
import com.concentus.llm.BuiltInReranker;
import com.concentus.llm.LocalModelClient;
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
import java.util.stream.Collectors;

/**
 * Getting documents into a knowledge base, and saying what the base can do once they are in.
 *
 * <p>Assembled from parts that already existed for other features, which is why it is small: the
 * mail pipeline's extractors turn PDF/Word/Excel/images into text, and the embedding runs in
 * process. This service adds the chunking in between. Finding things again is
 * {@link KnowledgeRetriever}'s job — ingestion and retrieval share a table and nothing else, and
 * keeping them in one class meant every change to ranking risked the code that writes documents.
 *
 * <p><b>Degradation is layered, never fatal.</b> No embedding model: chunks are stored without
 * vectors and retrieval ranks them lexically. An embedding model appears later: re-uploading a
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

    private final JdbcTemplate jdbc;
    private final AttachmentExtractionService extraction;
    private final WebPageFetcher web;
    private final KnowledgeRetriever retriever;
    private final LocalModelClient models;
    private final BuiltInEmbedder builtIn;
    private final BuiltInReranker reranker;
    private final ObjectMapper mapper;
    private final String embeddingModel;

    public KnowledgeService(JdbcTemplate jdbc, AttachmentExtractionService extraction,
                            WebPageFetcher web, KnowledgeRetriever retriever,
                            LocalModelClient models, BuiltInEmbedder builtIn,
                            BuiltInReranker reranker, ObjectMapper mapper,
                            @Value("${local-model.embedding-model:bge-m3}") String embeddingModel) {
        this.jdbc = jdbc;
        this.extraction = extraction;
        this.web = web;
        this.retriever = retriever;
        this.models = models;
        this.builtIn = builtIn;
        this.reranker = reranker;
        this.mapper = mapper;
        this.embeddingModel = embeddingModel;
    }

    /** What ingest did, told to the user rather than assumed: chunk count and how it will rank. */
    public record IngestResult(String docName, int chunks, boolean embedded, String detail) {
    }

    /**
     * @param sourceUrl where it came from, when it came from a page. Null for an upload — a file
     *                  somebody chose has no address to go back to, and pretending otherwise
     *                  would put a refresh button on a document that cannot be refreshed
     */
    public record DocInfo(String name, int chunks, boolean embedded, long createdAt,
                          String sourceUrl, String ingestedBy) {
    }

    /**
     * Reads a page and files it in the base under its own address.
     *
     * <p>A manual, a policy or a runbook lives on an internal wiki as often as it lives in a PDF,
     * and asking somebody to print one to PDF first is asking them to keep a second copy that
     * starts going out of date immediately. Named by its URL rather than its title: the URL is
     * what makes re-ingesting it a replacement rather than a duplicate, and a citation naming the
     * address is one the reader can open.
     */
    public IngestResult ingestUrl(String baseId, String url, String ingestedBy) {
        WebPageFetcher.Page page = web.fetch(url);
        String name = url.trim();
        return store(baseId, name, page.bytes(), name, ingestedBy);
    }

    /** Extracts, chunks, embeds when possible, and replaces any previous version of the document. */
    public IngestResult ingest(String baseId, String filename, byte[] bytes) {
        return ingest(baseId, filename, bytes, null);
    }

    public IngestResult ingest(String baseId, String filename, byte[] bytes, String ingestedBy) {
        return store(baseId, filename, bytes, null, ingestedBy);
    }

    private IngestResult store(String baseId, String filename, byte[] bytes, String sourceUrl,
                               String ingestedBy) {
        var extracted = extraction.extractAll(List.of(new RawAttachment(filename, bytes)));
        // The per-file text, not combinedText(): the combined form carries "=== attachment ==="
        // framing meant for a mail-triggered prompt, which here would pollute every chunk.
        String text = extracted.files().stream()
                .filter(f -> f.hasText())
                .map(f -> f.text())
                .collect(Collectors.joining("\n\n"));
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "No text could be extracted from '" + filename + "'. Supported: PDF, Word, "
                            + "Excel/CSV, plain text and HTML; images need OCR to be installed.");
        }

        List<String> chunks = chunk(text);
        boolean truncated = chunks.size() > MAX_CHUNKS_PER_DOC;
        if (truncated) chunks = chunks.subList(0, MAX_CHUNKS_PER_DOC);

        List<float[]> vectors = retriever.embedPassages(chunks);
        boolean embedded = vectors != null && vectors.size() == chunks.size();

        // Replace-then-insert, so re-uploading a corrected document does not leave stale chunks of
        // the old one answering queries beside the new.
        long now = System.currentTimeMillis();
        jdbc.update("delete from knowledge_chunks where base_id = ? and doc_name = ?", baseId, filename);
        // One batch, not one round trip per chunk: a document capped at MAX_CHUNKS_PER_DOC was
        // 2000 separate statements, and a folder import runs that back to back per file.
        List<String> finalChunks = chunks;
        List<float[]> finalVectors = embedded ? vectors : null;
        // The lexeme is computed in SQL rather than in Java because the query side computes its
        // half in SQL too, and the two only match if the same PostgreSQL configuration produced
        // both. Two stemmers agreeing today is not a property anybody could keep true.
        String detectedType = extracted.files().stream().findFirst()
                .map(f -> f.type() == null ? null : f.type().name())
                .orElse(null);
        jdbc.batchUpdate("""
                insert into knowledge_chunks (base_id, doc_name, seq, content, embedding, created_at,
                                              lexeme, source_url, content_type, ingested_by)
                values (?, ?, ?, ?, ?, ?, to_tsvector('spanish', ?) || to_tsvector('english', ?), ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                String content = finalChunks.get(i);
                ps.setString(1, baseId);
                ps.setString(2, filename);
                ps.setInt(3, i);
                ps.setString(4, content);
                ps.setString(5, finalVectors == null ? null : toJson(finalVectors.get(i)));
                ps.setLong(6, now);
                ps.setString(7, content);
                ps.setString(8, content);
                ps.setString(9, sourceUrl);
                ps.setString(10, detectedType);
                ps.setString(11, ingestedBy);
            }

            @Override
            public int getBatchSize() {
                return finalChunks.size();
            }
        });

        String model = retriever.embeddingModelName();
        String detail = (embedded
                ? "Indexed for semantic and keyword search (" + model + ")."
                : "Indexed for keyword search only — no embedding model is reachable, so nothing "
                        + "ranks by meaning yet. Re-upload once one is to upgrade.")
                + (truncated ? " Document was capped at " + MAX_CHUNKS_PER_DOC + " chunks." : "");
        log.info("Knowledge base {}: '{}' -> {} chunk(s), embedded={}", baseId, filename, chunks.size(), embedded);
        return new IngestResult(filename, chunks.size(), embedded, detail);
    }

    public List<DocInfo> documents(String baseId) {
        return jdbc.query("""
                select doc_name, count(*) as chunks,
                       bool_and(embedding is not null) as embedded,
                       max(created_at) as created_at,
                       max(source_url) as source_url,
                       max(ingested_by) as ingested_by
                  from knowledge_chunks where base_id = ?
                 group by doc_name order by doc_name
                """,
                (rs, i) -> new DocInfo(rs.getString("doc_name"), rs.getInt("chunks"),
                        rs.getBoolean("embedded"), rs.getLong("created_at"),
                        rs.getString("source_url"), rs.getString("ingested_by")),
                baseId);
    }

    /** Every document in this base that came from a page, so a refresh knows what to re-fetch. */
    public List<String> refreshableUrls(String baseId) {
        return jdbc.queryForList("""
                select distinct source_url from knowledge_chunks
                 where base_id = ? and source_url is not null
                 order by source_url
                """, String.class, baseId);
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
     * What the retrieval pipeline can actually do right now, checked rather than inferred.
     *
     * <p>Three questions, and the user is owed a different sentence for each: does anything embed,
     * does anything rerank, and — when the answer is no — which specific piece is missing. "Start
     * Ollama" and "the server does not serve that model" are different problems with different
     * fixes, and a shrug helps with neither.
     *
     * @param semantic whether meaning-based ranking is available at all; keyword search always is
     */
    public record EmbeddingStatus(boolean semantic, String detail) {
    }

    public EmbeddingStatus status() {
        EmbeddingStatus embedding = embeddingStatus();
        if (!reranker.isReady()) return embedding;
        return new EmbeddingStatus(embedding.semantic(),
                embedding.detail() + " Results are reordered by the "
                        + BuiltInReranker.MODEL_NAME + " reranker.");
    }

    private EmbeddingStatus embeddingStatus() {
        // NOT_DOWNLOADED falls through to the model-server checks below; every other state is
        // answered here, so the switch needs no do-nothing branch to say so.
        if (builtIn.state() != BuiltInEmbedder.State.NOT_DOWNLOADED) {
            return switch (builtIn.state()) {
                case READY -> new EmbeddingStatus(true,
                        "Keyword and semantic search, fused (" + BuiltInEmbedder.MODEL_NAME + ").");
                case DOWNLOADING -> new EmbeddingStatus(false,
                        "Keyword search is working. Downloading the built-in embedding model… "
                                + builtIn.progressPercent() + "%");
                default -> new EmbeddingStatus(false, "Keyword search is working, but the built-in "
                        + "embedding model failed to download: " + builtIn.error());
            };
        }
        if (!models.isConfigured()) {
            return new EmbeddingStatus(false,
                    "Keyword search only. Download the built-in model below to add semantic "
                            + "search — or install Ollama and pull " + embeddingModel + ".");
        }
        Set<String> served;
        try {
            served = models.listModels();
        } catch (RuntimeException e) {
            return new EmbeddingStatus(false,
                    "Keyword search only: the model server at " + models.baseUrl() + " is not "
                            + "answering. Start it — or download the built-in model below, which "
                            + "needs no server.");
        }
        boolean present = served.stream().anyMatch(m ->
                m.equalsIgnoreCase(embeddingModel)
                        || m.toLowerCase(Locale.ROOT).startsWith(embeddingModel.toLowerCase(Locale.ROOT) + ":"));
        if (!present) {
            return new EmbeddingStatus(false,
                    "Keyword search only: the model server answers but does not serve '"
                            + embeddingModel + "'. Run: ollama pull " + embeddingModel);
        }
        return new EmbeddingStatus(true, "Keyword and semantic search, fused ('" + embeddingModel + "').");
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

    private String toJson(float[] vector) {
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }
}
