package com.concentus.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Which external identity an account signs in with.
 *
 * <p>Kept apart from the account itself because an address is not an identity. The same person may
 * arrive as name@company.com through Entra today and through something else tomorrow, and the
 * account — with its role, and its authorship on every flow version it ever saved — has to be the
 * thing that survives that.
 *
 * <p>The key is the provider's own id for the person, never their address. Addresses are reassigned
 * when people leave; matching on one would eventually hand a leaver's flows, and their role, to
 * whoever inherited the mailbox.
 */
@Component
public class UserIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityStore.class);

    private final JdbcTemplate jdbc;

    public UserIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param provider "microsoft"
     * @param subject  the provider's immutable id for the person
     */
    public record Identity(String provider, String subject, String userId, String organizationId,
                           String email, long createdAt) {
    }

    public Optional<Identity> find(String provider, String subject) {
        if (provider == null || subject == null) return Optional.empty();
        try {
            return jdbc.query("select * from user_identities where provider = ? and subject = ?",
                    MAPPER, provider, subject).stream().findFirst();
        } catch (DataAccessException e) {
            // A database that cannot be read means nobody signs in through a provider, which is the
            // same answer as "not linked" and one the caller already handles.
            log.warn("Identity lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Idempotent: signing in twice through the same provider must not be an error. */
    public void link(String provider, String subject, String userId, String organizationId, String email) {
        jdbc.update("""
                insert into user_identities (provider, subject, user_id, organization_id, email, created_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (provider, subject) do update set email = excluded.email
                """, provider, subject, userId, organizationId, email, System.currentTimeMillis());
    }

    private static final RowMapper<Identity> MAPPER = (rs, i) -> new Identity(
            rs.getString("provider"), rs.getString("subject"), rs.getString("user_id"),
            rs.getString("organization_id"), rs.getString("email"), rs.getLong("created_at"));
}
