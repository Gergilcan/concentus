package com.concentus.groups;

import com.concentus.support.Ids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code groups} and {@code group_memberships} tables, and the one write that touches the
 * other tables: deleting a group un-scopes what it held.
 *
 * <p>Nothing here asks who is calling. Every read takes the organization explicitly and the
 * service above decides what the caller may see of it — this class is what the service and the
 * per-request {@link GroupContext} are built on, so it cannot also depend on them.
 *
 * <p>Like the other stores: a missing table is reported, never thrown, and reads then answer
 * empty while writes refuse.
 */
@Component
public class GroupStore {

    private static final Logger log = LoggerFactory.getLogger(GroupStore.class);

    /** What deleting a group did. */
    public record Deleted(boolean deleted, int unscoped) {
    }

    private static final String SELECT = """
            select g.*,
                   (select count(*) from group_memberships m where m.group_id = g.id) as members,
                   (select count(*) from resources r where r.group_id = g.id)
                     + (select count(*) from credentials c where c.group_id = g.id) as resources
            from groups g
            """;

    private static final RowMapper<Group> GROUP = (rs, i) -> new Group(
            rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
            rs.getString("description"), rs.getLong("created_at"), rs.getString("created_by"),
            rs.getInt("members"), rs.getInt("resources"), false);

