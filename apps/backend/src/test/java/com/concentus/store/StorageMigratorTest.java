package com.concentus.store;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Moving an installation's data into another PostgreSQL.
 *
 * <p>Two real databases, because the failure modes worth catching are all in the seam between
 * them: a schema the target does not have yet, a column one side knows and the other does not, and
 * a repeat of a migration that stopped halfway. Nothing here is mocked for the same reason the
 * rest of this package is not — a copy that works against a fake is evidence of nothing.
 */
class StorageMigratorTest {

    /** A source that looks like an installation somebody has been using. */
    private static JdbcTemplate populatedSource(String name) {
        DataSource ds = TestDatabase.freshDatabase(name);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into resources (kind, id, json, updated_at) values (?,?,?,?)",
                "flow", "flow_1", "{\"name\":\"Daily briefing\"}", 1L);
        jdbc.update("insert into resources (kind, id, json, updated_at) values (?,?,?,?)",
                "mcp", "mcp_1", "{\"name\":\"holded\"}", 2L);
        jdbc.update("insert into runs (id, flow_id, flow_name, status, created_at) values (?,?,?,?,?)",
                "run_1", "flow_1", "Daily briefing", "COMPLETED", 10L);
        jdbc.update("insert into knowledge_chunks (base_id, doc_name, seq, content, created_at) "
                + "values (?,?,?,?,?)", "kb_1", "manual.pdf", 0, "a chunk of text", 11L);
        return jdbc;
    }

    private static JdbcTemplate jdbcFor(DataSource ds) {
        return new JdbcTemplate(ds);
    }

    private static long rows(JdbcTemplate jdbc, String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void an_empty_company_database_ends_up_holding_everything() {
        JdbcTemplate source = populatedSource("migrate_src_1");
        DataSource target = TestDatabase.freshDatabase("migrate_dst_1");

        StorageMigrator.Report report = StorageMigrator.copy(
                source.getDataSource(), target, Set.of());

        JdbcTemplate there = jdbcFor(target);
        assertThat(rows(there, "resources")).isEqualTo(2);
        assertThat(rows(there, "runs")).isEqualTo(1);
        assertThat(rows(there, "knowledge_chunks")).isEqualTo(1);
        assertThat(report.totalRows()).isEqualTo(4);
        assertThat(there.queryForObject(
                "select json from resources where id = 'flow_1'", String.class))
                .isEqualTo("{\"name\":\"Daily briefing\"}");
    }

    // The target does not have to be prepared by hand: the schema belongs to this application, so
    // the migration brings it up to date with the same migrations everything else runs.
    @Test
    void the_target_schema_is_created_by_the_migration_itself() {
        JdbcTemplate source = populatedSource("migrate_src_2");
        DataSource target = TestDatabase.freshDatabase("migrate_dst_2");

        StorageMigrator.copy(source.getDataSource(), target, Set.of());

        assertThat(jdbcFor(target).queryForObject(
                "select count(*) from information_schema.tables where table_name = 'flyway_schema_history'",
                Integer.class)).isEqualTo(1);
    }

    // The property that makes this safe to press: a run that stopped halfway is repeated, not
    // unpicked, and pointing at a database that already holds some of this merges instead of
    // destroying it.
    @Test
    void repeating_a_migration_adds_nothing_and_destroys_nothing() {
        JdbcTemplate source = populatedSource("migrate_src_3");
        DataSource target = TestDatabase.freshDatabase("migrate_dst_3");

        StorageMigrator.copy(source.getDataSource(), target, Set.of());
        StorageMigrator.Report second = StorageMigrator.copy(source.getDataSource(), target, Set.of());

        assertThat(rows(jdbcFor(target), "resources")).isEqualTo(2);
        // Nothing was taken the second time, and the report says so rather than counting rows it
        // did not move.
        assertThat(second.totalRows()).isZero();
    }

    @Test
    void a_row_the_target_already_has_a_different_version_of_is_left_as_it_is() {
        JdbcTemplate source = populatedSource("migrate_src_4");
        DataSource target = TestDatabase.freshDatabase("migrate_dst_4");
        assertThat(SchemaMigrator.migrate(target)).isTrue();
        jdbcFor(target).update("insert into resources (kind, id, json, updated_at) values (?,?,?,?)",
                "flow", "flow_1", "{\"name\":\"Theirs, not mine\"}", 99L);

        StorageMigrator.copy(source.getDataSource(), target, Set.of());

        // Never deleted, never overwritten: a migration that could replace a company's row on a
        // mistyped URL would be worse than no migration at all.
        assertThat(jdbcFor(target).queryForObject(
                "select json from resources where id = 'flow_1'", String.class))
                .isEqualTo("{\"name\":\"Theirs, not mine\"}");
        assertThat(rows(jdbcFor(target), "resources")).isEqualTo(2);
    }

    // For somebody who wants their configuration on the company database without waiting for a
    // year of run history to cross a VPN.
    @Test
    void the_heavy_tables_can_be_left_behind_and_the_report_says_they_were() {
        JdbcTemplate source = populatedSource("migrate_src_5");
        DataSource target = TestDatabase.freshDatabase("migrate_dst_5");

        StorageMigrator.Report report = StorageMigrator.copy(
                source.getDataSource(), target, Set.of("runs", "knowledge_chunks"));

        JdbcTemplate there = jdbcFor(target);
        assertThat(rows(there, "resources")).isEqualTo(2);
        assertThat(rows(there, "runs")).isZero();
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("runs"));
    }

    @Test
    void the_survey_says_what_is_here_biggest_first() {
        JdbcTemplate source = populatedSource("migrate_src_6");

        List<StorageMigrator.TableCount> counts = StorageMigrator.survey(source.getDataSource());
        Map<String, Long> byTable = StorageMigrator.asMap(counts);

        assertThat(byTable).containsEntry("resources", 2L).containsEntry("runs", 1L);
        assertThat(counts.get(0).rows()).isGreaterThanOrEqualTo(counts.get(counts.size() - 1).rows());
        // Remember-me tokens name a browser and a deployment; carried across they could only ever
        // be dead rows.
        assertThat(byTable).doesNotContainKey("persistent_logins");
        assertThat(byTable).doesNotContainKey("flyway_schema_history");
    }

    @Test
    void a_target_that_cannot_be_reached_says_so_instead_of_half_copying() {
        JdbcTemplate source = populatedSource("migrate_src_7");
        org.springframework.jdbc.datasource.DriverManagerDataSource unreachable =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:1/nothing", "nobody", "nothing");

        Throwable thrown = catchThrowable(() ->
                StorageMigrator.copy(source.getDataSource(), unreachable, Set.of()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(rows(source, "resources")).isEqualTo(2); // the source is only ever read
    }
}
