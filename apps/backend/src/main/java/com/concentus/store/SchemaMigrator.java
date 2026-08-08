package com.concentus.store;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Applies the schema migrations in {@code db/migration}.
 *
 * <p><b>Run by hand, not by Spring Boot's auto-configuration.</b> That is the whole design
 * decision here, and it is about what happens when the database cannot be reached. Flyway's
 * auto-configuration migrates during startup and fails the application context if it cannot — which
 * for a server is right, and for this app is a trap: the storage settings are edited <em>inside</em>
 * the app, so an unreachable external database would mean it never opens, locking the user out of
 * the one screen where the connection is fixed. Every store already reports itself unavailable and
 * carries on for exactly the same reason.
 *
 * <p>So a failure here is logged and swallowed. The stores then find no tables, report themselves
 * unavailable, and the app opens far enough to be repaired.
 *
 * <p><b>Baselining.</b> Installs that predate this created their tables ad-hoc, so their schema is
 * non-empty with no history table. Flyway baselines those at V1 — the version that describes
 * exactly what they already have — and applies anything above it. A fresh database is empty, gets
 * no baseline, and has V1 applied normally.
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private SchemaMigrator() {
    }

    /**
     * Migrates, reporting whether it worked.
     *
     * @return true when the schema is at the latest version; false when the database could not be
     *         migrated, in which case the stores will find nothing and say so
     */
    public static boolean migrate(DataSource dataSource) {
        try {
            long start = System.currentTimeMillis();
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    // See the class comment: an existing install's tables ARE V1.
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .baselineDescription("Schema created before migrations were introduced")
                    // Refuse to run against a database whose applied migrations do not match the
                    // ones shipped here. That is either a newer app having touched it or an edited
                    // migration, and both are cases where guessing is worse than stopping.
                    .validateOnMigrate(true)
                    .load()
                    .migrate();

            if (result.migrationsExecuted > 0) {
                log.info("Applied {} schema migration(s) in {} ms; database is now at version {}.",
                        result.migrationsExecuted, System.currentTimeMillis() - start,
                        result.targetSchemaVersion);
            } else {
                log.info("Schema is up to date.");
            }
            return true;
        } catch (Exception e) {
            log.error("Could not migrate the database ({}). The app will start, but storage will be "
                    + "unavailable until this is resolved — check Resources → Storage.", e.getMessage());
            return false;
        }
    }
}
