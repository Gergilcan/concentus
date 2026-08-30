package com.concentus.marketplace;

import com.concentus.model.FlowGraph;
import com.concentus.model.LibraryAgent;
import com.concentus.store.AgentLibraryStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes the bundled library as global, built-in, approved items: the MCP catalog
 * ({@code library-mcp.json}), the library agents ({@code library-agents/*.yaml}) and the starter
 * flows ({@code library-flows/*.json}).
 *
 * <p>Runs on every start and compares rather than counts. Each built-in has an id derived from
 * what it is ("mcp:GitHub", "flow:pr-review-crew") and a hash of its bundled definition; a row
 * whose hash matches is left alone, a row whose hash differs is rewritten with the version bumped
 * — so an organization that installed the old one is told an update exists — and a built-in with
 * no row is inserted. Unless it was deleted: what has been seeded is recorded, the way
 * {@code FlowLibrarySeeder} records its starters, and a built-in that is on the record but not in
 * the table stays gone. Nothing here reaches the network; the skill catalog and the plugin
 * marketplaces, which do, are not seeded.
 */
@Component
public class MarketplaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSeeder.class);

    public static final String AUTHOR = "system:concentus";

    /** Recorded beside the flows' marker, in the same table, for the same reason: it has to follow the database. */
    private static final String MARKER_KIND = "meta";
    private static final String MARKER_ID = "seeded-marketplace";

    /** One bundled definition, before it becomes a row. */
    record Seed(String key, String kind, String name, String summary, String description,
                List<String> tags, JsonNode payload) {
    }

    /** What one run did, for the log and the tests. */
    public record Report(int inserted, int updated, int unchanged, int deleted) {
    }

    private final JdbcTemplate jdbc;
    private final MarketplaceStore store;
    private final ObjectMapper mapper;

    public MarketplaceSeeder(JdbcTemplate jdbc, MarketplaceStore store, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.store = store;
        this.mapper = mapper;
    }

    @PostConstruct
    void seedOnStart() {
        try {
            Report report = seed();
            log.info("Marketplace library: {} added, {} updated, {} unchanged, {} deleted by hand.",
                    report.inserted(), report.updated(), report.unchanged(), report.deleted());
        } catch (Exception e) {
            // The bundle failing to seed must never stop the app from serving the real thing.
            log.warn("Could not seed the marketplace library: {}", e.getMessage());
        }
    }

    /** Public for the tests: one pass over the bundle against whatever the table holds. */
    public Report seed() {
        if (!store.isAvailable()) return new Report(0, 0, 0, 0);
        Set<String> recorded = readMarker();
        Set<String> seeded = new LinkedHashSet<>(recorded);
        int inserted = 0, updated = 0, unchanged = 0, deleted = 0;
        long now = System.currentTimeMillis();

        for (Seed seed : bundled()) {
            String id = idFor(seed.key());
            String hash = hashOf(seed);
            seeded.add(id);
            MarketplaceItem existing = store.find(id).orElse(null);
            if (existing != null) {
                if (!existing.builtIn() || hash.equals(store.builtInHash(id).orElse(""))) {
                    unchanged++;
                    continue;
                }
                boolean payloadChanged = !MarketplaceItem.payloadEquals(existing.payload(), seed.payload());
                store.update(new MarketplaceItem(id, seed.kind(), seed.name(), seed.summary(),
                        seed.description(), seed.tags(), payloadChanged ? existing.version() + 1 : existing.version(),
                        MarketplaceItem.SCOPE_GLOBAL, null, MarketplaceItem.PUBLISHED, null,
                        existing.author(), seed.payload(), existing.icon(), existing.installs(), true,
                        existing.createdAt(), now, existing.publishedAt() == null ? now : existing.publishedAt(),
                        AUTHOR), hash);
                updated++;
            } else if (recorded.contains(id)) {
                // Installed once and deleted since: a starter, not something the app keeps
                // reinstating against the deployment's wishes.
                deleted++;
            } else {
                store.insert(new MarketplaceItem(id, seed.kind(), seed.name(), seed.summary(),
                        seed.description(), seed.tags(), 1, MarketplaceItem.SCOPE_GLOBAL, null,
                        MarketplaceItem.PUBLISHED, null, new MarketplaceItem.Author(AUTHOR, AUTHOR),
                        seed.payload(), null, 0, true, now, now, now, AUTHOR), hash);
                inserted++;
            }
        }
        writeMarker(seeded);
        return new Report(inserted, updated, unchanged, deleted);
    }

    /** The id a built-in gets: stable across starts and databases, derived from what it is. */
    public static String idFor(String key) {
        return "mkt_" + sha256(key).substring(0, 12);
    }

    // ------------------------------------------------------------------ the bundle

    List<Seed> bundled() {
        List<Seed> out = new ArrayList<>();
        out.addAll(mcpCatalog());
        out.addAll(libraryAgents());
        out.addAll(libraryFlows());
        return out;
    }

    /** {@code library-mcp.json}: the catalog the MCP panel offers, one item per server. */
    private List<Seed> mcpCatalog() {
        List<Seed> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/library-mcp.json")) {
            if (in == null) return out;
            List<Map<String, Object>> entries = mapper.readValue(in.readAllBytes(),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> e : entries) {
                String name = str(e.get("name"));
                if (name.isBlank()) continue;
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", name);
                if (e.get("url") != null) payload.put("url", str(e.get("url")));
                if (e.get("command") != null) payload.put("command", str(e.get("command")));
                if (e.get("args") != null) payload.set("args", mapper.valueToTree(e.get("args")));
                if (e.get("env") != null) payload.set("env", mapper.valueToTree(e.get("env")));
                if (e.get("header") != null) payload.put("authHeader", str(e.get("header")));
                String auth = str(e.get("auth"));
                payload.put("auth", auth);
                List<String> tags = new ArrayList<>();
                if (!str(e.get("category")).isBlank()) tags.add(str(e.get("category")));
                if (!auth.isBlank()) tags.add(auth);
                out.add(new Seed("mcp:" + name, MarketplaceItem.KIND_MCP, name, str(e.get("blurb")),
                        blankToNull(str(e.get("note"))), tags, payload));
            }
        } catch (IOException e) {
            log.warn("Could not read library-mcp.json: {}", e.getMessage());
        }
        return out;
    }

    /** {@code library-agents/*.yaml}, read exactly as the agent library reads them. */
    private List<Seed> libraryAgents() {
        List<Seed> out = new ArrayList<>();
        for (Resource r : resources("classpath*:library-agents/*.yaml")) {
            String filename = r.getFilename();
            if (filename == null) continue;
            String id = filename.replaceFirst("\\.(ya?ml)$", "");
            try (InputStream in = r.getInputStream()) {
                LibraryAgent agent = AgentLibraryStore.fromYaml(in.readAllBytes(), id);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", agent.name());
                payload.put("model", agent.model());
                payload.put("effort", agent.effort());
                payload.put("maxTokens", agent.maxTokens());
                payload.put("systemPrompt", agent.systemPrompt());
                payload.put("description", agent.description());
                out.add(new Seed("agent:" + id, MarketplaceItem.KIND_AGENT, agent.name(), agent.description(),
                        null, List.of(), payload));
            } catch (Exception e) {
                log.warn("Skipping bundled agent {}: {}", filename, e.getMessage());
            }
        }
        return out;
    }

    /** {@code library-flows/*.json}: the starters, stripped of ids and secrets like any published flow. */
    private List<Seed> libraryFlows() {
        List<Seed> out = new ArrayList<>();
        for (Resource r : resources("classpath*:library-flows/*.json")) {
            String filename = r.getFilename();
            if (filename == null) continue;
            try (InputStream in = r.getInputStream()) {
                FlowGraph flow = mapper.readValue(in.readAllBytes(), FlowGraph.class);
                String key = flow.id() == null || flow.id().isBlank() ? filename.replaceFirst("\\.json$", "") : flow.id();
                FlowGraph clean = MarketplaceInstaller.stripFlow(flow, new ArrayList<>());
                out.add(new Seed("flow:" + key, MarketplaceItem.KIND_FLOW, flow.name(),
                        MarketplaceInstaller.flowSummary(flow), null, flow.tagsOrEmpty(),
                        mapper.valueToTree(clean)));
            } catch (Exception e) {
                log.warn("Skipping bundled flow {}: {}", filename, e.getMessage());
            }
        }
        return out;
    }

    private static List<Resource> resources(String pattern) {
        try {
            return List.of(new PathMatchingResourcePatternResolver().getResources(pattern));
        } catch (IOException e) {
            log.warn("Could not list {}: {}", pattern, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ hashing and the marker

    private String hashOf(Seed seed) {
        try {
            ObjectNode canonical = mapper.createObjectNode();
            canonical.put("kind", seed.kind());
            canonical.put("name", seed.name());
            canonical.put("summary", seed.summary());
            canonical.put("description", seed.description());
            canonical.set("tags", mapper.valueToTree(seed.tags()));
            canonical.set("payload", seed.payload());
            return sha256(mapper.writeValueAsString(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash a bundled item: " + e.getMessage(), e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> readMarker() {
        try {
            return jdbc.query("select json from resources where kind = ? and id = ?",
                            (rs, i) -> rs.getString("json"), MARKER_KIND, MARKER_ID)
                    .stream().findFirst()
                    .map(json -> {
                        try {
                            return (Set<String>) new LinkedHashSet<>(
                                    mapper.readValue(json, new TypeReference<List<String>>() {}));
                        } catch (Exception e) {
                            return (Set<String>) new LinkedHashSet<String>();
                        }
                    })
                    .orElse(new LinkedHashSet<>());
        } catch (RuntimeException e) {
            // "Nothing recorded" is the safe reading: the worst case is a deleted built-in being
            // offered again, which is recoverable; a seeder that failed to start is not.
            return new LinkedHashSet<>();
        }
    }

    private void writeMarker(Set<String> ids) {
        if (ids.isEmpty()) return;
        try {
            jdbc.update("""
                    insert into resources (kind, id, sort_key, json, updated_at)
                    values (?, ?, ?, ?, ?)
                    on conflict (kind, id) do update
                       set json = excluded.json, updated_at = excluded.updated_at
                    """, MARKER_KIND, MARKER_ID, MARKER_ID,
                    mapper.writeValueAsString(List.copyOf(ids)), System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Could not record the seeded marketplace items: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
