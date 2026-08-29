package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.support.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <p><b>Every row belongs to one organization.</b> {@link #list()}, {@link #get}, {@link #save}
 * and {@link #delete} act for the organization the calling thread is in — the signed-in person's,
 * or the installation's default when there is no principal — so a second organization on the
 * same deployment sees none of the first one's records and cannot reach them by id. The few
 * callers that legitimately act for no organization in particular — the schedulers, which fire
 * every organization's triggers, and the endpoints a webhook or a published-flow token
 * authenticates — say so by name: {@link #listAcrossOrganizations()} and
 * {@link #getAcrossOrganizations}. A caller that knows which organization it acts for without
 * having a principal (a run's own threads) names it: {@link #getIn}, {@link #saveIn}.
 *
 * <p><b>What this costs.</b> A file-backed store worked whether or not the database did; this does
 * not. An unreachable database now means no flows rather than a degraded feature, so every read
 * fails soft — an empty list and a logged reason — while writes fail loudly, because silently
 * discarding someone's flow would be worse than an error message.
 */
public abstract class JsonStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonStore.class);
    /** Doubles as the availability probe in {@code init} and the "already imported?" check. */
    private static final String COUNT_BY_KIND = "select count(*) from resources where kind = ?";
    private static final String BAD_ID = "Invalid id: ";

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;
    private final Class<T> type;
    /** Discriminates rows in the shared table: "flow", "mcp", "database". */
    private final String kind;
    private final String idPrefix;
    /** The folder these records used to live in, imported once and then set aside. */
    private final Path legacyDir;
    /** Whose records a call without an explicit organization reads and writes. */
    private final OrgContext orgContext;
    private volatile boolean available;
    /** Every read selects the same single column; {@code parse} may return null, hence the filters. */
    private final RowMapper<T> jsonRow = (rs, i) -> parse(rs.getString("json"));

    protected JsonStore(JdbcTemplate jdbc, ObjectMapper mapper, Class<T> type, String kind,
                        String idPrefix, Path legacyDir, OrgContext orgContext) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.type = type;
        this.kind = kind;
        this.idPrefix = idPrefix;
        this.legacyDir = legacyDir;
        this.orgContext = orgContext;
    }

    @PostConstruct
    void init() {
        // The table is created by the migrations, not here. This only asks whether it is actually
        // there — which is the same question the old `create table if not exists` answered as a
        // side effect, and the answer still decides whether reads return empty or writes throw.
        try {
            jdbc.queryForObject(COUNT_BY_KIND, Integer.class, kind);
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

    /** The organization a call that names none acts for. */
    private String scope() {
        return orgContext.currentOrganizationId();
    }

    public List<T> list() {
        return listIn(scope());
    }

    public List<T> listIn(String organizationId) {
        if (!available) return List.of();
        try {
            // Sorted in SQL, case-insensitively, to match what the file-backed version did.
            return jdbc.query(
                    "select json from resources where kind = ? and organization_id = ? "
                            + "order by lower(coalesce(sort_key, '')), id",
                    jsonRow, kind, organizationId)
                    .stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            log.warn("Could not list {} records: {}", kind, e.getMessage());
            return List.of();
        }
    }

    /**
     * Every organization's records. For the schedulers only — a cron, a mail poll or a folder
     * watch has to fire for every organization's flows, and has no principal to be scoped by.
     * Nothing that answers an HTTP request for a person should call this.
     */
    public List<T> listAcrossOrganizations() {
        if (!available) return List.of();
        try {
            return jdbc.query(
                    "select json from resources where kind = ? order by lower(coalesce(sort_key, '')), id",
                    jsonRow, kind)
                    .stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            log.warn("Could not list {} records: {}", kind, e.getMessage());
            return List.of();
        }
    }

    public Optional<T> get(String id) {
        return getIn(scope(), id);
    }

    /** Empty for an id that exists in another organization, exactly as for one that does not exist. */
    public Optional<T> getIn(String organizationId, String id) {
        if (!available) return Optional.empty();
        String safe = Ids.sanitize(id, BAD_ID);
        try {
            return jdbc.query("select json from resources where kind = ? and id = ? and organization_id = ?",
                            jsonRow, kind, safe, organizationId)
                    .stream().findFirst().filter(Objects::nonNull);
        } catch (RuntimeException e) {
            log.warn("Could not read {} {}: {}", kind, safe, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A record by id whichever organization holds it. For callers whose authorization is not a
     * session but the id itself plus a secret of its own — a webhook delivery, a published-flow
     * token, a trigger firing the flow it was scheduled for.
     */
    public Optional<T> getAcrossOrganizations(String id) {
        if (!available) return Optional.empty();
        String safe = Ids.sanitize(id, BAD_ID);
        try {
            return jdbc.query("select json from resources where kind = ? and id = ?", jsonRow, kind, safe)
                    .stream().findFirst().filter(Objects::nonNull);
        } catch (RuntimeException e) {
            log.warn("Could not read {} {}: {}", kind, safe, e.getMessage());
            return Optional.empty();
        }
    }

    /** Which organization a record belongs to — what a run started with no principal is stamped with. */
    public Optional<String> organizationOf(String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        try {
            return jdbc.queryForList("select organization_id from resources where kind = ? and id = ?",
                            String.class, kind, Ids.sanitize(id, BAD_ID))
                    .stream().filter(Objects::nonNull).findFirst();
        } catch (RuntimeException e) {
            log.warn("Could not read the organization of {} {}: {}", kind, id, e.getMessage());
            return Optional.empty();
        }
    }

    public T save(T item) {
        return saveIn(scope(), item);
    }

    /**
     * @throws IllegalArgumentException when the id already names another organization's record —
     *         an import of somebody else's backup, most likely. Refused rather than overwritten:
     *         the write would otherwise reach across the one boundary this store exists to keep.
     */
    public T saveIn(String organizationId, T item) {
        requireAvailable();
        String given = idOf(item);
        String id = (given == null || given.isBlank())
                ? Ids.generate(idPrefix, 10)
                : Ids.sanitize(given, BAD_ID);
        T toSave = withId(item, id);
        write(organizationId, id, toSave);
        return toSave;
    }

    public boolean delete(String id) {
        return deleteIn(scope(), id);
    }

    public boolean deleteIn(String organizationId, String id) {
        requireAvailable();
        return jdbc.update("delete from resources where kind = ? and id = ? and organization_id = ?",
                kind, Ids.sanitize(id, BAD_ID), organizationId) > 0;
    }

    private void write(String organizationId, String id, T item) {
        String json;
        try {
            json = mapper.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save " + kind + " " + id + ": " + e.getMessage(), e);
        }
        int written;
        try {
            // The update half applies only to a row this organization owns: with the guard, an id
            // that collides with another organization's record touches nothing and is reported
            // below, instead of quietly becoming that organization's record.
            written = jdbc.update("""
                    insert into resources (kind, id, sort_key, json, updated_at, organization_id)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (kind, id) do update
                       set sort_key = excluded.sort_key,
                           json = excluded.json,
                           updated_at = excluded.updated_at
                     where resources.organization_id = excluded.organization_id
                    """, kind, id, sortKey(item), json, System.currentTimeMillis(), organizationId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save " + kind + " " + id + ": " + e.getMessage(), e);
        }
        if (written == 0) {
            throw new IllegalArgumentException("The id " + id + " already names a " + kind
                    + " in another organization. Save it under a new id.");
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

    /**
     * Imports records written by an older, file-backed build.
     *
     * <p>Runs once: the directory is renamed afterwards rather than deleted, so if anything about
     * the import turns out to be wrong the original files are still sitting there. Skipped entirely
     * when the table already holds records of this kind — that is the case where an install has
     * been running on the database for a while and the folder is a stale leftover, and re-importing
     * it would resurrect flows the user had deleted.
     *
     * <p>Imported into the installation's default organization: files on disk predate there being
     * more than one, and this runs at startup with nobody signed in.
     */
    private void migrateLegacyFiles() {
        if (legacyDir == null || !Files.isDirectory(legacyDir)) return;
        try {
            Integer existing = jdbc.queryForObject(COUNT_BY_KIND, Integer.class, kind);
            if (existing != null && existing > 0) return;

            List<T> legacy = readLegacy(legacyDir);
            if (legacy.isEmpty()) return;

            List<String> failed = new ArrayList<>();
            int imported = 0;
            for (T item : legacy) {
                try {
                    write(orgContext.defaultOrganizationId(), Ids.sanitize(idOf(item), BAD_ID), item);
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
