package com.concentus.config;

import com.concentus.secrets.SecretCipher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The settings this installation has been given, as opposed to the ones its machine was started
 * with.
 *
 * <p>Reads are answered from a snapshot held in memory and refreshed when something is written, so
 * asking for a setting costs nothing and can be done wherever the value is used rather than only
 * at construction. That is the point: a value read once, into a field, at startup, is a value that
 * cannot be changed without a restart no matter where it is stored.
 *
 * <p>Secrets are sealed with the same key that protects stored credentials, when there is one,
 * and are never read back out to a client — a client secret filed under "settings" is still a
 * credential. One sealed under a key this installation does not have is treated as unset and
 * reported as {@link #isLocked locked}, so the screen can ask for it again instead of the
 * provider failing somewhere far from here with ciphertext for a secret.
 */
@Component
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    /** organization -> key -> value, already opened. Rebuilt on write, not on read. */
    private volatile Map<String, Map<String, String>> snapshot = Map.of();
    /** organization -> keys whose stored secret this installation cannot open. */
    private volatile Map<String, Set<String>> locked = Map.of();
    /**
     * group -> key -> value: what a group inside an organization overrides. Never a secret — the
     * catalogue only lets the per-run keys be group-scoped, and none of those is one — so these
     * rows are read and written as they are.
     */
    private volatile Map<String, Map<String, String>> groupSnapshot = Map.of();
    private volatile boolean available;

    public SettingsStore(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @PostConstruct
    void load() {
        try {
            Map<String, Map<String, String>> next = new LinkedHashMap<>();
            Map<String, Set<String>> nextLocked = new LinkedHashMap<>();
            jdbc.query("select organization_id, key, value, secret from settings", rs -> {
                String organizationId = rs.getString("organization_id");
                String key = rs.getString("key");
                String value = rs.getString("value");
                if (rs.getBoolean("secret") && value != null && !value.isBlank()) {
                    SecretCipher.Reading reading = cipher.open(value);
                    if (reading.locked()) {
                        // Unset rather than ciphertext: handing the sealed text on would reach a
                        // provider as a wrong secret and fail where nobody would connect it to
                        // this. Named here and flagged for the screen, because "the token is
                        // wrong" is what it looks like from anywhere else.
                        log.warn("Setting {} is locked: it was encrypted with a key this "
                                + "installation does not have. Enter it again in Settings.", key);
                        nextLocked.computeIfAbsent(organizationId, k -> new LinkedHashSet<>()).add(key);
                    } else if (reading.state() == SecretCipher.Reading.State.CLEAR && cipher.hasKey()) {
                        // Written before this installation had a key. The value is in hand, so it
                        // goes sealed now rather than waiting for somebody to re-save it.
                        jdbc.update("update settings set value = ? where organization_id = ? "
                                + "and key = ? and value = ?",
                                cipher.wrap(value), organizationId, key, value);
                        log.info("Secret setting {} was stored in the clear and is now encrypted.", key);
                    }
                    value = reading.orNull();
                }
                next.computeIfAbsent(organizationId, k -> new LinkedHashMap<>()).put(key, value);
            });
            snapshot = next;
            locked = nextLocked;
            available = true;
            loadGroups();
        } catch (DataAccessException e) {
            // Settings are overrides; without them the environment's values stand, which is
            // exactly how this installation behaved before the table existed.
            available = false;
            log.warn("Settings could not be read ({}); using configuration as given.", e.getMessage());
        }
    }

    /**
     * The group rows, apart from the organization ones: a database this migration has not reached
     * yet still has settings, and must not lose them over a table that is not there.
     */
    private void loadGroups() {
        try {
            Map<String, Map<String, String>> next = new LinkedHashMap<>();
            jdbc.query("select group_id, key, value from group_settings", (java.sql.ResultSet rs) -> {
                next.computeIfAbsent(rs.getString("group_id"), k -> new LinkedHashMap<>())
                        .put(rs.getString("key"), rs.getString("value"));
            });
            groupSnapshot = next;
        } catch (DataAccessException e) {
            groupSnapshot = Map.of();
            log.warn("Group settings could not be read ({}); groups inherit their organization's.",
                    e.getMessage());
        }
    }

    /** Whether the table could be read at all. False means every lookup falls through. */
    public boolean isAvailable() {
        return available;
    }

    // ---- per group ----

    /** A group's own override for a key, if it has one. */
    public Optional<String> groupSetting(String groupId, String key) {
        if (groupId == null) return Optional.empty();
        String value = groupSnapshot.getOrDefault(groupId, Map.of()).get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** Every override one group holds, by key. */
    public Map<String, String> groupSettings(String groupId) {
        if (groupId == null) return Map.of();
        return Map.copyOf(groupSnapshot.getOrDefault(groupId, Map.of()));
    }

    /**
     * Replaces a group's overrides with {@code values}: a key absent from the map is cleared, a
     * blank value clears too. "Replaces" rather than "merges" so the Settings tab of a group can
     * send what it shows and be sure that is what the group now has.
     */
    public void replaceGroupSettings(String organizationId, String groupId, Map<String, String> values) {
        jdbc.update("delete from group_settings where group_id = ?", groupId);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            jdbc.update("insert into group_settings (organization_id, group_id, key, value, updated_at) "
                    + "values (?, ?, ?, ?, ?)", organizationId, groupId, e.getKey(), e.getValue().trim(), now);
        }
        loadGroups();
    }

    /** Forgets every override of a group — what deleting the group does. */
    public void clearGroup(String groupId) {
        jdbc.update("delete from group_settings where group_id = ?", groupId);
        loadGroups();
    }

    /** The stored override for a key, if this organization has one. */
    public Optional<String> get(String organizationId, String key) {
        String value = snapshot.getOrDefault(organizationId, Map.of()).get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * True when a secret is stored for this key but sealed under a key this installation does
     * not have. It reads as unset; the fix is to enter it again.
     */
    public boolean isLocked(String organizationId, String key) {
        return locked.getOrDefault(organizationId, Set.of()).contains(key);
    }

    /** Every override this organization has, secrets included — for the resolver, not for a client. */
    public Map<String, String> all(String organizationId) {
        return Map.copyOf(snapshot.getOrDefault(organizationId, Map.of()));
    }

    /**
     * Saves an override, or removes it when the value is blank.
     *
     * <p>Blank means "go back to the configured value" rather than "set it to empty", which is the
     * only reading that leaves a way back: without it, clearing a field would pin the setting to
     * an empty string and the environment's value could never be reached again.
     */
    public void put(String organizationId, String key, String value, boolean isSecret,
                    String updatedBy) {
        if (value == null || value.isBlank()) {
            jdbc.update("delete from settings where organization_id = ? and key = ?",
                    organizationId, key);
        } else {
            jdbc.update("""
                    insert into settings (organization_id, key, value, secret, updated_at, updated_by)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (organization_id, key) do update
                    set value = excluded.value, secret = excluded.secret,
                        updated_at = excluded.updated_at, updated_by = excluded.updated_by
                    """, organizationId, key, isSecret ? cipher.wrap(value) : value, isSecret,
                    System.currentTimeMillis(), updatedBy);
        }
        load();
    }
}
