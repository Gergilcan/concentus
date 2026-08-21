package com.concentus.web;

import com.concentus.model.KnowledgeDef;
import com.concentus.service.KnowledgeService;
import com.concentus.store.KnowledgeStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Knowledge bases: named document collections agents retrieve from. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeStore store;
    private final KnowledgeService service;
    private final com.concentus.service.KnowledgeRetriever retriever;
    private final com.concentus.llm.BuiltInEmbedder embedder;
    private final com.concentus.llm.BuiltInReranker reranker;
    private final com.concentus.integration.content.AttachmentExtractionService extraction;

    public KnowledgeController(KnowledgeStore store, KnowledgeService service,
                               com.concentus.service.KnowledgeRetriever retriever,
                               com.concentus.llm.BuiltInEmbedder embedder,
                               com.concentus.llm.BuiltInReranker reranker,
                               com.concentus.integration.content.AttachmentExtractionService extraction) {
        this.store = store;
        this.service = service;
        this.retriever = retriever;
        this.embedder = embedder;
        this.reranker = reranker;
        this.extraction = extraction;
    }

    /** What the picker may offer — derived from the extractors actually present and working. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("extensions", extraction.supportedExtensions());
    }

    /**
     * The built-in embedding model: download it, drop it, see how far a download has got.
     *
     * <p>Polled while downloading rather than streamed — 130 MB behind a frozen button is
     * indistinguishable from a hang, and a poll needs no new transport.
     */
    @GetMapping("/embedder")
    public Map<String, Object> embedderStatus() {
        // Carries overall semantic availability too, not only the built-in model's state: with
        // Ollama serving the embedding model, search IS semantic while the built-in model is
        // absent, and a panel reading only the local state would claim keyword-only wrongly.
        KnowledgeService.EmbeddingStatus overall = service.status();
        return Map.of(
                "state", embedder.state().name(),
                "percent", embedder.progressPercent(),
                "error", embedder.error(),
                "sizeMb", 130,
                "model", com.concentus.llm.BuiltInEmbedder.MODEL_NAME,
                "semantic", overall.semantic(),
                "detail", overall.detail());
    }

    @PostMapping("/embedder/download")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void downloadEmbedder() {
        embedder.download();
    }

    @DeleteMapping("/embedder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmbedder() throws IOException {
        embedder.delete();
    }

    /**
     * The reranker: the cross-encoder that reorders results by reading query and passage together.
     *
     * <p>Reported separately from the embedder rather than folded into one "models" state, because
     * they fail independently and the user's next action differs: without the embedder there is no
     * semantic search at all, while without this one search works and merely ranks less well.
     */
    @GetMapping("/reranker")
    public Map<String, Object> rerankerStatus() {
        return Map.of(
                "state", reranker.state().name(),
                "percent", reranker.progressPercent(),
                "error", reranker.error(),
                "sizeMb", com.concentus.llm.BuiltInReranker.SIZE_MB,
                "model", com.concentus.llm.BuiltInReranker.MODEL_NAME);
    }

    @PostMapping("/reranker/download")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void downloadReranker() {
        reranker.download();
    }

    @DeleteMapping("/reranker")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReranker() throws IOException {
        reranker.delete();
    }

    @GetMapping
    public List<KnowledgeDef> list() {
        return store.list();
    }

    /** What ranking is available, and exactly which piece is missing when it is not all of it. */
    @GetMapping("/status")
    public KnowledgeService.EmbeddingStatus status() {
        return service.status();
    }

    @PostMapping
    public KnowledgeDef save(@RequestBody KnowledgeDef def) {
        if (def.name() == null || def.name().isBlank()) {
            throw new IllegalArgumentException("A knowledge base needs a name.");
        }
        return store.save(def);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        // Chunks first: a base that fails to delete half-way must not strand orphaned text that
        // no UI lists any more.
        service.deleteAll(id);
        store.delete(id);
    }

    @GetMapping("/{id}/documents")
    public List<KnowledgeService.DocInfo> documents(@PathVariable String id) {
        requireBase(id);
        return service.documents(id);
    }

    @PostMapping("/{id}/documents")
    public KnowledgeService.IngestResult upload(@PathVariable String id,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        requireBase(id);
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "document";
        return service.ingest(id, name, file.getBytes());
    }

    /**
     * The document name rides in a query parameter, not the path: folder uploads keep their
     * relative path in the name ("manuals/intro.pdf"), and Tomcat rejects an encoded slash in a
     * path segment outright.
     */
    @DeleteMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id, @RequestParam("name") String docName) {
        service.deleteDocument(id, docName);
    }

    /** Deletes a folder and everything under it; returns how many documents went. */
    @DeleteMapping("/{id}/folders")
    public Map<String, Object> deleteFolder(@PathVariable String id, @RequestParam("path") String folder) {
        return Map.of("deleted", service.deleteFolder(id, folder));
    }

    /** Try a query before wiring the base into a flow — a bad ranking should cost a click here. */
    @PostMapping("/{id}/search")
    public List<com.concentus.service.KnowledgeRetriever.Hit> search(@PathVariable String id,
                                                                    @RequestBody Map<String, Object> body) {
        requireBase(id);
        String query = String.valueOf(body.getOrDefault("query", "")).trim();
        if (query.isEmpty()) throw new IllegalArgumentException("A query is required.");
        int topK = body.get("topK") instanceof Number n ? n.intValue() : 5;
        return retriever.search(id, query, topK);
    }

    private void requireBase(String id) {
        if (store.get(id).isEmpty()) {
            throw new IllegalArgumentException("No knowledge base '" + id + "'.");
        }
    }
}
