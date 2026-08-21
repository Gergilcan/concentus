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
 * Rewrites the values this installation can still decrypt, once, in the clear.
 *
 * <p>Runs at every start and does nothing on all but the first: it selects only rows that still
 * carry the {@code v1:} marker, so a converted database is one empty query per launch.
 *
 * <p><b>Nothing is deleted and nothing is guessed.</b> A row this installation cannot open is left
 * exactly as it was — it is somebody else's key, and the value may still be recoverable from the
 * machine that wrote it. What those rows get instead is a log line naming them, because the
 * failure they cause otherwise is the one that cost a morning: an integration reporting itself
 * "not configured" while its row sits plainly in the table, with nothing to say which of them are
 * affected.
 */
@Component
public class SecretsMigration {

    private static final Logger log = LoggerFactory.getLogger(SecretsMigration.class);

    private final JdbcTemplate jdbc;
    private final LegacySecrets legacy;

    public SecretsMigration(JdbcTemplate jdbc, LegacySecrets legacy) {
        this.jdbc = jdbc;
        this.legacy = legacy;
    }

    /** What one table's conversion did, for the summary line. */
    private record Result(int converted, List<String> stranded) {
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
                "select id, label, secret from credentials where secret like 'v1:%'",
                (rs, n) -> new String[] { rs.getString("id"), rs.getString("label"),
                        rs.getString("secret") });
        int converted = 0;
        List<String> stranded = new ArrayList<>();
        for (String[] row : rows) {
            String plaintext = legacy.open(row[2]).orElse(null);
            if (plaintext == null) {
                stranded.add(row[1]);
                continue;
            }
            jdbc.update("update credentials set secret = ? where id = ?", plaintext, row[0]);
            converted++;
        }
        return new Result(converted, stranded);
    }

    private Result convertSettings() {
        List<String[]> rows = jdbc.query(
                "select organization_id, key, value from settings where secret = true and value like 'v1:%'",
                (rs, n) -> new String[] { rs.getString("organization_id"), rs.getString("key"),
                        rs.getString("value") });
        int converted = 0;
        List<String> stranded = new ArrayList<>();
        for (String[] row : rows) {
            String plaintext = legacy.open(row[2]).orElse(null);
            if (plaintext == null) {
                stranded.add(row[1]);
                continue;
            }
            jdbc.update("update settings set value = ? where organization_id = ? and key = ?",
                    plaintext, row[0], row[1]);
            converted++;
        }
        return new Result(converted, stranded);
    }

    private void report(Result credentials, Result settings) {
        int converted = credentials.converted() + settings.converted();
        List<String> stranded = new ArrayList<>(credentials.stranded());
        stranded.addAll(settings.stranded());

        if (converted > 0) {
            log.info("Converted {} stored secret(s) to plain text ({} credential(s), {} setting(s)).",
                    converted, credentials.converted(), settings.converted());
        }
        if (stranded.isEmpty()) {
            if (converted > 0) {
                log.info("Nothing encrypted is left in this database. The old key is no longer "
                        + "used by anything running here.");
            }
            return;
        }
        // Named, not counted: "3 credentials could not be read" sends somebody hunting through a
        // list, and the whole point is that they should know which ones to re-enter.
        log.warn("{} stored secret(s) are still encrypted with a key this installation does not "
                + "have, and read as not configured: {}. Re-enter each one to store it in the "
                + "clear, or start once with CONCENTUS_SECRET_KEY set to the key that sealed them "
                + "and they will convert themselves.",
                stranded.size(), String.join(", ", stranded));
    }
}
