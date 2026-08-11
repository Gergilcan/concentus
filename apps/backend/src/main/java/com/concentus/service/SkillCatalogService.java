package com.concentus.service;

import com.concentus.model.SkillDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The skill catalog: popular Claude-skill repositories on GitHub, browsable and installable
 * without leaving the app.
 *
 * <p>Skills already have an ecosystem — collections on GitHub, ranked by the stars people gave
 * them — and what stood between a user and installing one was the same hunt the MCP catalog
 * removed for servers: find the repo, find the folder, zip it, upload it. This closes that loop:
 * search GitHub for the starred collections, read each repository's archive to see which folders
 * are skills (a folder is a skill exactly when it carries a {@code SKILL.md}), and install one by
 * streaming those files through the very same validation the zip upload uses.
 *
 * <p><b>How GitHub is read.</b> Two surfaces, chosen for their rate-limit behaviour. The search
 * and repo-metadata endpoints are the API proper — 60 unauthenticated requests an hour — so their
 * results are cached and every failure degrades to something useful (the pinned repositories are
 * served even when search is exhausted). Repository CONTENT never touches those limits: it comes
 * as one zipball per repository, which also makes listing a repo's skills one request instead of
 * one per folder.
 *
 * <p><b>Trust.</b> A skill is instructions an agent will follow, so installing one is a trust
 * decision the user makes explicitly, exactly as with a zip they uploaded — the catalog only
 * removes the packaging steps, not the choice. What this service does guarantee is mechanical
 * safety: the archive's paths cannot escape the skill's folder, the upload size caps apply
 * unchanged, and only github.com hosts are ever contacted, with owner and repo names validated
 * against the characters GitHub itself allows.
 */
