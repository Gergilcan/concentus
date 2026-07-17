package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Persists flows as one JSON file per flow under {@code <data-dir>/flows}. */
@Component
public class FlowStore {

    private final Path dir;
    private final ObjectMapper mapper;

    public FlowStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper) throws IOException {
        this.dir = Path.of(dataDir, "flows");
        Files.createDirectories(this.dir);
        this.mapper = mapper;
    }

    public synchronized List<FlowGraph> list() {
        try (Stream<Path> files = Files.list(dir)) {
            List<FlowGraph> flows = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    flows.add(mapper.readValue(Files.readString(p), FlowGraph.class));
                } catch (IOException e) {
                    // Skip unreadable/corrupt files rather than failing the whole list.
                }
            });
            flows.sort(Comparator.comparing(f -> f.name() == null ? "" : f.name(), String.CASE_INSENSITIVE_ORDER));
            return flows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<FlowGraph> get(String id) {
        Path f = fileFor(id);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(Files.readString(f), FlowGraph.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized FlowGraph save(FlowGraph flow) {
        String id = (flow.id() == null || flow.id().isBlank()) ? newId() : sanitize(flow.id());
        FlowGraph toSave = flow.withId(id);
        try {
            Files.writeString(fileFor(id), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toSave));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return toSave;
    }

    public synchronized boolean delete(String id) {
        try {
            return Files.deleteIfExists(fileFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String id) {
        return dir.resolve(sanitize(id) + ".json");
    }

    private static String sanitize(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid flow id: " + id);
        }
        return id;
    }

    private static String newId() {
        return "flow_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
