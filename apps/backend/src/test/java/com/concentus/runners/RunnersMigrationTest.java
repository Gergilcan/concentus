package com.concentus.runners;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21 on the two databases a migration has to get right: an empty one, and one baselined at V1
 * with only the tables it happened to have — where {@code runs} may not exist for the alter.
 */
class RunnersMigrationTest {

    private static boolean tableExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' "
                        + "and table_name = ? and column_name = ?", Integer.class, table, column);
        return count != null && count > 0;
    }

    private static void assertRunnersSchema(JdbcTemplate jdbc) {
        assertThat(tableExists(jdbc, "runners")).isTrue();
        assertThat(columnExists(jdbc, "runs", "runner_id")).isTrue();
        assertThat(columnExists(jdbc, "runs", "runner_name")).isTrue();

        jdbc.update("insert into runners (id, organization_id, name, scope, token_hash, created_at) "
                + "values ('rn_1', 'org', 'NAS', 'organization', 'h1', 1)");
        // The name is unique per organization, case-blind; the token hash everywhere.
        assertThatThrownBy(() -> jdbc.update("insert into runners (id, organization_id, name, scope, token_hash, "
                + "created_at) values ('rn_2', 'org', 'nas', 'organization', 'h2', 1)"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("insert into runners (id, organization_id, name, scope, token_hash, "
                + "created_at) values ('rn_3', 'other', 'nas', 'organization', 'h1', 1)"))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("insert into runners (id, organization_id, name, scope, token_hash, created_at) "
                + "values ('rn_4', 'other', 'nas', 'organization', 'h4', 1)");
        assertThat(jdbc.queryForObject("select count(*) from runners", Integer.class)).isEqualTo(2);
    }

    @Test
    void an_empty_database_gets_the_runners_table_and_the_run_columns() {
        DataSource ds = TestDatabase.freshDatabase("runners_migrate_empty");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertRunnersSchema(new JdbcTemplate(ds));
    }

    @Test
    void a_database_baselined_at_v1_gets_them_too() {
        DataSource ds = TestDatabase.freshDatabase("runners_migrate_legacy");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table runs (id text primary key, initial_prompt text)");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertThat(tableExists(jdbc, "flyway_schema_history")).isTrue();
        assertRunnersSchema(jdbc);
    }
}
