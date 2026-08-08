package com.concentus.store;

import com.concentus.support.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Id-keyed resource records — flows, MCP definitions, database definitions — stored as JSON rows.
 *
 * <p>These used to be one file per record under the data directory. Moving them into the database
 * is what makes an external PostgreSQL mean something: pointing two installs at one database now
 * shares the flows and definitions between them, and backing up that database backs up the actual
 * work rather than just the run history. With files, "shared storage" shared everything except the
 * things people build.
 *
 * <p>One table for every kind rather than one per store. The records differ only in which Java type
 * their JSON deserialises to, so separate tables would be the same three columns three times, and
 * a new kind of record would need a migration instead of a constructor argument.
 *
 * <p><b>What this costs.</b> A file-backed store worked whether or not the database did; this does
 * not. An unreachable database now means no flows rather than a degraded feature, so every read
 * fails soft — an empty list and a logged reason — while writes fail loudly, because silently
 * discarding someone's flow would be worse than an error message.
 */
public abstract class JsonStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonStore.class);

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;
    private final Class<T> type;
    /** Discriminates rows in the shared table: "flow", "mcp", "database". */
    private final String kind;
    private final String idPrefix;
    /** The folder these records used to live in, imported once and then set aside. */
    private final Path legacyDir;
    private volatile boolean available;

    protected JsonStore(JdbcTemplate jdbc, ObjectMapper mapper, Class<T> type, String kind,
                        String idPrefix, Path legacyDir) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.type = type;
        this.kind = kind;
        this.idPrefix = idPrefix;
        this.legacyDir = legacyDir;
    }

    @PostConstruct
    void init() {
        try {
            jdbc.execute("""
                    create table if not exists resources (
                      kind text not null,
                      id text not null,
                      sort_key text,
                      json text not null,
                      updated_at bigint not null,
                      primary key (kind, id)
                    )
                    """);
            available = true;
        } catch (Exception e) {
            log.error("{} storage unavailable: {}", kind, e.getMessage());
            return;
        }
        migrateLegacyFiles();
    }

    public boolean isAvailable() {
        return available;
    }

    protected abstract String idOf(T item);

    protected abstract T withId(T item, String id);

    protected abstract String sortKey(T item);

    public List<T> list() {
        if (!available) return List.of();
        try {
            // Sorted in SQL, case-insensitively, to match what the file-backed version did.
            return jdbc.query(
                    "select json from resources where kind = ? order by lower(coalesce(sort_key, '')), id",
                    (rs, i) -> parse(rs.getString("json")), kind)
                    .stream().filter(java.util.Objects::nonNull).toList();
        } catch (RuntimeException e) {
            log.warn("Could not list {} records: {}", kind, e.getMessage());
            return List.of();
        }
    }

    public Optional<T> get(String id) {
        if (!available) return Optional.empty();
        String safe = Ids.sanitize(id, "Invalid id: ");
        try {
            return jdbc.query("select json from resources where kind = ? and id = ?",
                            (rs, i) -> parse(rs.getString("json")), kind, safe)
                    .stream().findFirst().filter(java.util.Objects::nonNull);
        } catch (RuntimeException e) {
            log.warn("Could not read {} {}: {}", kind, safe, e.getMessage());
            return Optional.empty();
        }
    }

    public T save(T item) {
        requireAvailable();
        String id = (idOf(item) == null || idOf(item).isBlank())
                ? Ids.generate(idPrefix, 10)
                : Ids.sanitize(idOf(item), "Invalid id: ");
        T toSave = withId(item, id);
        write(id, toSave);
        return toSave;
    }

    public boolean delete(String id) {
        requireAvailable();
        return jdbc.update("delete from resources where kind = ? and id = ?",
                kind, Ids.sanitize(id, "Invalid id: ")) > 0;
    }

    private void write(String id, T item) {
        try {
            String json = mapper.writeValueAsString(item);
            jdbc.update("""
                    insert into resources (kind, id, sort_key, json, updated_at)
                    values (?, ?, ?, ?, ?)
                    on conflict (kind, id) do update
                       set sort_key = excluded.sort_key,
                           json = excluded.json,
                           updated_at = excluded.updated_at
                    """, kind, id, sortKey(item), json, System.currentTimeMillis());
        } catch (Exception e) {
            throw new IllegalStateException("Could not save " + kind + " " + id + ": " + e.getMessage(), e);
        }
    }

    /** Null rather than throwing, so one unreadable row cannot hide every other record. */
    private T parse(String json) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Skipping an unreadable {} record: {}", kind, e.getMessage());
            return null;
        }
    }

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException(
                    "The database is unavailable, so " + kind + " records cannot be saved. "
                            + "Check the database settings under Resources → Storage.");
        }
    }

    /**
     * Imports records written by an older, file-backed build.
     *
     * <p>Runs once: the directory is renamed afterwards rather than deleted, so if anything about
     * the import turns out to be wrong the original files are still sitting there. Skipped entirely
     * when the table already holds records of this kind — that is the case where an install has
     * been running on the database for a while and the folder is a stale leftover, and re-importing
     * it would resurrect flows the user had deleted.
     */
    /**
     * Reads the records an older build left on disk. JSON files by default; overridden where a
     * store used a different format, which is why the import is a method rather than inlined.
     *
     * <p>Each record comes back with an id: the filename is the fallback, because it was the id
     * before records carried their own.
     */
    protected List<T> readLegacy(Path dir) throws IOException {
        List<T> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    T item = mapper.readValue(Files.readString(file), type);
                    if (idOf(item) == null || idOf(item).isBlank()) {
                        item = withId(item, file.getFileName().toString().replaceFirst("\\.json$", ""));
                    }
                    out.add(item);
                } catch (Exception e) {
                    log.warn("Skipping unreadable {} file {}: {}", kind, file.getFileName(), e.getMessage());
                }
            }
        }
        return out;
    }

    private void migrateLegacyFiles() {
        if (legacyDir == null || !Files.isDirectory(legacyDir)) return;
        try {
            Integer existing = jdbc.queryForObject(
                    "select count(*) from resources where kind = ?", Integer.class, kind);
            if (existing != null && existing > 0) return;

            List<T> legacy = readLegacy(legacyDir);
            if (legacy.isEmpty()) return;

            List<String> failed = new ArrayList<>();
            int imported = 0;
            for (T item : legacy) {
                try {
                    String id = idOf(item);
                    write(Ids.sanitize(id, "Invalid id: "), item);
                    imported++;
                } catch (Exception e) {
                    failed.add(idOf(item) + " (" + e.getMessage() + ")");
                }
            }

            if (imported > 0) {
                Path moved = legacyDir.resolveSibling(legacyDir.getFileName() + ".migrated");
                try {
                    Files.move(legacyDir, moved, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Imported {} {} record(s) into the database; the old files are now in {}.",
                            imported, kind, moved);
                } catch (IOException e) {
                    // Not fatal — the records are already in the database, which is what matters.
                    // Left in place, and the count check above stops it importing them twice.
                    log.warn("Imported {} {} record(s), but could not move {}: {}",
                            imported, kind, legacyDir, e.getMessage());
                }
            }
            if (!failed.isEmpty()) {
                log.warn("Could not import {} {} file(s): {}", failed.size(), kind, String.join(", ", failed));
            }
        } catch (Exception e) {
            log.warn("Could not import legacy {} files from {}: {}", kind, legacyDir, e.getMessage());
        }
    }
}
