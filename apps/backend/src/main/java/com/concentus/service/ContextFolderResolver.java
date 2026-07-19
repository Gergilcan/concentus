package com.concentus.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Validates the host folders a flow asks an agent to read.
 *
 * <p>A flow is editable over HTTP and can be triggered by a public webhook, so an unchecked folder
 * list is a read-anything primitive against the host ({@code /etc}, {@code ~/.ssh}). Every path is
 * therefore resolved against an explicit allowlist, {@code local.context-roots}. The allowlist is
 * <b>required</b>: with nothing configured no folder is accepted, so a deployment that never opts
 * in cannot expose its filesystem by accident.
 *
 * <p>Containment is checked after {@code toRealPath()}, which resolves {@code ..} and symlinks — a
 * symlink sitting inside an allowed root but pointing at {@code /etc} must not smuggle it back in.
 */
@Component
public class ContextFolderResolver {

    private final List<Path> roots;

    public ContextFolderResolver(@Value("${local.context-roots:}") String configuredRoots) {
        this.roots = parseRoots(configuredRoots);
    }

    /**
     * Roots are comma- or semicolon-separated. Deliberately <em>not</em> split on
     * {@code File.pathSeparator}: that is ":" on Unix, which would tear "C:\repos" in half for
     * anyone carrying a Windows-style path in their config.
     */
    private static List<Path> parseRoots(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String raw : configured.split("[,;]")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            out.add(canonical(Path.of(t).toAbsolutePath().normalize()));
        }
        return List.copyOf(out);
    }

    /** Real path where possible; a root that doesn't exist yet simply matches nothing. */
    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    public boolean configured() {
        return !roots.isEmpty();
    }

    public List<Path> roots() {
        return roots;
    }

    /** Why this folder may not be used, or null when it is allowed. */
    public String rejectionReason(String raw) {
        if (raw == null || raw.isBlank()) return "empty path";
        if (roots.isEmpty()) {
            return "no context roots are configured — set `local.context-roots` to the directories "
                    + "flows may read";
        }
        Path p;
        try {
            p = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "not a valid path";
        }
        if (!Files.isDirectory(p)) return "not an existing directory";

        Path real = canonical(p);
        for (Path root : roots) {
            if (real.startsWith(root)) return null;
        }
        return "outside the configured context roots (" + describeRoots() + ")";
    }

    /**
     * Resolves the folders an agent may read. A rejected entry is reported through
     * {@code onRejected} and dropped rather than failing the run: a half-configured flow is still
     * worth executing, and the reason shows up in the run console where it can be acted on.
     */
    public List<Path> resolve(List<String> requested, BiConsumer<String, String> onRejected) {
        List<Path> out = new ArrayList<>();
        if (requested == null) return out;
        for (String raw : requested) {
            String reason = rejectionReason(raw);
            if (reason != null) {
                onRejected.accept(raw, reason);
                continue;
            }
            Path resolved = Path.of(raw).toAbsolutePath().normalize();
            if (!out.contains(resolved)) out.add(resolved);
        }
        return out;
    }

    /**
     * Resolves a CLAUDE.md reference, accepting either the file itself or a folder containing one.
     * Returns null (having reported why) when it cannot be used.
     */
    public Path resolveClaudeMd(String raw, BiConsumer<String, String> onRejected) {
        if (raw == null || raw.isBlank()) return null;
        Path p = Path.of(raw).toAbsolutePath().normalize();
        Path file = Files.isDirectory(p) ? p.resolve("CLAUDE.md") : p;

        // Guard the containing directory, so one allowlist covers files and folders alike.
        Path dir = file.getParent();
        String reason = dir == null ? "not a valid path" : rejectionReason(dir.toString());
        if (reason != null) {
            onRejected.accept(raw, reason);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            onRejected.accept(raw, "no CLAUDE.md found there");
            return null;
        }
        return file;
    }

    private String describeRoots() {
        return roots.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
