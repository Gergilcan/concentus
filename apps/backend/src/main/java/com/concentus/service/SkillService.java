package com.concentus.service;

import com.concentus.model.SkillDef;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Packs and unpacks Agent Skills: a zip with a {@code SKILL.md} in, files on disk out.
 *
 * <p>The zip is untrusted input. Entry paths are normalised and anything that would escape the
 * skill's own directory — {@code ..}, absolute paths, drive letters — is refused by name, because
 * a skill that writes outside its folder is not a skill, it is an installer.
 */
@Service
public class SkillService {

    /** Generous for documents-plus-scripts; a bigger upload is almost certainly the wrong file. */
    private static final int MAX_FILES = 200;
    private static final long MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** Parses an uploaded zip into a SkillDef; throws IllegalArgumentException with the reason. */
    public SkillDef fromZip(byte[] zip) {
        List<SkillDef.SkillFile> files = new ArrayList<>();
        String skillMd = null;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = normalise(entry.getName());
                byte[] content = in.readNBytes((int) Math.min(MAX_FILE_BYTES + 1, Integer.MAX_VALUE));
                if (content.length > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("'" + path + "' exceeds "
                            + (MAX_FILE_BYTES / 1024 / 1024) + " MB — a skill carries documents "
                            + "and scripts, not datasets.");
                }
                total += content.length;
                if (total > MAX_TOTAL_BYTES || files.size() >= MAX_FILES) {
                    throw new IllegalArgumentException("The zip is larger than a skill should be ("
                            + MAX_FILES + " files / " + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB).");
                }
                files.add(new SkillDef.SkillFile(path, Base64.getEncoder().encodeToString(content)));
                if (path.equals("SKILL.md") || path.endsWith("/SKILL.md")) {
                    skillMd = new String(content, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a readable zip: " + e.getMessage());
        }
        if (skillMd == null) {
            throw new IllegalArgumentException(
                    "No SKILL.md found in the zip — that file is what makes it a skill.");
        }

        String name = frontmatter(skillMd, "name");
        String description = frontmatter(skillMd, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "SKILL.md has no `name:` in its frontmatter — Claude discovers skills by it.");
        }
        // If everything sits under one top folder (how zips are usually made), strip it so the
        // files land at the skill root — the CLI looks for <skill>/SKILL.md, not <skill>/x/SKILL.md.
        String prefix = commonTopFolder(files);
        if (!prefix.isEmpty()) {
            files = files.stream().map(f ->
                    new SkillDef.SkillFile(f.path().substring(prefix.length()), f.contentBase64()))
                    .toList();
        }
        return new SkillDef(null, sanitizeName(name), description, files);
    }

    /**
     * Writes the given skills into a run workspace's {@code .claude/skills/}.
     *
     * <p>Flow-level on purpose: Claude Code discovers skills per project, not per subagent, so the
     * union of every agent's assignment is materialised once and the per-agent part is guidance in
     * that agent's own instructions — the same steering-not-enforcement deal as context folders.
     */
    public void materialise(Path workdir, List<SkillDef> skills) throws IOException {
        for (SkillDef skill : skills) {
            Path root = workdir.resolve(".claude").resolve("skills").resolve(skill.name());
            for (SkillDef.SkillFile file : skill.files()) {
                Path target = root.resolve(file.path()).normalize();
                if (!target.startsWith(root)) {
                    // Defence in depth: fromZip already refused traversal, but rows can be written
                    // by other tools and a stored skill must not become an installer either.
                    throw new IOException("Skill '" + skill.name() + "' contains an escaping path: "
                            + file.path());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, Base64.getDecoder().decode(file.contentBase64()));
            }
        }
    }

    private static String normalise(String entryName) {
        String path = entryName.replace('\\', '/');
        if (path.startsWith("/") || path.contains("..") || path.matches("^[a-zA-Z]:.*")) {
            throw new IllegalArgumentException("Zip entry escapes the skill folder: " + entryName);
        }
        return path;
    }

    /** The one folder every path starts with, or "" when files sit at multiple roots. */
    private static String commonTopFolder(List<SkillDef.SkillFile> files) {
        String prefix = null;
        for (SkillDef.SkillFile f : files) {
            int slash = f.path().indexOf('/');
            if (slash < 0) return "";
            String top = f.path().substring(0, slash + 1);
            if (prefix == null) prefix = top;
            else if (!prefix.equals(top)) return "";
        }
        return prefix == null ? "" : prefix;
    }

    /** Frontmatter is two `---` fences with `key: value` lines between them. */
    private static String frontmatter(String skillMd, String key) {
        String[] lines = skillMd.split("\n");
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.equals("---")) {
                if (inside) break;
                inside = true;
                continue;
            }
            if (inside && trimmed.startsWith(key + ":")) {
                return trimmed.substring(key.length() + 1).strip();
            }
        }
        return "";
    }

    /** Directory-safe: the name becomes a folder under .claude/skills/. */
    private static String sanitizeName(String name) {
        return name.strip().toLowerCase().replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
    }
}
