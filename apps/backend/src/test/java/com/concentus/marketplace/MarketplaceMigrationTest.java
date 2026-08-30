package com.concentus.marketplace;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19 on the two databases a migration has to get right: an empty one, and one that predates
 * migrations and is baselined at V1 with only the tables it happened to have.
 */
class MarketplaceMigrationTest {

    private static boolean tableExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and indexname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private static void assertMarketplaceSchema(JdbcTemplate jdbc) {
        assertThat(tableExists(jdbc, "marketplace_items")).isTrue();
        assertThat(tableExists(jdbc, "marketplace_installs")).isTrue();
        assertThat(indexExists(jdbc, "marketplace_items_scope_status_idx")).isTrue();
        assertThat(indexExists(jdbc, "marketplace_items_org_idx")).isTrue();
        assertThat(indexExists(jdbc, "marketplace_installs_org_idx")).isTrue();
        // The JSON columns are jsonb, so a malformed payload is refused at the boundary.
        jdbc.update("""
                insert into marketplace_items (id, kind, name, scope, status, author_user_id, payload, tags,
                  created_at, updated_at)
                values ('mkt_1', 'mcp', 'X', 'global', 'published', 'usr', '{"name":"X"}'::jsonb, '["a"]'::jsonb, 1, 1)
                """);
        assertThat(jdbc.queryForObject("select payload->>'name' from marketplace_items", String.class)).isEqualTo("X");
        jdbc.update("insert into marketplace_installs (item_id, organization_id, version, installed_at) values ('mkt_1','org',1,1)");
        assertThat(jdbc.queryForObject("select count(*) from marketplace_installs", Integer.class)).isEqualTo(1);
    }

    @Test
    void an_empty_database_gets_the_marketplace_tables() {
        DataSource ds = TestDatabase.freshDatabase("mkt_migrate_empty");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertMarketplaceSchema(new JdbcTemplate(ds));
    }

    @Test
    void a_database_baselined_at_v1_gets_them_too() {
        DataSource ds = TestDatabase.freshDatabase("mkt_migrate_legacy");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // No resources, no organizations: the shape of an install from before migrations existed.
        jdbc.execute("create table runs (id text primary key, initial_prompt text)");

        assertThat(SchemaMigrator.migrate(ds)).isTrue();

        assertThat(tableExists(jdbc, "flyway_schema_history")).isTrue();
        assertMarketplaceSchema(jdbc);
    }
}
