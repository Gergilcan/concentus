package com.concentus.auth;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V16 on a database that already has accounts and records: what an installation upgrading to
 * this build actually goes through.
 *
 * <p>The schema is taken to the version just before, populated the way the old code populated
 * it — one organization per account, no organization on resources or runs — and then migrated
 * the rest of the way. The claims are the ones the upgrade rests on: every account has exactly
 * the membership it had implicitly, every record belongs to the one organization the deployment
 * had, and an account whose organization had no row is not left pointing at nothing.
 */
class OrganizationMigrationTest {

    private static final long NOW = 1_700_000_000_000L;

    private static JdbcTemplate migratedToBefore(String databaseName) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("15")
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }

    @Test
    void every_existing_account_becomes_one_membership_in_its_organization() {
        JdbcTemplate jdbc = migratedToBefore("orgs_migration_memberships");
        jdbc.update("insert into organizations (id, name, created_at) values ('default', 'Concentus', ?)", NOW);
        jdbc.update("""
                insert into users (id, organization_id, email, password_hash, role, enabled, created_at)
                values ('usr_admin', 'default', 'admin@tecnovent.com', 'x', 'ADMIN', true, ?),
                       ('usr_viewer', 'default', 'viewer@tecnovent.com', 'x', 'VIEWER', true, ?)
                """, NOW, NOW + 1);

        assertThat(SchemaMigrator.migrate(jdbc.getDataSource())).isTrue();

        List<Map<String, Object>> memberships = jdbc.queryForList(
                "select user_id, organization_id, role from memberships order by user_id");
        assertThat(memberships).hasSize(2);
        assertThat(memberships.get(0)).containsEntry("user_id", "usr_admin")
                .containsEntry("organization_id", "default").containsEntry("role", "ADMIN");
        assertThat(memberships.get(1)).containsEntry("user_id", "usr_viewer")
                .containsEntry("organization_id", "default").containsEntry("role", "VIEWER");
        // The account row keeps meaning something: the organization it is working in.
        assertThat(jdbc.queryForObject("select organization_id from users where id = 'usr_admin'", String.class))
                .isEqualTo("default");
        // An organization that already had a row keeps its name — nothing is renamed to "Default".
        assertThat(jdbc.queryForObject("select name from organizations where id = 'default'", String.class))
                .isEqualTo("Concentus");
    }

    @Test
    void an_organization_every_account_names_but_no_row_describes_becomes_a_row_named_default() {
        JdbcTemplate jdbc = migratedToBefore("orgs_migration_default_row");
        // Possible only on a database edited by hand, and exactly the case that must not leave an
        // account pointing at an organization nothing can name.
        jdbc.update("""
                insert into users (id, organization_id, email, password_hash, role, enabled, created_at)
                values ('usr_1', 'acme', 'one@acme.example', 'x', 'ADMIN', true, ?)
                """, NOW);

        assertThat(SchemaMigrator.migrate(jdbc.getDataSource())).isTrue();

        assertThat(jdbc.queryForObject("select name from organizations where id = 'acme'", String.class))
                .isEqualTo("Default");
        assertThat(jdbc.queryForObject(
                "select count(*) from memberships where user_id = 'usr_1' and organization_id = 'acme'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void existing_resources_and_runs_belong_to_the_one_organization_the_deployment_had() {
        JdbcTemplate jdbc = migratedToBefore("orgs_migration_backfill");
        jdbc.update("insert into organizations (id, name, created_at) values ('default', 'Concentus', ?)", NOW);
        jdbc.update("""
                insert into resources (kind, id, sort_key, json, updated_at)
                values ('flow', 'flow_1', 'Ads', '{"id":"flow_1","name":"Ads"}', ?),
                       ('facade-profile', 'fprof_1', 'Read only', '{"id":"fprof_1"}', ?)
                """, NOW, NOW);
        jdbc.update("insert into runs (id, flow_id, status, created_at, updated_at) values ('run_1', 'flow_1', 'COMPLETED', ?, ?)",
                NOW, NOW);

        assertThat(SchemaMigrator.migrate(jdbc.getDataSource())).isTrue();

        assertThat(jdbc.queryForList("select organization_id from resources order by id", String.class))
                .containsExactly("default", "default");
        assertThat(jdbc.queryForObject("select organization_id from runs where id = 'run_1'", String.class))
                .isEqualTo("default");
    }

    /** A fresh database has nothing to backfill and must not be tripped by the empty subquery. */
    @Test
    void a_fresh_database_migrates_with_nothing_to_backfill() {
        JdbcTemplate jdbc = migratedToBefore("orgs_migration_fresh");

        assertThat(SchemaMigrator.migrate(jdbc.getDataSource())).isTrue();

        assertThat(jdbc.queryForObject("select count(*) from memberships", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from organizations", Integer.class)).isZero();
    }
}
