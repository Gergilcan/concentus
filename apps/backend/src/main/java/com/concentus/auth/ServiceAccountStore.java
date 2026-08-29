package com.concentus.auth;

import com.concentus.support.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Service accounts, in PostgreSQL.
 *
 * <p>Every write is scoped by organization as well as by id, the way {@link AccountStore#updateRole}
 * is: an id arrives from a request, the organization from the caller's own session, and only the
 * pair addresses a row — so a guessed id from another tenant renames or revokes nothing.
 *
 * <p>The one lookup that is NOT scoped is {@link #findByTokenHash}: the token is presented before
 * anything is known about who presents it, and the row it resolves to is what says which
 * organization the request belongs to. That is the whole authorization — the hash is unique, and
 * a token either resolves to exactly one row or to none.
 */
@Component
public class ServiceAccountStore {

    private final JdbcTemplate jdbc;

    public ServiceAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a row. {@code tokenHash} is the hash — this store never sees a token. */
    public ServiceAccount create(String organizationId, String name, String role, String tokenHash,
                                 String createdBy) {
        ServiceAccount account = new ServiceAccount(Ids.generate("sa_", 10), organizationId, name.trim(),
                role, tokenHash, createdBy, System.currentTimeMillis(), null, null);
        jdbc.update("""
                insert into service_accounts
                  (id, organization_id, name, role, token_hash, created_by, created_at, last_used_at, revoked_at)
                values (?,?,?,?,?,?,?,null,null)
                """,
                account.id(), account.organizationId(), account.name(), account.role(),
                account.tokenHash(), account.createdBy(), account.createdAt());
        return account;
    }

    /** The row for a presented token's hash, revoked or not — the caller decides what revoked means. */
    public Optional<ServiceAccount> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
        return jdbc.query("select * from service_accounts where token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    public Optional<ServiceAccount> find(String id, String organizationId) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("select * from service_accounts where id = ? and organization_id = ?",
                MAPPER, id, organizationId).stream().findFirst();
    }

    /** Every row of the organization, revoked ones included, oldest first. */
    public List<ServiceAccount> list(String organizationId) {
        return jdbc.query("select * from service_accounts where organization_id = ? order by created_at",
                MAPPER, organizationId);
    }

    /** How many tokens of the organization still work — what the Team cap counts. */
    public long countActive(String organizationId) {
        Long n = jdbc.queryForObject(
                "select count(*) from service_accounts where organization_id = ? and revoked_at is null",
                Long.class, organizationId);
        return n == null ? 0 : n;
    }

    public boolean rename(String id, String organizationId, String name) {
        return jdbc.update("update service_accounts set name = ? where id = ? and organization_id = ?",
                name.trim(), id, organizationId) > 0;
    }

    /** Stamps the row rather than deleting it; false when there was nothing to revoke. */
    public boolean revoke(String id, String organizationId, long now) {
        return jdbc.update("""
                update service_accounts set revoked_at = ?
                where id = ? and organization_id = ? and revoked_at is null
                """, now, id, organizationId) > 0;
    }

    public void touchLastUsed(String id, long now) {
        jdbc.update("update service_accounts set last_used_at = ? where id = ?", now, id);
    }

    private static final RowMapper<ServiceAccount> MAPPER = (rs, i) -> new ServiceAccount(
            rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
            rs.getString("role"), rs.getString("token_hash"), rs.getString("created_by"),
            rs.getLong("created_at"), nullable(rs.getObject("last_used_at")),
            nullable(rs.getObject("revoked_at")));

    private static Long nullable(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
