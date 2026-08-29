package com.concentus.web;

import com.concentus.audit.AuditEvent;
import com.concentus.audit.AuditKinds;
import com.concentus.auth.OrgContext;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.service.RetentionService;
import com.concentus.store.AuditStore;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The trail's read side against a real PostgreSQL: the filters are SQL, the paging is a cursor
 * over a serial column, and a mocked JdbcTemplate would only prove the strings were passed.
 *
 * <p>Export is the one tier gate on this controller: refused below Enterprise with the feature's
 * own sentence, allowed on Enterprise — proven with the committed fixture licenses.
 */
class AuditControllerTest {

    private static final String ORG = "default";

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final AuditStore store = new AuditStore(jdbc);
    private final RetentionService retention = mock(RetentionService.class);

    @BeforeEach
    void cleanTrail() {
        jdbc.update("delete from audit_events");
        store.init();
        when(retention.policy()).thenReturn(new RetentionService.Policy(null, "kept forever"));
    }

    /** As {@code LicenseControllerTest}'s: an admin of the default organization, no real session. */
    private static OrgContext adminContext() {
        return new OrgContext(ORG) {
            @Override
            public boolean isAdmin() {
                return true;
            }

            @Override
            public String requireOrganizationId() {
                return ORG;
            }
        };
    }

    private static OrgContext viewerContext() {
        return new OrgContext(ORG) {
            @Override
            public boolean isAdmin() {
                return false;
            }
        };
    }

    private AuditController controller(LicenseService license, OrgContext context) {
        return new AuditController(store, context, license, retention);
    }

    private AuditController controllerOnFreeInstall(Path dir) throws Exception {
        return controller(TestLicenses.serviceOn(dir), adminContext());
    }

    private void row(long at, String actor, String kind, String label) {
        store.append(at, ORG, actor, "ADMIN", kind, "flow", "flow_1", label, null);
    }

    @SuppressWarnings("unchecked")
    private static List<AuditEvent> events(Map<String, Object> page) {
        return (List<AuditEvent>) page.get("events");
    }

    // ---------------------------------------------------------------- reading

