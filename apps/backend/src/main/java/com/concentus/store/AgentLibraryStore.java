package com.concentus.store;

import com.concentus.config.AgentSpec;
import com.concentus.model.LibraryAgent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A library of reusable agent definitions, selectable when configuring an agent node.
 *
 * <p>These were YAML files in the agents directory, and could be added by dropping a file there.
 * They are now rows like every other record, for the reason the whole move exists: an external
 * database should carry the things people build, and a library that lived on one laptop could not
 * be shared between installs or backed up with the rest.
 *
 * <p><b>The drop-a-file workflow is gone.</b> Existing YAML files are imported once, and the
 * bundled examples are installed into the database on a first run — but after that the library is
 * edited in the app, under Resources → Agents, which is where people were already doing it. The
 * import still reads YAML, so a file-backed install upgrades without losing anything.
 */
@Component
public class AgentLibraryStore extends JsonStore<LibraryAgent> {

    private static final String DEFAULT_MODEL = "claude-opus-4-8";
    private static final String DEFAULT_EFFORT = "high";
    private static final long DEFAULT_MAX_TOKENS = 16000;

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AgentLibraryStore(JdbcTemplate jdbc,
                             @Value("${app.agents-dir:}") String agentsDir,
                             @Value("${app.data-dir}") String dataDir,
                             ObjectMapper mapper) {
        super(jdbc, mapper, LibraryAgent.class, "agent", "agent_",
                (agentsDir == null || agentsDir.isBlank()) ? Path.of(dataDir, "agents") : Path.of(agentsDir));
    }

    @Override
    protected String idOf(LibraryAgent a) {
        return a.id();
    }

    @Override
    protected LibraryAgent withId(LibraryAgent a, String id) {
        return new LibraryAgent(id, a.name(), a.model(), a.effort(), a.maxTokens(), a.systemPrompt());
    }

    @Override
    protected String sortKey(LibraryAgent a) {
        return a.name();
    }

    /**
     * Normalises before storing, so {@code save} and {@code list} agree on every field — a null
     * system prompt reads back as "" either way, and a missing model is the default rather than
     * null waiting to break a run.
     */
    @Override
    public LibraryAgent save(LibraryAgent a) {
        return super.save(new LibraryAgent(
                a.id(),
                a.name() == null || a.name().isBlank() ? "Untitled agent" : a.name(),
                a.model() == null || a.model().isBlank() ? DEFAULT_MODEL : a.model(),
                a.effort() == null || a.effort().isBlank() ? DEFAULT_EFFORT : a.effort(),
                a.maxTokens() > 0 ? a.maxTokens() : DEFAULT_MAX_TOKENS,
                a.systemPrompt() == null ? "" : a.systemPrompt()));
    }

    /** Reads the YAML AgentSpec files the file-backed version wrote. */
    @Override
    protected List<LibraryAgent> readLegacy(Path dir) throws IOException {
        List<LibraryAgent> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(AgentLibraryStore::isYaml).toList()) {
                try {
                    out.add(parse(file));
                } catch (Exception e) {
                    // One malformed file must not stop the rest of a library being imported.
                    logSkipped(file, e);
                }
            }
        }
        return out;
    }

    private void logSkipped(Path file, Exception e) {
        org.slf4j.LoggerFactory.getLogger(AgentLibraryStore.class)
                .warn("Skipping unreadable library agent {}: {}", file.getFileName(), e.getMessage());
    }

    private LibraryAgent parse(Path p) throws IOException {
        AgentSpec s = yaml.readValue(Files.readString(p), AgentSpec.class);
        // The filename was the id in the file-backed layout, so importing keeps it — a flow node
        // that recorded which library agent it came from still matches.
        String id = p.getFileName().toString().replaceFirst("\\.(ya?ml)$", "");
        String model = (s.model != null && s.model.id != null) ? s.model.id : DEFAULT_MODEL;
        String effort = (s.model != null && s.model.effort != null) ? s.model.effort : DEFAULT_EFFORT;
        long maxTokens = (s.model != null && s.model.maxTokens > 0) ? s.model.maxTokens : DEFAULT_MAX_TOKENS;
        String name = (s.name == null || s.name.isBlank()) ? id : s.name;
        return new LibraryAgent(id, name, model, effort, maxTokens,
                s.systemPrompt == null ? "" : s.systemPrompt);
    }

    private static boolean isYaml(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    /**
     * Installs the bundled examples when the library is empty.
     *
     * <p>Keyed on empty rather than on first run, so an install that already had agents — imported
     * from files a moment earlier — is left alone instead of having examples added to it.
     */
    @Override
    void init() {
        super.init();
        if (!isAvailable() || !list().isEmpty()) return;
        try {
            Resource[] bundled = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:library-agents/*.yaml");
            for (Resource r : bundled) {
                String filename = r.getFilename();
                if (filename == null) continue;
                try (InputStream in = r.getInputStream()) {
                    AgentSpec spec = yaml.readValue(in.readAllBytes(), AgentSpec.class);
                    String id = filename.replaceFirst("\\.(ya?ml)$", "");
                    super.save(new LibraryAgent(id,
                            spec.name == null || spec.name.isBlank() ? id : spec.name,
                            spec.model != null && spec.model.id != null ? spec.model.id : DEFAULT_MODEL,
                            spec.model != null && spec.model.effort != null ? spec.model.effort : DEFAULT_EFFORT,
                            spec.model != null && spec.model.maxTokens > 0 ? spec.model.maxTokens : DEFAULT_MAX_TOKENS,
                            spec.systemPrompt == null ? "" : spec.systemPrompt));
                }
            }
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(AgentLibraryStore.class)
                    .warn("Could not install the bundled library agents: {}", e.getMessage());
        }
    }
}
