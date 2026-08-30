package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.groups.GroupContext;
import com.concentus.support.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>And, within an organization, a row may belong to one group.</b> {@code group_id} is null
 * for a row the whole organization sees and a group's id for one only its members and the
 * organization's admins see. Every organization-scoped read and write adds that condition from
 * {@link GroupContext} — for a signed-in person; a thread with no principal is the machine's own
 * (a cron, a run's threads) and is not filtered, or a group could not own a flow that runs. The
 * two cross-organization escapes stay unfiltered for the same reason. A save never touches the
 * column: a row keeps its group until {@link #assignGroup} moves it, so an edit cannot silently
 * widen who sees the thing edited.
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
    /**
     * Which group-scoped rows the caller may see. Injected by setter rather than by constructor
     * so the thirteen stores' constructors — and every test that builds one by hand — keep their
     * shape; a store built outside a container has none and filters nothing, which is the
     * pre-groups behaviour those tests assert.
     */
    private GroupContext groupContext;
    private volatile boolean available;
    /**
     * Every read selects the same two columns — the record, and the group it is scoped to, which
     * is stamped onto the record on the way out; {@code parse} may return null, hence the filters.
     */
    private final RowMapper<T> jsonRow = (rs, i) -> parse(rs.getString("json"), rs.getString("group_id"));
    /** The property the group column is echoed as, on every record that has it. */
    private static final String GROUP_PROPERTY = "groupId";

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

    /** Spring wires it on every store; a test that is about group visibility calls it by hand. */
    @Autowired
    public void setGroupContext(GroupContext groupContext) {
        this.groupContext = groupContext;
    }

    /** The discriminator this store's rows carry: "flow", "mcp", "facade-profile", … */
    public String kind() {
        return kind;
    }

    protected abstract String idOf(T item);

    protected abstract T withId(T item, String id);

    protected abstract String sortKey(T item);

    /** The organization a call that names none acts for. */
    private String scope() {
        return orgContext.currentOrganizationId();
    }

    /** A condition and its arguments, so the group predicate can be appended to any of them. */
    private record Where(String sql, Object[] args) {
    }

    /**
     * {@code condition} narrowed to the group-scoped rows the caller may see — unchanged when
     * nothing is hidden from them (an admin, no principal, or a store built outside a container).
     */
    private Where visible(String condition, Object... args) {
        Optional<GroupContext.Predicate> predicate = groupContext == null
                ? Optional.empty() : groupContext.predicate("group_id");
        if (predicate.isEmpty()) return new Where(condition, args);
        Object[] all = new Object[args.length + predicate.get().args().size()];
        System.arraycopy(args, 0, all, 0, args.length);
        for (int i = 0; i < predicate.get().args().size(); i++) {
            all[args.length + i] = predicate.get().args().get(i);
        }
        return new Where(condition + " and " + predicate.get().sql(), all);
    }

    public List<T> list() {
        return listIn(scope());
    }

    public List<T> listIn(String organizationId) {
        Where where = visible("kind = ? and organization_id = ?", kind, organizationId);
        return listWhere(where.sql(), where.args());
    }

    /**
     * Every organization's records. For the schedulers only — a cron, a mail poll or a folder
     * watch has to fire for every organization's flows, and has no principal to be scoped by.
     * Nothing that answers an HTTP request for a person should call this.
     */
    public List<T> listAcrossOrganizations() {
        return listWhere("kind = ?", kind);
    }

    public Optional<T> get(String id) {
        return getIn(scope(), id);
    }

    /**
     * Empty for an id that exists in another organization — or in a group the caller is not in —
     * exactly as for one that does not exist.
     */
    public Optional<T> getIn(String organizationId, String id) {
        if (!available) return Optional.empty();
        String safe = Ids.sanitize(id, BAD_ID);
        Where where = visible("kind = ? and id = ? and organization_id = ?", kind, safe, organizationId);
        return getWhere(safe, where.sql(), where.args());
    }

    /**
     * A record by id whichever organization holds it. For callers whose authorization is not a
     * session but the id itself plus a secret of its own — a webhook delivery, a published-flow
     * token, a trigger firing the flow it was scheduled for.
     */
    public Optional<T> getAcrossOrganizations(String id) {
        if (!available) return Optional.empty();
        String safe = Ids.sanitize(id, BAD_ID);
        return getWhere(safe, "kind = ? and id = ?", kind, safe);
    }

    /** The rows matching {@code condition}, or nothing when the database cannot be read. */
    private List<T> listWhere(String condition, Object... args) {
        if (!available) return List.of();
        try {
            // Sorted in SQL, case-insensitively, to match what the file-backed version did.
            return jdbc.query("select json, group_id from resources where " + condition
                            + " order by lower(coalesce(sort_key, '')), id", jsonRow, args)
                    .stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            log.warn("Could not list {} records: {}", kind, e.getMessage());
            return List.of();
        }
    }

    private Optional<T> getWhere(String id, String condition, Object... args) {
        try {
            return jdbc.query("select json, group_id from resources where " + condition, jsonRow, args)
                    .stream().findFirst().filter(Objects::nonNull);
        } catch (RuntimeException e) {
            log.warn("Could not read {} {}: {}", kind, id, e.getMessage());
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
        Where where = visible("kind = ? and id = ? and organization_id = ?",
                kind, Ids.sanitize(id, BAD_ID), organizationId);
        return jdbc.update("delete from resources where " + where.sql(), where.args()) > 0;
    }

    // ---- groups ----

    /**
     * The group a record is scoped to, or empty for one the whole organization sees — and for an
     * id that names nothing, which a caller tells apart with {@link #get} first. Unfiltered: the
     * question is asked of a row the caller has already been allowed to see, or by the run
     * service for the flow it is launching.
     */
    public Optional<String> groupOf(String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        try {
            return jdbc.queryForList("select group_id from resources where kind = ? and id = ?",
                            String.class, kind, Ids.sanitize(id, BAD_ID))
                    .stream().filter(Objects::nonNull).findFirst();
        } catch (RuntimeException e) {
            log.warn("Could not read the group of {} {}: {}", kind, id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Scopes a record to a group, or to the whole organization with a null {@code groupId}. The
     * one write that changes who sees a row; the rules of who may ask for it are the group
     * service's, which checks membership and the license before calling this.
     *
     * @return false when the organization has no such record
     */
    public boolean assignGroup(String organizationId, String id, String groupId) {
        requireAvailable();
        return jdbc.update("update resources set group_id = ? where kind = ? and id = ? and organization_id = ?",
                groupId, kind, Ids.sanitize(id, BAD_ID), organizationId) > 0;
    }

    private void write(String organizationId, String id, T item) {
        String json;
        try {
            // Without the group: the column is the one truth about who sees a row, and a record
            // that arrived from a browser carrying a group it does not belong to — or lost the
            // one it has — must change nothing about that on save.
            com.fasterxml.jackson.databind.JsonNode tree = mapper.valueToTree(item);
            if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.remove(GROUP_PROPERTY);
            json = mapper.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save " + kind + " " + id + ": " + e.getMessage(), e);
        }
        int written;
        try {
            // The update half applies only to a row this organization owns — and, for a person,
            // to one they may see: with the guard, an id that collides with another organization's
            // record, or with a record of a group the caller is not in, touches nothing and is
            // reported below, instead of quietly becoming the caller's record. The group column is
            // not in the statement at all: a new row is the organization's, an existing one keeps
            // the group it had.
            Where guard = visible("resources.organization_id = excluded.organization_id");
            written = jdbc.update("""
                    insert into resources (kind, id, sort_key, json, updated_at, organization_id)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (kind, id) do update
                       set sort_key = excluded.sort_key,
                           json = excluded.json,
                           updated_at = excluded.updated_at
                    """ + " where " + guard.sql().replace("group_id", "resources.group_id"),
                    withArgs(new Object[] {kind, id, sortKey(item), json, System.currentTimeMillis(), organizationId},
                            guard.args()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save " + kind + " " + id + ": " + e.getMessage(), e);
        }
        if (written == 0) {
            throw new IllegalArgumentException("The id " + id + " already names a " + kind
                    + " in another organization, or in a group you are not in. Save it under a new id.");
        }
    }

    private static Object[] withArgs(Object[] first, Object[] rest) {
        Object[] all = new Object[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

    /**
     * The record, with the row's group stamped onto it as {@code groupId} — the records that can
     * be scoped carry the component; for the rest the property is simply absent. Null rather
     * than throwing, so one unreadable row cannot hide every other record.
     */
    private T parse(String json, String groupId) {
        try {
            com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(json);
            if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                if (groupId == null) {
                    object.remove(GROUP_PROPERTY);
                } else {
                    object.put(GROUP_PROPERTY, groupId);
                }
            }
            return mapper.treeToValue(tree, type);
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
