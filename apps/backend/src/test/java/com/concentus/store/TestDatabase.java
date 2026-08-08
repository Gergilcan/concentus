package com.concentus.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;

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
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        }
        return jdbc;
    }

    /** Empties the shared table so one test cannot see another's records. */
    static void reset(JdbcTemplate template) {
        template.execute("drop table if exists resources");
    }
}
