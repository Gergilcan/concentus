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
import java.util.Optional;

/** Knowledge bases: named document collections agents retrieve from. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeStore store;
    private final KnowledgeService service;
    private final com.concentus.llm.BuiltInEmbedder embedder;

    public KnowledgeController(KnowledgeStore store, KnowledgeService service,
                               com.concentus.llm.BuiltInEmbedder embedder) {
        this.store = store;
        this.service = service;
        this.embedder = embedder;
    }

    /**
     * The built-in embedding model: download it, drop it, see how far a download has got.
     *
     * <p>Polled while downloading rather than streamed — 130 MB behind a frozen button is
     * indistinguishable from a hang, and a poll needs no new transport.
     */
    @GetMapping("/embedder")
    public Map<String, Object> embedderStatus() {
        return Map.of(
                "state", embedder.state().name(),
                "percent", embedder.progressPercent(),
                "error", embedder.error(),
                "sizeMb", 130,
                "model", "multilingual-e5-small");
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

    @GetMapping
    public List<KnowledgeDef> list() {
        return store.list();
    }

    /** Semantic vs word-overlap, and exactly which piece is missing when it is not semantic. */
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

    /** Try a query before wiring the base into a flow — a bad ranking should cost a click here. */
    @PostMapping("/{id}/search")
    public List<KnowledgeService.Hit> search(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        requireBase(id);
        String query = String.valueOf(body.getOrDefault("query", "")).trim();
        if (query.isEmpty()) throw new IllegalArgumentException("A query is required.");
        int topK = body.get("topK") instanceof Number n ? n.intValue() : 5;
        return service.search(id, query, topK);
    }

    private void requireBase(String id) {
        Optional<KnowledgeDef> base = store.get(id);
        if (base.isEmpty()) throw new IllegalArgumentException("No knowledge base '" + id + "'.");
    }
}
