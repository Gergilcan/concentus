package com.concentus.groups;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code group_policies} table: one {@link GroupPolicy} per group, as JSON, keyed by the
 * group's id.
 *
 * <p>Its own table rather than a row in {@code resources}, where the organization's policy lives:
 * every read of {@code resources} is now filtered by who may see the row, and a policy is not a
 * thing anybody "sees" — it is read by the run service on a thread with no principal, for a flow
 * whose group the caller may not even be in. A table nothing filters is the honest shape.
 */
@Component
public class GroupPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(GroupPolicyStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile boolean available;

    public GroupPolicyStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Public for the tests that wire this outside a container; the probe decides availability. */
    @PostConstruct
    public void init() {
        try {
            jdbc.queryForObject("select count(*) from group_policies", Integer.class);
            available = true;
        } catch (Exception e) {
            available = false;
            log.warn("Group policies unavailable: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** The group's policy, or empty when it never wrote one — which reads as inheriting everything. */
    public Optional<GroupPolicy> get(String groupId) {
        if (!available || groupId == null || groupId.isBlank()) return Optional.empty();
        try {
            return jdbc.queryForList("select json from group_policies where group_id = ?", String.class, groupId)
                    .stream().findFirst().map(this::parse).filter(p -> p != null);
        } catch (RuntimeException e) {
            log.warn("Could not read the policy of group {}: {}", groupId, e.getMessage());
            return Optional.empty();
        }
    }

    public GroupPolicy save(String organizationId, String groupId, GroupPolicy policy) {
        requireAvailable();
        String json;
        try {
            json = mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the group policy: " + e.getMessage(), e);
        }
        jdbc.update("""
                insert into group_policies (group_id, organization_id, json) values (?, ?, ?)
                on conflict (group_id) do update set json = excluded.json
                """, groupId, organizationId, json);
        return policy;
    }

    public void delete(String groupId) {
        requireAvailable();
        jdbc.update("delete from group_policies where group_id = ?", groupId);
    }

    private GroupPolicy parse(String json) {
        try {
            return mapper.readValue(json, GroupPolicy.class);
        } catch (Exception e) {
            log.warn("Skipping an unreadable group policy: {}", e.getMessage());
            return null;
        }
    }

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("The database is unavailable, so group policies cannot be "
                    + "saved. Check the database settings under Resources → Storage.");
        }
    }
}
