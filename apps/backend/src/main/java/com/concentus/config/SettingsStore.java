package com.concentus.config;

import com.concentus.secrets.LegacySecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The settings this installation has been given, as opposed to the ones its machine was started
 * with.
 *
 * <p>Reads are answered from a snapshot held in memory and refreshed when something is written, so
 * asking for a setting costs nothing and can be done wherever the value is used rather than only
 * at construction. That is the point: a value read once, into a field, at startup, is a value that
 * cannot be changed without a restart no matter where it is stored.
 *
 * <p>Secrets are sealed with the same key that protects stored credentials, and are never read
 * back out to a client — a client secret filed under "settings" is still a credential.
 */
@Component
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final JdbcTemplate jdbc;
    private final LegacySecrets legacy;
    /** organization -> key -> value, already unsealed. Rebuilt on write, not on read. */
    private volatile Map<String, Map<String, String>> snapshot = Map.of();
    private volatile boolean available;

    public SettingsStore(JdbcTemplate jdbc, LegacySecrets legacy) {
        this.jdbc = jdbc;
        this.legacy = legacy;
    }

    @jakarta.annotation.PostConstruct
    void load() {
        try {
            Map<String, Map<String, String>> next = new LinkedHashMap<>();
            jdbc.query("select organization_id, key, value, secret from settings", rs -> {
                String organizationId = rs.getString("organization_id");
                String value = rs.getString("value");
                if (rs.getBoolean("secret") && value != null && !value.isBlank()) {
                    // Stored in the clear now. A value still carrying the old encryption is opened
                    // when this installation has the key that sealed it, and dropped when it does
                    // not — handing ciphertext on would reach a provider as a wrong secret and
                    // fail somewhere far from here.
                    String opened = legacy.read(value).orElse(null);
                    if (opened == null) {
                        log.error("Setting {} is still encrypted and this installation cannot open "
                                + "it; treating it as unset. Enter it again to store it in the "
                                + "clear.", rs.getString("key"));
                    }
                    value = opened;
                }
                next.computeIfAbsent(organizationId, k -> new LinkedHashMap<>())
                        .put(rs.getString("key"), value);
            });
            snapshot = next;
            available = true;
        } catch (DataAccessException e) {
            // Settings are overrides; without them the environment's values stand, which is
            // exactly how this installation behaved before the table existed.
            available = false;
            log.warn("Settings could not be read ({}); using configuration as given.", e.getMessage());
        }
    }

    /** Whether the table could be read at all. False means every lookup falls through. */
    public boolean isAvailable() {
        return available;
    }

    /** The stored override for a key, if this organization has one. */
    public Optional<String> get(String organizationId, String key) {
        String value = snapshot.getOrDefault(organizationId, Map.of()).get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
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
                    """, organizationId, key, value, isSecret,
                    System.currentTimeMillis(), updatedBy);
        }
        load();
    }
}
