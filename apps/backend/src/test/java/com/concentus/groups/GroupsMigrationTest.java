package com.concentus.groups;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20 on the two databases a migration has to get right: an empty one, and one that predates
 * migrations and is baselined at V1 with only the tables it happened to have — where
 * {@code credentials}, a V1 table, may not exist for the alter to find.
 */
class GroupsMigrationTest {

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

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and indexname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private static void assertGroupsSchema(JdbcTemplate jdbc) {
        for (String table : new String[] {"groups", "group_memberships", "group_settings", "group_policies"}) {
            assertThat(tableExists(jdbc, table)).as(table).isTrue();
        }
        for (String table : new String[] {"resources", "credentials", "runs", "marketplace_items"}) {
            assertThat(columnExists(jdbc, table, "group_id")).as(table + ".group_id").isTrue();
            assertThat(indexExists(jdbc, table + "_group_idx")).as(table + "_group_idx").isTrue();
        }
        assertThat(indexExists(jdbc, "groups_org_idx")).isTrue();
        assertThat(indexExists(jdbc, "groups_org_name_key")).isTrue();
        assertThat(indexExists(jdbc, "group_memberships_user_idx")).isTrue();

        jdbc.update("insert into groups (id, organization_id, name, created_at) values ('gr_1', 'org', 'Platform', 1)");
        jdbc.update("insert into group_memberships (group_id, user_id, manager, created_at) values ('gr_1', 'usr', true, 1)");
        jdbc.update("insert into group_settings (organization_id, group_id, key, value, updated_at) "
                + "values ('org', 'gr_1', 'workers.retries', '2', 1)");
        jdbc.update("insert into group_policies (group_id, organization_id, json) values ('gr_1', 'org', '{}')");
        // The name is unique per organization, case-blind — the second insert must fail.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into groups (id, organization_id, name, created_at) values ('gr_2', 'org', 'platform', 1)"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("insert into groups (id, organization_id, name, created_at) values ('gr_3', 'other', 'Platform', 1)");
        assertThat(jdbc.queryForObject("select count(*) from groups", Integer.class)).isEqualTo(2);
    }

    @Test
    void an_empty_database_gets_the_group_tables_and_columns() {
        DataSource ds = TestDatabase.freshDatabase("groups_migrate_empty");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertGroupsSchema(new JdbcTemplate(ds));
    }

    @Test
    void a_database_baselined_at_v1_gets_them_too() {
        DataSource ds = TestDatabase.freshDatabase("groups_migrate_legacy");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // The shape of an install from before migrations existed: a runs table, nothing else — in
        // particular no credentials table, which V1 would have created and the baseline skips.
        jdbc.execute("create table runs (id text primary key, initial_prompt text)");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertThat(tableExists(jdbc, "flyway_schema_history")).isTrue();
        assertGroupsSchema(jdbc);
    }
}
