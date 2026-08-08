package com.concentus.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * One real PostgreSQL, shared by every test that needs storage.
 *
 * <p>A real server rather than a mocked {@code JdbcTemplate} because these tests exist to prove the
 * SQL works — the upsert, the case-insensitive ordering, the primary key across two columns. A mock
 * would assert that the strings we wrote are the strings we passed, which is not the same claim and
 * would have kept passing through a genuine dialect error.
 *
 * <p>Started once for the whole run and never stopped: starting takes seconds and doing it per test
 * class would dominate the suite. The JVM exiting takes the server with it, and
 * {@link #reset(JdbcTemplate)} gives each test a clean table, which is what isolation actually
 * needs here.
 */
final class TestDatabase {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    private TestDatabase() {
    }

    static synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            try {
                postgres = EmbeddedPostgres.builder().setPort(0).start();
            } catch (IOException | RuntimeException e) {
                // PostgreSQL refuses to run under an account with administrative rights on Windows.
                // That is every GitHub Windows runner, and any developer working from an elevated
                // shell — neither of which is a broken build, so these tests step aside there with
                // a reason rather than failing.
                //
                // Only on Windows: on Linux, which is where CI runs the suite, a database that will
                // not start IS a broken build and must fail.
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    Assumptions.abort(
                            "Skipped: the embedded PostgreSQL could not start (" + e.getMessage() + "). "
                            + "On Windows it refuses to run from an elevated shell — run these tests "
                            + "from a normal, non-administrator terminal.");
                }
                throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
            }
            jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
            // The real migrations, so the tests run against the schema the app actually ships
            // rather than one written twice and free to disagree.
            SchemaMigrator.migrate(postgres.getPostgresDatabase());
        }
        return jdbc;
    }

    /**
     * A separate, empty database on the same server.
     *
     * <p>For the tests that need a schema of their own — chiefly the baseline case, which has to
     * start from a database this run has never migrated.
     */
    static javax.sql.DataSource freshDatabase(String name) {
        jdbc();  // make sure the server is up
        jdbc.execute("drop database if exists " + name);
        jdbc.execute("create database " + name);
        return postgres.getDatabase("postgres", name);
    }

    /**
     * Clears the records one test wrote so the next does not see them.
     *
     * <p>Deletes rather than drops: the table belongs to the migrations now, and dropping it would
     * leave Flyway's history claiming a schema that is no longer there.
     */
    static void reset(JdbcTemplate template) {
        template.execute("delete from resources");
    }
}
