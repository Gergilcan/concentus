package com.concentus.runners;

import com.concentus.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The {@code runners} table. */
@Component
public class RunnerStore {

    private static final Logger log = LoggerFactory.getLogger(RunnerStore.class);

    private static final RowMapper<Runner> MAPPER = (rs, i) -> new Runner(
            rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
            rs.getString("scope"), rs.getString("group_id"), rs.getString("user_id"),
            rs.getString("token_hash"), rs.getString("created_by"), rs.getLong("created_at"),
            nullable(rs.getObject("last_seen_at")), nullable(rs.getObject("revoked_at")));

    private final JdbcTemplate jdbc;

    public RunnerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        try {
            jdbc.queryForObject("select count(*) from runners", Integer.class);
            return true;
        } catch (Exception e) {
            log.debug("runners table unavailable: {}", e.getMessage());
            return false;
        }
    }

    public Runner create(String organizationId, String name, String scope, String groupId, String userId,
                         String tokenHash, String createdBy) {
        String id = Ids.generate(Runner.ID_PREFIX, 12);
        long now = System.currentTimeMillis();
        try {
            jdbc.update("""
                    insert into runners (id, organization_id, name, scope, group_id, user_id, token_hash,
                      created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, organizationId, name.trim(), scope, groupId, userId, tokenHash, createdBy, now);
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
        return new Runner(id, organizationId, name.trim(), scope, groupId, userId, tokenHash, createdBy, now,
                null, null);
    }

    public List<Runner> list(String organizationId) {
        return jdbc.query("select * from runners where organization_id = ? order by lower(name)", MAPPER,
                organizationId);
    }

    public Optional<Runner> find(String organizationId, String id) {
        return jdbc.query("select * from runners where organization_id = ? and id = ?", MAPPER,
                organizationId, id).stream().findFirst();
    }

    /** By id alone — what the socket handshake and a restored run have in hand. */
    public Optional<Runner> findById(String id) {
        return jdbc.query("select * from runners where id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Runner> findByTokenHash(String hash) {
        return jdbc.query("select * from runners where token_hash = ?", MAPPER, hash).stream().findFirst();
    }

    public boolean rename(String organizationId, String id, String name) {
        try {
            return jdbc.update("update runners set name = ? where organization_id = ? and id = ?",
                    name.trim(), organizationId, id) > 0;
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    public boolean revoke(String organizationId, String id, long now) {
        return jdbc.update("update runners set revoked_at = ? where organization_id = ? and id = ? "
                + "and revoked_at is null", now, organizationId, id) > 0;
    }

    public boolean delete(String organizationId, String id) {
        return jdbc.update("delete from runners where organization_id = ? and id = ?", organizationId, id) > 0;
    }

    public void touchLastSeen(String id, long now) {
        jdbc.update("update runners set last_seen_at = ? where id = ?", now, id);
    }

    private static IllegalArgumentException nameTaken(String name) {
        return new IllegalArgumentException("A runner called '" + name.trim()
                + "' already exists in this organization.");
    }

    private static Long nullable(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
