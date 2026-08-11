package com.concentus.service;

import com.concentus.model.SkillDef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GitHub catalog against a fake GitHub: canned search JSON, in-memory zipballs. What these
 * tests pin down is the part that must not drift — which folders count as skills, how hostile
 * paths are refused, and that the catalog installer obeys the same size rules as the zip upload.
 */
class SkillCatalogServiceTest {

    private final Map<String, byte[]> responses = new HashMap<>();
    private final SkillCatalogService catalog = new SkillCatalogService(
            new SkillService(),
            (url) -> {
                byte[] body = responses.get(url);
                if (body == null) throw new IOException("no canned answer for " + url);
                return body;
            });

    // ---- popularRepos ----

    @Test
    void mergesSearchResultsWithThePinnedRepositories() {
        responses.put(searchUrl(), json("""
                {"items":[
                  {"full_name":"someone/cool-skills","description":"community pack","stargazers_count":900},
                  {"full_name":"anthropics/skills","description":"official","stargazers_count":5000}
                ]}"""));

        List<SkillCatalogService.CatalogRepo> repos = catalog.popularRepos();

        assertThat(repos).extracting(SkillCatalogService.CatalogRepo::fullName)
                .contains("anthropics/skills", "someone/cool-skills", "obra/superpowers");
        // Sorted by stars, and the pinned flag survives being found by search too.
        assertThat(repos.get(0).fullName()).isEqualTo("anthropics/skills");
        assertThat(repos.get(0).pinned()).isTrue();
        assertThat(repos.get(0).stars()).isEqualTo(5000);
    }

    @Test
    void servesThePinnedRepositoriesWhenSearchIsRateLimited() {
        // No canned answers at all: every GitHub call fails, the way a rate-limited hour looks.
        List<SkillCatalogService.CatalogRepo> repos = catalog.popularRepos();

        assertThat(repos).extracting(SkillCatalogService.CatalogRepo::fullName)
                .containsExactlyInAnyOrderElementsOf(SkillCatalogService.PINNED);
    }

    // ---- skillsOf ----

    @Test
    void aFolderIsASkillExactlyWhenItCarriesASkillMd() {
        responses.put(zipballUrl("acme/skills"), zipball("acme-skills-abc123", Map.of(
                "pdf/SKILL.md", "---\nname: pdf-tools\ndescription: Reads PDFs\n---\nBody",
                "pdf/scripts/extract.py", "print('hi')",
                "notes/README.md", "not a skill",
                "deep/nested/thing/SKILL.md", "---\nname: nested\n---\n")));

        List<SkillCatalogService.CatalogSkill> skills = catalog.skillsOf("acme", "skills");

        assertThat(skills).extracting(SkillCatalogService.CatalogSkill::path)
                .containsExactly("deep/nested/thing", "pdf");
        assertThat(skills.get(1).name()).isEqualTo("pdf-tools");
        assertThat(skills.get(1).description()).isEqualTo("Reads PDFs");
    }

    @Test
    void aRepositoryThatIsItselfOneSkillListsItsRoot() {
        responses.put(zipballUrl("solo/skill"), zipball("solo-skill-abc", Map.of(
                "SKILL.md", "---\nname: solo\n---\n",
                "helper.md", "extra")));

        assertThat(catalog.skillsOf("solo", "skill"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.path()).isEmpty();
                    assertThat(s.name()).isEqualTo("solo");
                });
    }

    @Test
    void refusesHostileOwnerAndRepoNames() {
        assertThatThrownBy(() -> catalog.skillsOf("a/b", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.skillsOf("..", "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fetch ----

    @Test
    void fetchBuildsTheSkillWithPathsRelativeToItsFolder() {
        responses.put(zipballUrl("acme/skills"), zipball("acme-skills-abc123", Map.of(
                "pdf/SKILL.md", "---\nname: PDF Tools\ndescription: d\n---\n",
                "pdf/scripts/extract.py", "print('hi')",
                "other/SKILL.md", "---\nname: other\n---\n")));

        SkillDef skill = catalog.fetch("acme", "skills", "pdf");

        assertThat(skill.name()).isEqualTo("pdf-tools"); // sanitised like any upload
        assertThat(skill.files()).extracting(SkillDef.SkillFile::path)
                .containsExactlyInAnyOrder("SKILL.md", "scripts/extract.py");
    }

    @Test
    void fetchOfARootSkillSkipsTheRepositoryDotfiles() {
        responses.put(zipballUrl("solo/skill"), zipball("solo-skill-abc", Map.of(
                "SKILL.md", "---\nname: solo\n---\n",
                ".github/workflows/ci.yml", "secrets",
                ".gitignore", "node_modules")));

        SkillDef skill = catalog.fetch("solo", "skill", "");

        assertThat(skill.files()).extracting(SkillDef.SkillFile::path)
                .containsExactly("SKILL.md");
    }

    @Test
    void fetchRefusesEscapingPaths() {
        assertThatThrownBy(() -> catalog.fetch("acme", "skills", "../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.fetch("acme", "skills", "/absolute"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchEnforcesTheUploadSizeRules() {
        byte[] big = new byte[(int) SkillService.MAX_FILE_BYTES + 1];
        responses.put(zipballUrl("acme/skills"), zipballBytes("acme-skills-abc", Map.of(
                "fat/SKILL.md", "---\nname: fat\n---\n".getBytes(StandardCharsets.UTF_8),
                "fat/dataset.bin", big)));

        assertThatThrownBy(() -> catalog.fetch("acme", "skills", "fat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void aSkillMdWithoutANameFallsBackToItsFolderName() {
        responses.put(zipballUrl("acme/skills"), zipball("acme-skills-abc", Map.of(
                "tools/handy/SKILL.md", "no frontmatter at all")));

        assertThat(catalog.skillsOf("acme", "skills"))
                .singleElement()
                .satisfies(s -> assertThat(s.name()).isEqualTo("handy"));
    }

    // ---- helpers ----

    private static String searchUrl() {
        return "https://api.github.com/search/repositories?q=topic%3Aclaude-skills&sort=stars&order=desc&per_page=20";
    }

    private static String zipballUrl(String ownerRepo) {
        return "https://api.github.com/repos/" + ownerRepo + "/zipball";
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A zipball the way GitHub makes them: every entry under one `repo-sha/` wrapping folder. */
    private static byte[] zipball(String wrapper, Map<String, String> files) {
        Map<String, byte[]> bytes = new HashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return zipballBytes(wrapper, bytes);
    }

    private static byte[] zipballBytes(String wrapper, Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(wrapper + "/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

}