@Service
public class SkillCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);

    /**
     * Always present, even when the search API is rate-limited or the topic is missing: the
     * ecosystem's canonical collections. Anthropic's own repository famously carries no
     * {@code claude-skills} topic, which is exactly how a pure search would lose the most
     * authoritative entry in the catalog.
     */
    static final List<String> PINNED = List.of("anthropics/skills", "obra/superpowers");

    private static final String SEARCH_URL =
            "https://api.github.com/search/repositories?q=topic%3Aclaude-skills&sort=stars&order=desc&per_page=20";
    private static final Pattern NAME_PART = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    /** A repository archive larger than this is not a skill collection; stop downloading. */
    private static final long MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int MAX_REPOS = 15;

    public record CatalogRepo(String fullName, String description, int stars, boolean pinned) {}
    public record CatalogSkill(String path, String name, String description) {}

    /** The one seam the tests replace: bytes for a URL, or an IOException with the story. */
    interface Transport {
        byte[] get(String url) throws IOException;
    }

    private record Cached<T>(T value, Instant at) {
        boolean fresh() { return Duration.between(at, Instant.now()).compareTo(CACHE_TTL) < 0; }
    }

    private final Transport transport;
    private final SkillService skills;
    private final ObjectMapper json = new ObjectMapper();

    private volatile Cached<List<CatalogRepo>> repoCache;
    private final Map<String, Cached<List<CatalogSkill>>> skillCache = new ConcurrentHashMap<>();

    @Autowired
    public SkillCatalogService(SkillService skills) {
        this(skills, defaultTransport());
    }

    /** Visible for tests, which serve canned JSON and in-memory zipballs instead of GitHub. */
    SkillCatalogService(SkillService skills, Transport transport) {
        this.skills = skills;
        this.transport = transport;
    }

    /** Popular skill repositories: the pinned ones plus GitHub's most-starred, merged and cached. */
    public List<CatalogRepo> popularRepos() {
        Cached<List<CatalogRepo>> cached = repoCache;
        if (cached != null && cached.fresh()) return cached.value();

        Map<String, CatalogRepo> byName = new LinkedHashMap<>();
        for (String name : PINNED) {
            byName.put(name.toLowerCase(), new CatalogRepo(name, "", -1, true));
        }
        try {
            JsonNode found = json.readTree(transport.get(SEARCH_URL));
            for (JsonNode item : found.path("items")) {
                String fullName = item.path("full_name").asText("");
                if (fullName.isBlank()) continue;
                boolean pinned = byName.containsKey(fullName.toLowerCase());
                byName.put(fullName.toLowerCase(), new CatalogRepo(fullName,
                        item.path("description").asText(""),
                        item.path("stargazers_count").asInt(0), pinned));
            }
        } catch (IOException e) {
            // The pinned list IS the degraded answer; say why the rest is missing and move on.
            log.info("GitHub search unavailable ({}); serving the pinned repositories only.",
                    e.getMessage());
        }
        // Fill in stars for pinned repos search did not cover — best effort, one call each.
        for (String name : PINNED) {
            CatalogRepo repo = byName.get(name.toLowerCase());
            if (repo.stars() >= 0) continue;
            try {
                JsonNode meta = json.readTree(transport.get("https://api.github.com/repos/" + name));
                byName.put(name.toLowerCase(), new CatalogRepo(name,
                        meta.path("description").asText(""),
                        meta.path("stargazers_count").asInt(0), true));
            } catch (IOException e) {
                log.info("Could not read {} from GitHub: {}", name, e.getMessage());
            }
        }

        List<CatalogRepo> result = byName.values().stream()
                .sorted(Comparator.comparingInt(CatalogRepo::stars).reversed())
                .limit(MAX_REPOS)
                .toList();
        repoCache = new Cached<>(result, Instant.now());
        return result;
    }

    /** The skills inside one repository: every folder carrying a SKILL.md, plus the root itself. */
    public List<CatalogSkill> skillsOf(String owner, String repo) {
        String key = validated(owner) + "/" + validated(repo);
        Cached<List<CatalogSkill>> cached = skillCache.get(key);
        if (cached != null && cached.fresh()) return cached.value();

        List<CatalogSkill> found = new ArrayList<>();
        try {
            scanArchive(key, (dir, path, content) -> {
                if (!path.equals(dir.isEmpty() ? "SKILL.md" : dir + "/SKILL.md")) return;
                String md = new String(content, StandardCharsets.UTF_8);
                String name = SkillService.frontmatter(md, "name");
                found.add(new CatalogSkill(dir,
                        name.isBlank() ? dir.substring(dir.lastIndexOf('/') + 1) : name,
                        SkillService.frontmatter(md, "description")));
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + key + " from GitHub: " + e.getMessage());
        }
        found.sort(Comparator.comparing(CatalogSkill::path));
        skillCache.put(key, new Cached<>(found, Instant.now()));
        return found;
    }

    /**
     * Downloads one skill out of a repository and builds it with the upload pipeline's rules.
     *
     * <p>{@code path} is the skill's directory inside the repository ("" for a repository that is
     * itself one skill). It came from {@link #skillsOf} originally, but it arrives from the
     * browser, so it is treated as hostile: no escaping the archive, no dot-segments.
     */
    public SkillDef fetch(String owner, String repo, String path) {
        String key = validated(owner) + "/" + validated(repo);
        String dir = validatedPath(path);

        List<SkillDef.SkillFile> files = new ArrayList<>();
        long[] total = {0};
        try {
            scanArchive(key, (unused, filePath, content) -> {
                boolean inside = dir.isEmpty()
                        ? !filePath.startsWith(".")     // a root skill skips the repo's dotfiles
                        : filePath.startsWith(dir + "/");
                if (!inside) return;
                String relative = dir.isEmpty() ? filePath : filePath.substring(dir.length() + 1);
                if (content.length > SkillService.MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("'" + relative + "' exceeds "
                            + (SkillService.MAX_FILE_BYTES / 1024 / 1024)
                            + " MB — a skill carries documents and scripts, not datasets.");
                }
                total[0] += content.length;
                if (total[0] > SkillService.MAX_TOTAL_BYTES
                        || files.size() >= SkillService.MAX_FILES) {
                    throw new IllegalArgumentException("That skill is larger than a skill should be ("
                            + SkillService.MAX_FILES + " files / "
                            + (SkillService.MAX_TOTAL_BYTES / 1024 / 1024) + " MB).");
                }
                files.add(new SkillDef.SkillFile(relative,
                        Base64.getEncoder().encodeToString(content)));
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + key + " from GitHub: " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found under '" + path + "' in " + key + ".");
        }
        return skills.fromFiles(files);
    }

    /** What a scan does with each file: its skill directory, repo-relative path, and bytes. */
    private interface EntryVisitor {
        void file(String dir, String path, byte[] content);
    }

    /**
     * Streams a repository's zipball, handing each file to the visitor with the archive's
     * wrapping {@code repo-sha/} folder already stripped. One request, no API rate limit.
     */
    private void scanArchive(String ownerRepo, EntryVisitor visitor) throws IOException {
        byte[] archive = transport.get("https://api.github.com/repos/" + ownerRepo + "/zipball");
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new IOException("the repository archive is over "
                    + (MAX_ARCHIVE_BYTES / 1024 / 1024) + " MB — too big for a skill collection.");
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String raw = entry.getName().replace('\\', '/');
                if (raw.startsWith("/") || raw.contains("..")) continue; // hostile archive entry
                int slash = raw.indexOf('/');
                if (slash < 0) continue;                     // the lone wrapping dir, nothing else
                String path = raw.substring(slash + 1);
                if (path.isEmpty()) continue;
                byte[] content = in.readAllBytes();
                int dirEnd = path.lastIndexOf('/');
                visitor.file(dirEnd < 0 ? "" : path.substring(0, dirEnd), path, content);
            }
        }
    }

    private static String validated(String part) {
        if (part == null || !NAME_PART.matcher(part).matches() || part.contains("..")) {
            throw new IllegalArgumentException("Not a GitHub owner/repository name: " + part);
        }
        return part;
    }

    private static String validatedPath(String path) {
        String dir = path == null ? "" : path.strip();
        if (dir.startsWith("/") || dir.contains("..") || dir.contains("\\")) {
            throw new IllegalArgumentException("Not a path inside the repository: " + path);
        }
        return dir;
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                // The zipball endpoint answers with a redirect to codeload; following it is the point.
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        return (url) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    // GitHub rejects requests without a User-Agent outright.
                    .header("User-Agent", "concentus")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response =
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 403 || response.statusCode() == 429) {
                    throw new IOException("GitHub rate limit reached — try again in a few minutes.");
                }
                if (response.statusCode() >= 400) {
                    throw new IOException("GitHub answered " + response.statusCode() + ".");
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while talking to GitHub.");
            }
        };
    }
}
