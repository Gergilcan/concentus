package com.concentus.web;

import com.concentus.model.SkillDef;
import com.concentus.service.SkillService;
import com.concentus.store.SkillStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Agent Skills: upload a zip with a SKILL.md, list what is installed, remove. */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillStore store;
    private final SkillService service;

    public SkillController(SkillStore store, SkillService service) {
        this.store = store;
        this.service = service;
    }

    /** The list omits file contents — the UI needs names and sizes, not megabytes of base64. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return store.list().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.id(),
                        "name", s.name(),
                        "description", s.description() == null ? "" : s.description(),
                        "fileCount", s.files().size()))
                .toList();
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        SkillDef parsed = service.fromZip(file.getBytes());
        // Same name replaces: re-uploading a corrected skill must not leave two versions competing
        // for the same folder name in every future run workspace.
        store.list().stream()
                .filter(existing -> existing.name().equals(parsed.name()))
                .forEach(existing -> store.delete(existing.id()));
        SkillDef saved = store.save(parsed);
        return Map.of("id", saved.id(), "name", saved.name(),
                "description", saved.description() == null ? "" : saved.description(),
                "fileCount", saved.files().size());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
