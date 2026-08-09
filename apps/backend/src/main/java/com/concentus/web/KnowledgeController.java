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

    public KnowledgeController(KnowledgeStore store, KnowledgeService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeDef> list() {
        return store.list();
    }

    /** Semantic vs word-overlap, so the UI states which it is instead of the user guessing. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("semantic", service.semanticAvailable());
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

    @DeleteMapping("/{id}/documents/{docName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id, @PathVariable String docName) {
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
