package com.concentus.store;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SchemaMigrator}, and specifically for the two situations a migration tool has to
 * get right the first time it is introduced: an empty database, and a database whose tables already
 * exist because an earlier build created them by hand.
 */
class SchemaMigratorTest {

    private static boolean tableExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    @Test
    void anEmptyDatabaseGetsTheWholeSchema() {
        DataSource ds = TestDatabase.freshDatabase("migrate_empty");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(tableExists(jdbc, "runs")).isTrue();
        assertThat(tableExists(jdbc, "resources")).isTrue();
        assertThat(tableExists(jdbc, "credentials")).isTrue();
        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(tableExists(jdbc, "flyway_schema_history")).isTrue();
        // The columns that used to be bolted on with `alter table ... add column` must be part of
        // the table now, or a fresh install would be missing them.
        assertThat(jdbc.queryForObject("select count(initial_prompt) from runs", Integer.class)).isZero();
    }

    @Test
    void aDatabaseFromBeforeMigrationsIsBaselinedRatherThanRejected() {
        // What an install that predates Flyway looks like: the tables are there, created ad-hoc,
        // and there is no history table to say so.
        DataSource ds = TestDatabase.freshDatabase("migrate_legacy");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table runs (id text primary key, initial_prompt text)");
        jdbc.execute("create table resources (kind text, id text, sort_key text, json text, "
                + "updated_at bigint, primary key (kind, id))");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        // Baselined at V1: the history exists, and the hand-made tables are left exactly as they
        // were rather than the migration trying to create them again and failing.
        assertThat(tableExists(jdbc, "flyway_schema_history")).isTrue();
        assertThat(tableExists(jdbc, "runs")).isTrue();
        jdbc.update("insert into resources (kind, id, json, updated_at) values ('flow','f1','{}',1)");
        assertThat(jdbc.queryForObject("select count(*) from resources", Integer.class)).isEqualTo(1);
    }

    @Test
    void migratingTwiceIsANoOp() {
        DataSource ds = TestDatabase.freshDatabase("migrate_twice");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(1);
    }

    @Test
    void anUnreachableDatabaseIsReportedRatherThanThrown() {
        // The behaviour the whole design rests on: the app must still start, because the settings
        // that fix the connection are edited inside it.
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/nope");
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);
        try (com.zaxxer.hikari.HikariDataSource dead = new com.zaxxer.hikari.HikariDataSource(config)) {
            assertThat(SchemaMigrator.migrate(dead)).isFalse();
        }
    }
}
