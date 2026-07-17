package com.concentus.store;

import com.fasterxml.jackson.databind.ObjectMapper;

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

/** File-backed JSON store for id-keyed resource records (one file per record). */
public abstract class JsonStore<T> {

    protected final Path dir;
    protected final ObjectMapper mapper;
    private final Class<T> type;
    private final String idPrefix;

    protected JsonStore(Path dir, ObjectMapper mapper, Class<T> type, String idPrefix) {
        this.dir = dir;
        this.mapper = mapper;
        this.type = type;
        this.idPrefix = idPrefix;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected abstract String idOf(T item);

    protected abstract T withId(T item, String id);

    protected abstract String sortKey(T item);

    public synchronized List<T> list() {
        try (Stream<Path> files = Files.list(dir)) {
            List<T> out = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    out.add(mapper.readValue(Files.readString(p), type));
                } catch (IOException ignored) {
                    // skip corrupt entries
                }
            });
            out.sort(Comparator.comparing(i -> {
                String k = sortKey(i);
                return k == null ? "" : k;
            }, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<T> get(String id) {
        Path f = fileFor(id);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(Files.readString(f), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized T save(T item) {
        String id = (idOf(item) == null || idOf(item).isBlank()) ? newId() : sanitize(idOf(item));
        T toSave = withId(item, id);
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
            throw new IllegalArgumentException("Invalid id: " + id);
        }
        return id;
    }

    private String newId() {
        return idPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
