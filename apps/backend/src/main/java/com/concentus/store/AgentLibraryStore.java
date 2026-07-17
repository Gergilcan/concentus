package com.concentus.store;

import com.concentus.config.AgentSpec;
import com.concentus.model.LibraryAgent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A library of reusable agent definitions, each a YAML file in the same {@link AgentSpec}
 * format as the CLI. Users drop {@code *.yaml} files in the agents dir to make agents
 * selectable when configuring an agent node. Two examples are seeded on first run.
 */
@Component
public class AgentLibraryStore {

    private static final Logger log = LoggerFactory.getLogger(AgentLibraryStore.class);

    private final Path dir;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AgentLibraryStore(@Value("${app.agents-dir:}") String agentsDir,
                             @Value("${app.data-dir}") String dataDir) throws IOException {
        this.dir = (agentsDir == null || agentsDir.isBlank()) ? Path.of(dataDir, "agents") : Path.of(agentsDir);
        Files.createDirectories(this.dir);
        seedExamplesIfEmpty();
    }

    public List<LibraryAgent> list() {
        try (Stream<Path> files = Files.list(dir)) {
            List<LibraryAgent> out = new ArrayList<>();
            files.filter(AgentLibraryStore::isYaml).forEach(p -> {
                try {
                    out.add(parse(p));
                } catch (Exception e) {
                    log.warn("skipping unreadable library agent {}: {}", p.getFileName(), e.getMessage());
                }
            });
            out.sort(Comparator.comparing(a -> a.name() == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates or updates a library agent, writing it as a YAML AgentSpec file. */
    public LibraryAgent save(LibraryAgent a) {
        String id = (a.id() == null || a.id().isBlank()) ? newId() : sanitizeId(a.id());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", a.model() == null || a.model().isBlank() ? "claude-opus-4-8" : a.model());
        model.put("effort", a.effort() == null || a.effort().isBlank() ? "high" : a.effort());
        model.put("maxTokens", a.maxTokens() > 0 ? a.maxTokens() : 16000);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", a.name() == null || a.name().isBlank() ? id : a.name());
        doc.put("model", model);
        doc.put("systemPrompt", a.systemPrompt() == null ? "" : a.systemPrompt());

        try {
            Files.writeString(dir.resolve(id + ".yaml"), yaml.writeValueAsString(doc));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LibraryAgent(id, (String) doc.get("name"), (String) model.get("id"),
                (String) model.get("effort"), a.maxTokens() > 0 ? a.maxTokens() : 16000, a.systemPrompt());
    }

    public boolean delete(String id) {
        String safe = sanitizeId(id);
        try {
            boolean a = Files.deleteIfExists(dir.resolve(safe + ".yaml"));
            boolean b = Files.deleteIfExists(dir.resolve(safe + ".yml"));
            return a || b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sanitizeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid agent id: " + id);
        }
        return id;
    }

    private static String newId() {
        return "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private LibraryAgent parse(Path p) throws IOException {
        AgentSpec s = yaml.readValue(Files.readString(p), AgentSpec.class);
        String file = p.getFileName().toString();
        String id = file.replaceFirst("\\.(ya?ml)$", "");
        String model = (s.model != null && s.model.id != null) ? s.model.id : "claude-opus-4-8";
        String effort = (s.model != null && s.model.effort != null) ? s.model.effort : "high";
        long maxTokens = (s.model != null && s.model.maxTokens > 0) ? s.model.maxTokens : 16000;
        String name = (s.name == null || s.name.isBlank()) ? id : s.name;
        return new LibraryAgent(id, name, model, effort, maxTokens, s.systemPrompt == null ? "" : s.systemPrompt);
    }

    private static boolean isYaml(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    /** On first run, install the bundled curated agent definitions into the library dir. */
    private void seedExamplesIfEmpty() {
        try (Stream<Path> files = Files.list(dir)) {
            if (files.anyMatch(AgentLibraryStore::isYaml)) return;
        } catch (IOException e) {
            return;
        }
        try {
            Resource[] bundled = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:library-agents/*.yaml");
            for (Resource r : bundled) {
                String name = r.getFilename();
                if (name == null) continue;
                try (InputStream in = r.getInputStream()) {
                    Files.write(dir.resolve(name), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            log.warn("could not seed bundled library agents: {}", e.getMessage());
        }
    }
}
