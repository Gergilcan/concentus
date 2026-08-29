package com.concentus.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings every stored secret up to what this installation's key can do, once, at startup.
 *
 * <p>With a key: rows still in the clear are sealed, and rows under the earlier {@code v1:}
 * marker that open with this key are rewritten under the current one. That is what makes "the
 * upgrade brought a key" mean something for credentials entered before it — without this pass, a
 * backup taken the day after the upgrade would still hold every password that no run had touched.
 * A converted database is one empty query per launch.
 *
 * <p>Without a key there is nothing to write, and the pass only reports.
 *
 * <p><b>Nothing is deleted and nothing is guessed.</b> A row this installation cannot open is left
 * exactly as it was — it is somebody else's key, and the value may still be recoverable from the
 * machine that wrote it. What those rows get instead is a log line naming them, because the
 * failure they used to cause is the one that cost a morning: an integration reporting itself
 * "not configured" while its row sits plainly in the table. They are also visible where it
 * matters now — locked in the credentials list, named by the flow doctor — so this line is the
 * operator's copy, not the only copy.
 */
@Component
public class SecretsMigration {

    private static final Logger log = LoggerFactory.getLogger(SecretsMigration.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public SecretsMigration(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** What one table's pass did, for the summary line. */
    private record Result(int sealed, List<String> locked) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void convert() {
        try {
            Result credentials = convertCredentials();
            Result settings = convertSettings();
            report(credentials, settings);
        } catch (DataAccessException e) {
            // An unreachable database is not this class's problem to solve — every store already
            // reports itself unavailable and the app opens far enough to be repaired. It will run
            // again at the next start.
            log.warn("Could not convert stored secrets ({}); will try again next start.",
                    e.getMessage());
        }
    }

    private Result convertCredentials() {
        List<String[]> rows = jdbc.query(
                "select id, label, secret from credentials",
                (rs, n) -> new String[] { rs.getString("id"), rs.getString("label"),
                        rs.getString("secret") });
        int sealed = 0;
        List<String> locked = new ArrayList<>();
        for (String[] row : rows) {
            String next = rewrite(row[2], locked, row[1]);
            if (next == null) continue;
            // Compare-and-set on the stored text: a credential saved between the select and this
            // update was written sealed already and must not be replaced with a stale value.
            sealed += jdbc.update("update credentials set secret = ? where id = ? and secret = ?",
                    next, row[0], row[2]);
        }
        return new Result(sealed, locked);
    }

    private Result convertSettings() {
        List<String[]> rows = jdbc.query(
                "select organization_id, key, value from settings where secret = true",
                (rs, n) -> new String[] { rs.getString("organization_id"), rs.getString("key"),
                        rs.getString("value") });
        int sealed = 0;
        List<String> locked = new ArrayList<>();
        for (String[] row : rows) {
            String next = rewrite(row[2], locked, row[1]);
            if (next == null) continue;
            sealed += jdbc.update("update settings set value = ? where organization_id = ? "
                    + "and key = ? and value = ?", next, row[0], row[1], row[2]);
        }
        return new Result(sealed, locked);
    }

    /**
     * What a stored value should become, or null to leave it alone.
     *
     * <p>Left alone: anything already under the current marker, anything locked (named), and
     * everything when there is no key. Rewritten: a clear value, and a legacy-marker value this key
     * opens — both go under the current marker so the table ends up saying one thing.
     */
    private String rewrite(String stored, List<String> locked, String name) {
        if (stored == null || stored.isBlank()) return null;
        SecretCipher.Reading reading = cipher.open(stored);
        if (reading.locked()) {
            locked.add(name);
            return null;
        }
        if (!cipher.hasKey()) return null;
        if (stored.startsWith(SecretCipher.PREFIX)) return null;
        return cipher.wrap(reading.plaintext());
    }

    private void report(Result credentials, Result settings) {
        int sealed = credentials.sealed() + settings.sealed();
        List<String> locked = new ArrayList<>(credentials.locked());
        locked.addAll(settings.locked());

        if (sealed > 0) {
            log.info("Encrypted {} stored secret(s) that were in the clear or under the old "
                    + "marker ({} credential(s), {} setting(s)).",
                    sealed, credentials.sealed(), settings.sealed());
        }
        if (locked.isEmpty()) return;
        // Named, not counted: "3 credentials could not be read" sends somebody hunting through a
        // list, and the whole point is that they should know which ones to re-enter.
        log.warn("{} stored secret(s) are locked — encrypted with a key this installation does not "
                + "have: {}. They show as locked in the app; enter each one again to store it under "
                + "this installation's key, or start with CONCENTUS_SECRET_KEY set to the key that "
                + "sealed them.",
                locked.size(), String.join(", ", locked));
    }
}
