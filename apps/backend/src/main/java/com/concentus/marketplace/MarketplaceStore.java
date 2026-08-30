package com.concentus.marketplace;

import com.concentus.support.Ids;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code marketplace_items} and {@code marketplace_installs} tables.
 *
 * <p>Visibility is a property of the query, not of the caller's good manners. Every read a person
 * triggers goes through {@link #listVisible} or {@link #findVisible}, which take who is asking and
 * answer only with what that person may see — published global items, their own organization's
 * items whatever their status, their own submissions, and (for a curator) every global item still
 * waiting. An id that names something outside that set is answered exactly as an id that names
 * nothing, so a guessed id from another organization's private shelf confirms nothing.
 *
 * <p>The unscoped reads — {@link #find}, {@link #delete} — are for the code that acts on a row it
 * has already been allowed to see, and for the seeder, which acts for nobody.
 *
 * <p>Like the other stores: a missing table is reported, never thrown, and reads then answer
 * empty while writes refuse.
 */
@Component
public class MarketplaceStore {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStore.class);

    /**
     * Who is asking, reduced to what the visibility query needs.
     *
     * @param groupIds the groups the viewer is in — a group item is visible to its group's members
     * @param orgAdmin whether the viewer administers their organization — and so sees every group's items in it
     */
    public record Viewer(String userId, String organizationId, boolean curator, java.util.Set<String> groupIds,
                         boolean orgAdmin) {

        /** A viewer in no group and not an admin: what the tests that predate groups build. */
        public Viewer(String userId, String organizationId, boolean curator) {
            this(userId, organizationId, curator, java.util.Set.of(), false);
        }

        public java.util.Set<String> groupIdsOrEmpty() {
            return groupIds == null ? java.util.Set.of() : groupIds;
        }
    }

    /** One organization's install of one item. */
    public record Install(String itemId, String organizationId, String resourceId, int version,
                          long installedAt, String installedBy) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile boolean available;

    public MarketplaceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Public for the tests that wire this outside a container; the probe decides availability. */
    @PostConstruct
    public void init() {
        try {
            jdbc.queryForObject("select count(*) from marketplace_items", Integer.class);
            jdbc.queryForObject("select count(*) from marketplace_installs", Integer.class);
            available = true;
        } catch (Exception e) {
            available = false;
            log.warn("Marketplace unavailable: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ------------------------------------------------------------------ items

    private static final String SELECT = """
            select i.*, (select count(*) from marketplace_installs n where n.item_id = i.id) as installs
            from marketplace_items i
            """;

    /**
     * Everything {@code viewer} may see, newest first. The caller narrows and sorts; the set
     * itself is decided here and nowhere else.
     */
    public List<MarketplaceItem> listVisible(Viewer viewer) {
        if (!available) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" where ");
        visibility(viewer, sql, args);
        sql.append(" order by i.created_at desc, i.id");
        return jdbc.query(sql.toString(), this::row, args.toArray());
    }

    /** One item by id, or empty when it does not exist OR {@code viewer} may not see it. */
    public Optional<MarketplaceItem> findVisible(String id, Viewer viewer) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        List<Object> args = new ArrayList<>();
        args.add(id);
        StringBuilder sql = new StringBuilder(SELECT).append(" where i.id = ? and ");
        visibility(viewer, sql, args);
        return jdbc.query(sql.toString(), this::row, args.toArray()).stream().findFirst();
    }

    /**
     * The clauses of "what this person may see", as one OR the SQL binds: published global items;
     * their organization's items whatever their status — except those of a group, which only the
     * group's members and the organization's admins see; their own submissions; and, for a
     * curator, every global item still waiting.
     */
    private static void visibility(Viewer viewer, StringBuilder sql, List<Object> args) {
        sql.append("((i.scope = 'global' and i.status = 'published')");
        sql.append(" or (i.organization_id = ? and i.scope <> 'group')");
        args.add(viewer.organizationId());
        if (!viewer.groupIdsOrEmpty().isEmpty()) {
            sql.append(" or (i.scope = 'group' and i.group_id in (");
            boolean first = true;
            for (String groupId : viewer.groupIdsOrEmpty()) {
                sql.append(first ? "?" : ", ?");
                args.add(groupId);
                first = false;
            }
            sql.append("))");
        }
        if (viewer.orgAdmin()) {
            sql.append(" or (i.scope = 'group' and i.organization_id = ?)");
            args.add(viewer.organizationId());
        }
        sql.append(" or i.author_user_id = ?");
        args.add(viewer.userId());
        if (viewer.curator()) {
            sql.append(" or (i.scope = 'global' and i.status = 'pending')");
        }
        sql.append(")");
    }

    /** Whoever holds it — for code acting on a row it has already been allowed to see, and the seeder. */
    public Optional<MarketplaceItem> find(String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        return jdbc.query(SELECT + " where i.id = ?", this::row, id).stream().findFirst();
    }

    /** Every built-in row, for the seeder's comparison. */
    public List<MarketplaceItem> listBuiltIn() {
        if (!available) return List.of();
        return jdbc.query(SELECT + " where i.built_in order by i.id", this::row);
    }

    /** How many global items still wait for a curator. */
    public int countPendingGlobal() {
        if (!available) return 0;
        Integer n = jdbc.queryForObject(
                "select count(*) from marketplace_items where scope = 'global' and status = 'pending'",
                Integer.class);
        return n == null ? 0 : n;
    }

    /** Inserts; mints an id when the item has none. Returns what was written. */
    public MarketplaceItem insert(MarketplaceItem item, String builtInHash) {
        requireAvailable();
        String id = item.id() == null || item.id().isBlank() ? Ids.generate("mkt_", 12)
                : Ids.sanitize(item.id(), "Invalid marketplace id: ");
        MarketplaceItem toSave = new MarketplaceItem(id, item.kind(), item.name(), item.summary(),
                item.description(), item.tagsOrEmpty(), item.version(), item.scope(),
                item.organizationId(), item.groupId(), item.status(), item.rejection(), item.author(),
                item.payload(), item.icon(), 0, item.builtIn(), item.createdAt(), item.updatedAt(),
                item.publishedAt(), item.approvedBy());
        jdbc.update("""
                insert into marketplace_items
                  (id, kind, name, summary, description, tags, version, scope, organization_id, group_id,
                   status, rejection, author_user_id, author_email, payload, icon, built_in, built_in_hash,
                   created_at, updated_at, published_at, approved_by)
                values (?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                """,
                toSave.id(), toSave.kind(), toSave.name(), toSave.summary(), toSave.description(),
                json(toSave.tagsOrEmpty()), toSave.version(), toSave.scope(), toSave.organizationId(),
                toSave.groupId(), toSave.status(), toSave.rejection(), toSave.author().userId(),
                toSave.author().email(), json(toSave.payload()), toSave.icon(), toSave.builtIn(), builtInHash,
                toSave.createdAt(), toSave.updatedAt(), toSave.publishedAt(), toSave.approvedBy());
        return toSave;
    }

    /** Rewrites every editable column of the row {@code item.id()} names. The install count is derived, never written. */
    public void update(MarketplaceItem item, String builtInHash) {
        requireAvailable();
        jdbc.update("""
                update marketplace_items
                   set kind = ?, name = ?, summary = ?, description = ?, tags = ?::jsonb, version = ?,
                       scope = ?, organization_id = ?, group_id = ?, status = ?, rejection = ?,
                       payload = ?::jsonb, icon = ?, built_in = ?, built_in_hash = ?, updated_at = ?,
                       published_at = ?, approved_by = ?
                 where id = ?
                """,
                item.kind(), item.name(), item.summary(), item.description(), json(item.tagsOrEmpty()),
                item.version(), item.scope(), item.organizationId(), item.groupId(), item.status(),
                item.rejection(), json(item.payload()), item.icon(), item.builtIn(), builtInHash,
                item.updatedAt(), item.publishedAt(), item.approvedBy(), item.id());
    }

    /** Removes the item and every organization's record of installing it. The resources those installs created stay where they are. */
    public boolean delete(String id) {
        requireAvailable();
        jdbc.update("delete from marketplace_installs where item_id = ?", id);
        return jdbc.update("delete from marketplace_items where id = ?", id) > 0;
    }

    /** The hash the seeder wrote for a built-in, or empty for a row that is not one. */
    public Optional<String> builtInHash(String id) {
        if (!available) return Optional.empty();
        return jdbc.queryForList("select built_in_hash from marketplace_items where id = ? and built_in",
                String.class, id).stream().filter(Objects::nonNull).findFirst();
    }

    // ------------------------------------------------------------------ installs

    /** Records an install, replacing an earlier one of the same item in the same organization. */
    public void recordInstall(Install install) {
        requireAvailable();
        jdbc.update("""
                insert into marketplace_installs
                  (item_id, organization_id, resource_id, version, installed_at, installed_by)
                values (?,?,?,?,?,?)
                on conflict (item_id, organization_id) do update
                   set resource_id = excluded.resource_id, version = excluded.version,
                       installed_at = excluded.installed_at, installed_by = excluded.installed_by
                """, install.itemId(), install.organizationId(), install.resourceId(),
                install.version(), install.installedAt(), install.installedBy());
    }

    public Optional<Install> install(String itemId, String organizationId) {
        if (!available) return Optional.empty();
        return jdbc.query("select * from marketplace_installs where item_id = ? and organization_id = ?",
                INSTALL, itemId, organizationId).stream().findFirst();
    }

    /** Everything one organization installed, by item id — one query for a whole list. */
    public Map<String, Install> installsOf(String organizationId) {
        if (!available) return Map.of();
        Map<String, Install> out = new LinkedHashMap<>();
        jdbc.query("select * from marketplace_installs where organization_id = ?", INSTALL, organizationId)
                .forEach(i -> out.put(i.itemId(), i));
        return out;
    }

    public boolean removeInstall(String itemId, String organizationId) {
        requireAvailable();
        return jdbc.update("delete from marketplace_installs where item_id = ? and organization_id = ?",
                itemId, organizationId) > 0;
    }

    // ------------------------------------------------------------------ organizations

    /**
     * The oldest organization row — the one the first account created — which is what curates
     * when nothing is configured. Empty on a database with no organization yet.
     */
    public Optional<String> oldestOrganizationId() {
        if (!available) return Optional.empty();
        try {
            return jdbc.queryForList("select id from organizations order by created_at, id limit 1", String.class)
                    .stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private MarketplaceItem row(ResultSet rs, int i) throws SQLException {
        return new MarketplaceItem(
                rs.getString("id"), rs.getString("kind"), rs.getString("name"), rs.getString("summary"),
                rs.getString("description"), tags(rs.getString("tags")), rs.getInt("version"),
                rs.getString("scope"), rs.getString("organization_id"), rs.getString("group_id"),
                rs.getString("status"), rs.getString("rejection"),
                new MarketplaceItem.Author(rs.getString("author_user_id"), rs.getString("author_email")),
                payload(rs.getString("payload")), rs.getString("icon"), rs.getInt("installs"),
                rs.getBoolean("built_in"), rs.getLong("created_at"), rs.getLong("updated_at"),
                nullable(rs.getObject("published_at")), rs.getString("approved_by"));
    }

    private static final RowMapper<Install> INSTALL = (rs, i) -> new Install(
            rs.getString("item_id"), rs.getString("organization_id"), rs.getString("resource_id"),
            rs.getInt("version"), rs.getLong("installed_at"), rs.getString("installed_by"));

    private List<String> tags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode payload(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Unreadable marketplace payload: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise a marketplace record: " + e.getMessage(), e);
        }
    }

    private static Long nullable(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("The database is unavailable, so the marketplace cannot be "
                    + "written to. Check the database settings under Resources → Storage.");
        }
    }
}