    private static final RowMapper<GroupMember> MEMBER = (rs, i) -> new GroupMember(
            rs.getString("user_id"), rs.getString("email"), rs.getString("role"),
            rs.getBoolean("manager"), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public GroupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Public for the tests that wire this outside a container; the probe decides availability. */
    @PostConstruct
    public void init() {
        try {
            jdbc.queryForObject("select count(*) from groups", Integer.class);
            jdbc.queryForObject("select count(*) from group_memberships", Integer.class);
            available = true;
        } catch (Exception e) {
            available = false;
            log.warn("Groups unavailable: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ------------------------------------------------------------------ groups

    /** Every group of one organization, by name. Counts filled; the manager flag is the service's. */
    public List<Group> list(String organizationId) {
        if (!available) return List.of();
        try {
            return jdbc.query(SELECT + " where g.organization_id = ? order by lower(g.name), g.id", GROUP,
                    organizationId);
        } catch (RuntimeException e) {
            log.warn("Could not list the groups of {}: {}", organizationId, e.getMessage());
            return List.of();
        }
    }

    /** One group, in one organization — empty for an id another organization holds. */
    public Optional<Group> find(String organizationId, String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        try {
            return jdbc.query(SELECT + " where g.id = ? and g.organization_id = ?", GROUP,
                    Ids.sanitize(id, "Invalid group id: "), organizationId).stream().findFirst();
        } catch (RuntimeException e) {
            log.warn("Could not read group {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** A group's name whichever organization holds it — for a run log or a doctor line naming it. */
    public Optional<String> nameOf(String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        try {
            return jdbc.queryForList("select name from groups where id = ?", String.class, id).stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @throws IllegalArgumentException when the organization already has a group of that name
     */
    public Group create(String organizationId, String name, String description, String createdBy) {
        requireAvailable();
        String id = Ids.generate(Group.ID_PREFIX, 12);
        long now = System.currentTimeMillis();
        try {
            jdbc.update("insert into groups (id, organization_id, name, description, created_at, created_by) "
                    + "values (?, ?, ?, ?, ?, ?)", id, organizationId, name, description, now, createdBy);
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
        return new Group(id, organizationId, name, description, now, createdBy, 0, 0, false);
    }

    /** Renames and re-describes. Empty when the organization has no such group. */
    public Optional<Group> update(String organizationId, String id, String name, String description) {
        requireAvailable();
        try {
            int changed = jdbc.update("update groups set name = ?, description = ? where id = ? and organization_id = ?",
                    name, description, id, organizationId);
            if (changed == 0) return Optional.empty();
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
        return find(organizationId, id);
    }

    /**
     * Removes the group, its memberships, its settings and its policy, and returns to the
     * organization every resource, credential and marketplace item that was scoped to it. A
     * group's runs keep the id: it records which settings and policy they resolved against, which
     * is history, and history is not rewritten by a deletion.
     *
     * <p>The settings and the policy live in their own stores and are cleared by the service,
     * which owns both; this method knows only the tables it reads.
     */
    public Deleted delete(String organizationId, String id) {
        requireAvailable();
        int owned = jdbc.update("delete from groups where id = ? and organization_id = ?", id, organizationId);
        if (owned == 0) return new Deleted(false, 0);
        jdbc.update("delete from group_memberships where group_id = ?", id);
        int unscoped = jdbc.update("update resources set group_id = null where group_id = ?", id);
        unscoped += jdbc.update("update credentials set group_id = null where group_id = ?", id);
        // A marketplace item published to the group becomes an organization item: it was born
        // published and its audience only widens to the organization the group was part of.
        try {
            unscoped += jdbc.update("update marketplace_items set group_id = null, scope = 'organization' "
                    + "where group_id = ?", id);
        } catch (RuntimeException e) {
            log.warn("Could not un-scope the marketplace items of group {}: {}", id, e.getMessage());
        }
        return new Deleted(true, unscoped);
    }

    // ------------------------------------------------------------------ memberships

    /** Who is in the group, with the role each holds in the organization, oldest membership first. */
    public List<GroupMember> members(String organizationId, String groupId) {
        if (!available) return List.of();
        return jdbc.query("""
                select gm.user_id, u.email, coalesce(m.role, u.role) as role, gm.manager, gm.created_at
                from group_memberships gm
                join users u on u.id = gm.user_id
                left join memberships m on m.user_id = gm.user_id and m.organization_id = ?
                where gm.group_id = ?
                order by gm.created_at, u.email
                """, MEMBER, organizationId, groupId);
    }

    /** Whether the account is in the group, and whether as a manager. Empty when not in it. */
    public Optional<Boolean> membership(String groupId, String userId) {
        if (!available || groupId == null || userId == null) return Optional.empty();
        return jdbc.queryForList("select manager from group_memberships where group_id = ? and user_id = ?",
                Boolean.class, groupId, userId).stream().findFirst();
    }

    /** Puts an account into the group, or changes whether it manages it if already in. */
    public void addMember(String groupId, String userId, boolean manager) {
        requireAvailable();
        jdbc.update("""
                insert into group_memberships (group_id, user_id, manager, created_at) values (?, ?, ?, ?)
                on conflict (group_id, user_id) do update set manager = excluded.manager
                """, groupId, userId, manager, System.currentTimeMillis());
    }

    public boolean removeMember(String groupId, String userId) {
        requireAvailable();
        return jdbc.update("delete from group_memberships where group_id = ? and user_id = ?", groupId, userId) > 0;
    }

    /**
     * Every group of {@code organizationId} the account is in, with whether it manages each —
     * the one query {@link GroupContext} runs per request.
     */
    public Map<String, Boolean> membershipsOf(String userId, String organizationId) {
        if (!available || userId == null || organizationId == null) return Map.of();
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    select gm.group_id, gm.manager from group_memberships gm
                    join groups g on g.id = gm.group_id
                    where gm.user_id = ? and g.organization_id = ?
                    order by gm.created_at
                    """, rs -> {
                out.put(rs.getString("group_id"), rs.getBoolean("manager"));
            }, userId, organizationId);
        } catch (RuntimeException e) {
            log.warn("Could not read the groups of {}: {}", userId, e.getMessage());
            return Map.of();
        }
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    private static IllegalArgumentException nameTaken(String name) {
        return new IllegalArgumentException("A group named '" + name + "' already exists in this organization.");
    }

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("The database is unavailable, so groups cannot be changed. "
                    + "Check the database settings under Resources → Storage.");
        }
    }
}
