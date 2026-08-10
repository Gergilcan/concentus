package com.concentus.service;

import com.concentus.model.SkillDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillServiceTest {

    private final SkillService service = new SkillService();

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final String SKILL_MD = """
            ---
            name: Brand Voice
            description: Writes replies in our brand voice.
            ---
            Always write like this…
            """;

    @Test
    void aZipWithASkillMdBecomesASkill() throws Exception {
        SkillDef skill = service.fromZip(zip(Map.of(
                "SKILL.md", SKILL_MD,
                "examples/reply.md", "ejemplo")));

        assertThat(skill.name()).isEqualTo("brand-voice");
        assertThat(skill.description()).isEqualTo("Writes replies in our brand voice.");
        assertThat(skill.files()).hasSize(2);
    }

    @Test
    void theUsualSingleTopFolderIsStripped() throws Exception {
        // Zips made by right-click-compress wrap everything in one folder; the CLI looks for
        // <skill>/SKILL.md at the root, so that wrapper must go.
        SkillDef skill = service.fromZip(zip(Map.of(
                "my-skill/SKILL.md", SKILL_MD,
                "my-skill/notes.md", "n")));

        assertThat(skill.files()).extracting(SkillDef.SkillFile::path)
                .containsExactlyInAnyOrder("SKILL.md", "notes.md");
    }

    @Test
    void aZipWithoutSkillMdIsRefusedWithTheReason() {
        assertThatThrownBy(() -> service.fromZip(zip(Map.of("readme.md", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void escapingPathsAreRefusedByName() {
        assertThatThrownBy(() -> service.fromZip(zip(Map.of(
                "SKILL.md", SKILL_MD,
                "../outside.txt", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void materialiseWritesUnderTheWorkspaceSkillsFolder(@TempDir Path temp) throws Exception {
        SkillDef skill = service.fromZip(zip(Map.of("SKILL.md", SKILL_MD, "extra/a.txt", "hola")));

        service.materialise(temp, List.of(skill));

        assertThat(Files.readString(temp.resolve(".claude/skills/brand-voice/SKILL.md")))
                .contains("Brand Voice");
        assertThat(Files.readString(temp.resolve(".claude/skills/brand-voice/extra/a.txt")))
                .isEqualTo("hola");
    }

    @Test
    void aStoredSkillWithAnEscapingPathCannotWriteOutside(@TempDir Path temp) {
        // Defence in depth: rows can be written by other tools; a stored skill must not become
        // an installer either.
        SkillDef evil = new SkillDef("s1", "evil", "",
                List.of(new SkillDef.SkillFile("../../boom.txt",
                        java.util.Base64.getEncoder().encodeToString("x".getBytes()))));

        assertThatThrownBy(() -> service.materialise(temp, List.of(evil)))
                .hasMessageContaining("escaping path");
        assertThat(Files.exists(temp.getParent().resolve("boom.txt"))).isFalse();
    }
}