    @Test
    void theTrailReadsNewestFirstAndOnlyForAdmins(@TempDir Path dir) throws Exception {
        row(1_000, "a@x.com", AuditKinds.FLOW_SAVED, "first");
        row(2_000, "a@x.com", AuditKinds.FLOW_SAVED, "second");

        List<AuditEvent> page = events(controllerOnFreeInstall(dir).list(null, null, null, null, null, null));

        assertThat(page).extracting(AuditEvent::subjectLabel).containsExactly("second", "first");
        assertThatThrownBy(() -> controller(TestLicenses.serviceOn(dir), viewerContext())
                .list(null, null, null, null, null, null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    @Test
    void actorKindAndDateFiltersNarrowThePage(@TempDir Path dir) throws Exception {
        long aug1 = AuditController.parseMoment("2026-08-01", false);
        long aug20 = AuditController.parseMoment("2026-08-20", false);
        long sep5 = AuditController.parseMoment("2026-09-05", false);
        row(aug1, "gerard@tecnovent.com", AuditKinds.FLOW_SAVED, "august save");
        row(aug20, "system:cron", AuditKinds.RUN_STARTED, "august run");
        row(sep5, "gerard@tecnovent.com", AuditKinds.FLOW_DELETED, "september delete");
        AuditController c = controllerOnFreeInstall(dir);

        // Actor is a substring, case-blind: "system:" is how every unattended action is found.
        assertThat(events(c.list("SYSTEM:", null, null, null, null, null)))
                .extracting(AuditEvent::subjectLabel).containsExactly("august run");
        assertThat(events(c.list(null, AuditKinds.FLOW_DELETED, null, null, null, null)))
                .extracting(AuditEvent::subjectLabel).containsExactly("september delete");
        // Dates are inclusive at both ends: "to 2026-08-20" includes the 20th.
        assertThat(events(c.list(null, null, "2026-08-01", "2026-08-20", null, null)))
                .extracting(AuditEvent::subjectLabel).containsExactly("august run", "august save");
        assertThat(events(c.list(null, null, "2026-08-02", null, null, null)))
                .extracting(AuditEvent::subjectLabel).containsExactly("september delete", "august run");
    }

    @Test
    void pagesContinueFromTheLastIdOfThePageBefore(@TempDir Path dir) throws Exception {
        for (int i = 1; i <= 5; i++) row(i * 1_000L, "a@x.com", AuditKinds.FLOW_SAVED, "save " + i);
        AuditController c = controllerOnFreeInstall(dir);

        Map<String, Object> first = c.list(null, null, null, null, null, 2);
        assertThat(events(first)).extracting(AuditEvent::subjectLabel).containsExactly("save 5", "save 4");
        assertThat(first.get("hasMore")).isEqualTo(true);

        Map<String, Object> second = c.list(null, null, null, null, (Long) first.get("nextBefore"), 2);
        assertThat(events(second)).extracting(AuditEvent::subjectLabel).containsExactly("save 3", "save 2");

        Map<String, Object> third = c.list(null, null, null, null, (Long) second.get("nextBefore"), 2);
        assertThat(events(third)).extracting(AuditEvent::subjectLabel).containsExactly("save 1");
        assertThat(third.get("hasMore")).isEqualTo(false);
    }

    @Test
    void aBadDateIsA400NotA500(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> controllerOnFreeInstall(dir).list(null, null, "last tuesday", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last tuesday");
    }

    // ---------------------------------------------------------------- status

    @Test
    void statusCarriesTheKindsTheRefusalAndTheRetentionInForce(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "team-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);
        when(retention.policy()).thenReturn(new RetentionService.Policy(90, "Team license: ninety days."));

        Map<String, Object> status = controller(license, adminContext()).status();

        assertThat(status.get("kinds")).isEqualTo(AuditKinds.ALL);
        assertThat((String) status.get("exportRefusal")).isEqualTo(license.refusal(Feature.AUDIT_EXPORT))
                .contains("Enterprise feature");
        assertThat(status.get("retentionDays")).isEqualTo(90);
        assertThat(status.get("retentionReason")).isEqualTo("Team license: ninety days.");
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportIsRefusedOnATeamLicenseWithTheFeaturesOwnSentence(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "team-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);
        assertThat(license.teamTier()).isTrue();

        assertThatThrownBy(() -> controller(license, adminContext()).export("csv", null, null, null, null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessage(license.refusal(Feature.AUDIT_EXPORT))
                .hasMessageContaining(Feature.AUDIT_EXPORT.label);
    }

    @Test
    void exportIsRefusedOnAFreeInstallToo(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> controllerOnFreeInstall(dir).export("json", null, null, null, null))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("Enterprise");
    }

    @Test
    void exportOnEnterpriseStreamsCsvOldestFirstWithQuotingWhereItMatters(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);
        store.append(1_000, ORG, "a@x.com", "ADMIN", AuditKinds.FLOW_SAVED, "flow", "flow_1",
                "Digest, nightly", "{\"version\":2}");
        store.append(2_000, ORG, "system:cron", null, AuditKinds.RUN_STARTED, "run", "run_1",
                "Digest, nightly", null);

        ResponseEntity<StreamingResponseBody> response =
                controller(license, adminContext()).export("csv", null, null, null, null);
        String csv = body(response);

        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("concentus-audit-").contains(".csv");
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("id,at,actor,role,kind,subject_type,subject_id,subject_label,detail");
        assertThat(lines[1]).contains("a@x.com,ADMIN,flow.saved,flow,flow_1,\"Digest, nightly\",\"{\"\"version\"\":2}\"");
        assertThat(lines[2]).contains("system:cron,,run.started,run,run_1,\"Digest, nightly\",");
    }

    @Test
    void exportOnEnterpriseStreamsJsonWithTheDetailAsAnObject(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        LicenseService license = TestLicenses.serviceOn(dir);
        store.append(1_000, ORG, "a@x.com", "ADMIN", AuditKinds.MEMBER_INVITED, "member", "u_2",
                "new@x.com", "{\"role\":\"VIEWER\"}");

        String json = body(controller(license, adminContext()).export("json", null, null, null, null));

        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed.get(0).get("actor").asText()).isEqualTo("a@x.com");
        assertThat(parsed.get(0).get("kind").asText()).isEqualTo(AuditKinds.MEMBER_INVITED);
        assertThat(parsed.get(0).get("detail").get("role").asText()).isEqualTo("VIEWER");
        assertThat(parsed.get(0).get("at").asText()).isEqualTo("1970-01-01T00:00:01Z");
    }

    @Test
    void exportRefusesAFormatItDoesNotKnow(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");

        assertThatThrownBy(() -> controller(TestLicenses.serviceOn(dir), adminContext())
                .export("xlsx", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String body(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
