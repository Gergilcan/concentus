package com.concentus.service;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.OrgContext;
import com.concentus.config.Settings;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.store.AuditStore;
import com.concentus.store.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the nightly purge removes and what it spares, on each tier, against a real PostgreSQL —
 * the deletes are SQL with a correlated subquery and an anti-join, and those are exactly the
 * statements a mock would wave through.
 *
 * <p>The clock is fixed at a known instant so "older than ninety days" is a fact of the fixture,
 * not of the day the suite runs.
 */
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final AuditStore auditStore = new AuditStore(jdbc);
    private final AuditService audit = new AuditService(auditStore, new OrgContext("default"), new ObjectMapper());
    private final RunService runService = mock(RunService.class);

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from runs");
        jdbc.update("delete from flow_versions");
        jdbc.update("delete from audit_events");
        auditStore.init();
    }

    private static long daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days)).toEpochMilli();
    }

    private void run(String id, String flowId, int flowVersion, boolean golden, long createdAt) {
        jdbc.update("insert into runs (id, flow_id, flow_name, status, golden, flow_version, created_at) "
                + "values (?,?,?,?,?,?,?)", id, flowId, "Flow " + flowId, "COMPLETED", golden, flowVersion, createdAt);
    }

    private void version(String flowId, int version, long createdAt) {
        jdbc.update("insert into flow_versions (flow_id, version, name, flow_json, created_at) values (?,?,?,?,?)",
                flowId, version, "Flow " + flowId, "{}", createdAt);
    }

    private void auditRow(long at, String label) {
        auditStore.append(at, "default", "a@x.com", "ADMIN", AuditKinds.FLOW_SAVED, "flow", "f1", label, null);
    }

    /**
     * The fixture every scenario starts from: one flow with three revisions, all past ninety
     * days, of which v2 is the golden run's and v3 is current; runs old and new, golden and not;
     * a trail row on each side of the window.
     */
    private void seed() {
        version("f1", 1, daysAgo(200));
        version("f1", 2, daysAgo(150));
        version("f1", 3, daysAgo(100));
        run("run_old", "f1", 1, false, daysAgo(120));
        run("run_golden", "f1", 2, true, daysAgo(150));
        run("run_recent", "f1", 3, false, daysAgo(10));
        auditRow(daysAgo(95), "old row");
        auditRow(daysAgo(5), "recent row");
    }

    private RetentionService service(LicenseService license, Map<String, String> settings) {
        return new RetentionService(jdbc, auditStore, audit, license, Settings.of(settings), runService, CLOCK);
    }

    private List<String> runIds() {
        return jdbc.query("select id from runs order by id", (rs, i) -> rs.getString("id"));
    }

    private List<Integer> versionsOf(String flowId) {
        return jdbc.query("select version from flow_versions where flow_id = ? order by version",
                (rs, i) -> rs.getInt("version"), flowId);
    }

    private List<String> auditLabels() {
        return jdbc.query("select subject_label from audit_events order by id", (rs, i) -> rs.getString(1));
    }

    // ---------------------------------------------------------------- team

    @Test
    void aTeamDeploymentPurgesPastNinetyDaysButKeepsRecentRunsTheGoldenRunAndItsVersion(@TempDir Path dir)
            throws Exception {
        TestLicenses.installFixture(dir, "team-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);
        seed();

        RetentionService.Report report = service(license, Map.of()).runNow();

        assertThat(report.days()).isEqualTo(Feature.TEAM_RETENTION_DAYS);
        assertThat(report.runs()).isEqualTo(1);
        assertThat(report.versions()).isEqualTo(1);
        assertThat(report.auditEvents()).isEqualTo(1);
        assertThat(runIds()).containsExactly("run_golden", "run_recent");
        // v1 is the only revision that is neither current (v3) nor the golden run's (v2).
        assertThat(versionsOf("f1")).containsExactly(2, 3);
        // The purge writes its own row, after the old one is gone — a trail that lost ninety
        // days overnight and did not say so would look like tampering.
        assertThat(auditLabels()).containsExactly("recent row", "90 days");
        assertThat(jdbc.queryForObject("select actor_email from audit_events where kind = ?",
                String.class, AuditKinds.RETENTION_PURGED)).isEqualTo("system:retention");
        // Memory is told the same cutoff the table was purged with.
        verify(runService).forgetOlderThan(daysAgo(Feature.TEAM_RETENTION_DAYS));
    }

    // A Team deployment cannot buy its way past ninety days by typing a bigger number, nor
    // shorten it: the setting belongs to the tier that owns unlimited retention.
    @Test
    void theEnterpriseSettingIsIgnoredOnTeam(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "team-test.license");
        seed();

        RetentionService.Report report = service(TestLicenses.serviceOn(dir),
                Map.of(RetentionService.SETTING_ENTERPRISE_DAYS, "3650")).runNow();

        assertThat(report.days()).isEqualTo(Feature.TEAM_RETENTION_DAYS);
        assertThat(runIds()).containsExactly("run_golden", "run_recent");
    }

    // ---------------------------------------------------------------- enterprise

    @Test
    void anEnterpriseDeploymentKeepsEverything(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        seed();
        RetentionService retention = service(TestLicenses.serviceOn(dir), Map.of());

        RetentionService.Report report = retention.runNow();

        assertThat(report.total()).isZero();
        assertThat(report.days()).isNull();
        assertThat(runIds()).containsExactly("run_golden", "run_old", "run_recent");
        assertThat(versionsOf("f1")).containsExactly(1, 2, 3);
        assertThat(auditLabels()).containsExactly("old row", "recent row");
        assertThat(retention.policy().purges()).isFalse();
        assertThat(retention.policy().reason()).contains("without limit");
        verify(runService, never()).forgetOlderThan(anyLong());
    }

    @Test
    void anEnterpriseAdminMayChooseAShorterWindow(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        seed();
        RetentionService retention = service(TestLicenses.serviceOn(dir),
                Map.of(RetentionService.SETTING_ENTERPRISE_DAYS, "30"));

        RetentionService.Report report = retention.runNow();

        assertThat(report.days()).isEqualTo(30);
        assertThat(runIds()).containsExactly("run_golden", "run_recent");
        assertThat(versionsOf("f1")).containsExactly(2, 3);
        assertThat(retention.policy().reason()).contains("30 days");
    }

    @Test
    void zeroOrNonsenseInTheEnterpriseSettingMeansForever(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);

        assertThat(service(license, Map.of(RetentionService.SETTING_ENTERPRISE_DAYS, "0")).policy().purges()).isFalse();
        assertThat(service(license, Map.of(RetentionService.SETTING_ENTERPRISE_DAYS, "soon")).policy().purges()).isFalse();
    }

    // ---------------------------------------------------------------- free

    @Test
    void aFreeInstallationIsNeverPurged(@TempDir Path dir) throws Exception {
        seed();
        RetentionService retention = service(TestLicenses.serviceOn(dir),
                Map.of(RetentionService.SETTING_ENTERPRISE_DAYS, "1"));

        RetentionService.Report report = retention.runNow();

        assertThat(report.total()).isZero();
        assertThat(runIds()).containsExactly("run_golden", "run_old", "run_recent");
        assertThat(retention.policy().purges()).isFalse();
        assertThat(retention.policy().reason()).contains("single-person");
    }
}
