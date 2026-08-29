package com.concentus.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * The accounts one browser has signed in with, so switching between them costs a click.
 *
 * <p>Checking what an operator can see and then going back to being an admin to change it is the
 * loop anybody setting up permissions is in. Without this it is sign out, retype an address,
 * retype a password, and the same again in reverse — enough friction that the check stops getting
 * done, which is the failure this exists to prevent.
 *
 * <p><b>An account is attached only by signing into it.</b> Nothing here grants access: the rows
 * record that this browser has already proved, with a password or a provider, that it may be each
 * of these people. What the browser holds is one opaque device id in an HttpOnly cookie, so a page
 * cannot read it and no second credential is kept where script can reach it.
 *
 * <p>What it does grant, plainly: whoever has this browser can become any account attached to it.
 * That is what staying signed in has always meant, and it is why detaching is one click and why
 * attachments expire on their own.
 */
@Component
public class DeviceAccountStore {

    private static final Logger log = LoggerFactory.getLogger(DeviceAccountStore.class);

    private final JdbcTemplate jdbc;
    private final long keepMillis;

    public DeviceAccountStore(JdbcTemplate jdbc,
                              @Value("${app.auth.remember-me-days:30}") int rememberMeDays) {
        this.jdbc = jdbc;
        // The same window as staying signed in. A switcher offering an account whose session died
        // weeks ago would be offering a sign-in screen with extra steps.
        this.keepMillis = Duration.ofDays(Math.max(1, rememberMeDays)).toMillis();
    }

    /** One account this browser may switch to. */
    public record Attached(String userId, String organizationId, String email, long addedAt,
                           long lastUsedAt) {
    }

    /**
     * Records that this browser has signed into that account. Idempotent — signing in again is
     * what keeps an attachment alive, not a duplicate.
     */
    public void attach(String deviceId, Accounts.UserAccount account) {
        if (deviceId == null || deviceId.isBlank() || account == null) return;
        long now = System.currentTimeMillis();
        try {
            jdbc.update("""
                    insert into device_accounts
                        (device_id, user_id, organization_id, email, added_at, last_used_at)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (device_id, user_id)
                    do update set email = excluded.email, last_used_at = excluded.last_used_at
                    """, deviceId, account.id(), account.organizationId(), account.email(), now, now);
        } catch (DataAccessException e) {
            // Convenience, not access: failing to record it must never fail the sign-in itself.
            log.warn("Could not remember this account on this device: {}", e.getMessage());
        }
    }

    /** Everything this browser may switch to, most recently used first. Expired rows are dropped. */
    public List<Attached> forDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return List.of();
        try {
            sweepExpired();
            return jdbc.query("""
                    select * from device_accounts
                    where device_id = ? and last_used_at >= ?
                    order by last_used_at desc
                    """, MAPPER, deviceId, System.currentTimeMillis() - keepMillis);
        } catch (DataAccessException e) {
            log.warn("Could not read this device's accounts: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether this browser has signed into that account and may switch back to it.
     *
     * <p>The whole authorization for a switch. Asked against the device id from the cookie and
     * never against anything in the request body, so naming somebody else's account id answers
     * "no" rather than becoming them.
     */
    public boolean isAttached(String deviceId, String userId) {
        return forDevice(deviceId).stream().anyMatch(a -> a.userId().equals(userId));
    }

    /** Moves an account to the front and keeps it from expiring. */
    public void touch(String deviceId, String userId) {
        try {
            jdbc.update("update device_accounts set last_used_at = ? where device_id = ? and user_id = ?",
                    System.currentTimeMillis(), deviceId, userId);
        } catch (DataAccessException e) {
            log.warn("Could not record the switch: {}", e.getMessage());
        }
    }

    /** Forgets one account on this browser. Signing into it again brings it back. */
    public void detach(String deviceId, String userId) {
        try {
            jdbc.update("delete from device_accounts where device_id = ? and user_id = ?",
                    deviceId, userId);
        } catch (DataAccessException e) {
            log.warn("Could not forget the account: {}", e.getMessage());
        }
    }

    private void sweepExpired() {
        jdbc.update("delete from device_accounts where last_used_at < ?",
                System.currentTimeMillis() - keepMillis);
    }

    private static final RowMapper<Attached> MAPPER = (rs, i) -> new Attached(
            rs.getString("user_id"), rs.getString("organization_id"), rs.getString("email"),
            rs.getLong("added_at"), rs.getLong("last_used_at"));
}
