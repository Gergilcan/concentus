package com.concentus.audit;

import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.store.AuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Who a row is credited to, and that a trail which cannot be written never stops the action
 * it was recording. Hand-wired: a mocked store, a real {@link OrgContext} reading the security
 * context this test sets and clears.
 */
class AuditServiceTest {

    private final AuditStore store = mock(AuditStore.class);
    private final AuditService audit = new AuditService(store, new OrgContext("default"), new ObjectMapper());

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String email, String role, String organizationId) {
        ConcentusUserDetails user = new ConcentusUserDetails("u1", organizationId, email, "hash", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void aSignedInPersonIsTheActorWithTheRoleTheyHoldNowAndTheirOrganization() {
        signIn("gerard@tecnovent.com", "ADMIN", "org_tv");

        audit.record(AuditKinds.FLOW_SAVED, "flow", "flow_1", "Nightly digest", Map.of("version", 3));

        verify(store).append(anyLong(), eq("org_tv"), eq("gerard@tecnovent.com"), eq("ADMIN"),
                eq(AuditKinds.FLOW_SAVED), eq("flow"), eq("flow_1"), eq("Nightly digest"),
                eq("{\"version\":3}"));
    }

    // A cron tick has no principal. The row must still say what fired the run rather than leave
    // a blank where a name should be — and it lands in the installation's default organization,
    // which is the one every single-tenant deployment is.
    @Test
    void withNobodySignedInASystemActionIsCreditedToItsTrigger() {
        audit.recordSystem("cron", AuditKinds.RUN_STARTED, "run", "run_1", "Nightly digest", null);

        verify(store).append(anyLong(), eq("default"), eq("system:cron"), isNull(),
                eq(AuditKinds.RUN_STARTED), eq("run"), eq("run_1"), eq("Nightly digest"), isNull());
    }

    // The same call with a person on the request credits the person: the trigger label is the
    // fallback, not an override — a run somebody pressed is that somebody's, whatever the flow's
    // trigger mode says.
    @Test
    void aSystemActionWithSomeoneSignedInIsStillCreditedToThem() {
        signIn("ops@tecnovent.com", "OPERATOR", "default");

        audit.recordSystem("manual", AuditKinds.RUN_STARTED, "run", "run_1", "Nightly digest", null);

        verify(store).append(anyLong(), eq("default"), eq("ops@tecnovent.com"), eq("OPERATOR"),
                eq(AuditKinds.RUN_STARTED), any(), any(), any(), any());
    }

    @Test
    void aPlainRecordWithNobodySignedInSaysSystem() {
        audit.record(AuditKinds.SETTING_CHANGED, "setting", "runs.max-concurrent", "Runs at once", null);

        verify(store).append(anyLong(), eq("default"), eq(AuditService.SYSTEM), isNull(),
                eq(AuditKinds.SETTING_CHANGED), any(), any(), any(), any());
    }

    // The trail exists to answer questions later; a database hiccup while writing it must not
    // turn into a refused save or a run that did not start.
    @Test
    void aStoreThatFailsNeverBreaksTheCaller() {
        doThrow(new RuntimeException("connection refused")).when(store)
                .append(anyLong(), anyString(), anyString(), any(), anyString(), any(), any(), any(), any());

        assertThatCode(() -> audit.record(AuditKinds.FLOW_DELETED, "flow", "flow_1", "Gone", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> audit.recordSystem("webhook", AuditKinds.RUN_STARTED, "run", "r", "F", null))
                .doesNotThrowAnyException();
    }
}
