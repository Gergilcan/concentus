package com.concentus.web;

import com.concentus.model.SkillDef;
import com.concentus.service.SkillCatalogService;
import com.concentus.service.SkillService;
import com.concentus.store.SkillStore;
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

/** Agent Skills: upload a zip with a SKILL.md, list what is installed, remove. */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillStore store;
    private final SkillService service;
    private final SkillCatalogService catalog;

    public SkillController(SkillStore store, SkillService service, SkillCatalogService catalog) {
        this.store = store;
        this.service = service;
        this.catalog = catalog;
    }

    /** The list omits file contents — the UI needs names and sizes, not megabytes of base64. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return store.list().stream().map(SkillController::view).toList();
    }

    /** One skill as the UI sees it, so listing and installing cannot describe the same skill differently. */
    private static Map<String, Object> view(SkillDef skill) {
        return Map.of(
                "id", skill.id(),
                "name", skill.name(),
                "description", skill.description() == null ? "" : skill.description(),
                "fileCount", skill.files().size());
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return saveReplacing(service.fromZip(file.getBytes()));
    }

    /** Popular skill repositories on GitHub — the pinned collections plus the most-starred. */
    @GetMapping("/catalog")
    public List<SkillCatalogService.CatalogRepo> catalogRepos() {
        return catalog.popularRepos();
    }

    /** The installable skills inside one repository: every folder with a SKILL.md. */
    @GetMapping("/catalog/{owner}/{repo}")
    public List<SkillCatalogService.CatalogSkill> catalogSkills(
            @PathVariable String owner, @PathVariable String repo) {
        return catalog.skillsOf(owner, repo);
    }

    /** Installs one catalog skill — the same pipeline as an uploaded zip, minus the zipping. */
    @PostMapping("/catalog/install")
    public Map<String, Object> installFromCatalog(@RequestBody Map<String, String> body) {
        return saveReplacing(catalog.fetch(
                body.getOrDefault("owner", ""),
                body.getOrDefault("repo", ""),
                body.getOrDefault("path", "")));
    }

    private Map<String, Object> saveReplacing(SkillDef parsed) {
        return view(store.saveReplacingByName(parsed));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
