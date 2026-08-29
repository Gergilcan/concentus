package com.concentus.policy;

import com.concentus.auth.OrgContext;
import com.concentus.store.OrgPolicyStore;
import com.concentus.store.PublishApprovalStore;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate, mostly: a saved policy on a Team deployment is a record and nothing more, and every
 * rule the service answers is a no-op there. On Enterprise the same record is the law, and a save
 * is validated so a typo cannot widen anything.
 */
class OrgPolicyServiceTest {

    private static final OrgPolicy STRICT = new OrgPolicy("default", "fprof_reader", true, "acceptEdits", 100.0, true);

    private final OrgPolicyStore store = mock(OrgPolicyStore.class);
    private final PublishApprovalStore approvals = mock(PublishApprovalStore.class);

    private OrgPolicyService serviceOn(Path dir, String fixture) throws Exception {
        if (fixture != null) TestLicenses.installFixture(dir, fixture);
        LicenseService license = TestLicenses.serviceOn(dir);
        return new OrgPolicyService(store, approvals, license, new OrgContext("default"));
    }

    @Test
    void onTeamTheStoredPolicyIsShownButNothingIsEnforced(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(STRICT));
        OrgPolicyService s = serviceOn(dir, "team-test.license");

        assertThat(s.enforced()).isFalse();
        assertThat(s.refusal()).contains("Organization policies").contains("Enterprise");
        // The panel still shows what was written — a downgrade is not a deletion.
        assertThat(s.stored()).isEqualTo(STRICT);
        // ...and no rule applies: exactly what a Team deployment did before policies existed.
        assertThat(s.effective()).isEqualTo(OrgPolicy.NONE);
        assertThat(s.defaultFacadeProfileId()).isEmpty();
        assertThat(s.requireFacade()).isFalse();
        assertThat(s.maxPermissionMode()).isEmpty();
        assertThat(s.monthlyBudgetUsd()).isNull();
        assertThat(s.publishRequiresApproval()).isFalse();
        assertThat(s.publishBlocked("flow_1", "tok")).isFalse();
        verify(approvals, never()).get(anyString());
    }

    @Test
    void onTeamASaveIsRefusedWithTheFeaturesOwnSentence(@TempDir Path dir) throws Exception {
        OrgPolicyService s = serviceOn(dir, "team-test.license");

        assertThatThrownBy(() -> s.save(STRICT))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("Organization policies is an Enterprise feature");
        assertThatThrownBy(() -> s.approve("flow_1", "tok", "admin@acme.test"))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        verify(store, never()).save(any());
        verify(approvals, never()).save(any());
    }

    @Test
    void withNoLicenseAtAllTheAnswerIsTheSameAsTeam(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(STRICT));
        OrgPolicyService s = serviceOn(dir, null);

        assertThat(s.enforced()).isFalse();
        assertThat(s.effective()).isEqualTo(OrgPolicy.NONE);
    }

    @Test
    void onEnterpriseTheStoredPolicyIsTheOneEnforced(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(STRICT));
        when(approvals.get("flow_1")).thenReturn(Optional.of(
                new PublishApproval("flow_1", "tok-approved", "admin@acme.test", 1L)));
        OrgPolicyService s = serviceOn(dir, "enterprise-test.license");

        assertThat(s.enforced()).isTrue();
        assertThat(s.refusal()).isNull();
        assertThat(s.effective()).isEqualTo(STRICT);
        assertThat(s.defaultFacadeProfileId()).contains("fprof_reader");
        assertThat(s.requireFacade()).isTrue();
        assertThat(s.maxPermissionMode()).isEqualTo("acceptEdits");
        assertThat(s.monthlyBudgetUsd()).isEqualTo(100.0);
        assertThat(s.publishRequiresApproval()).isTrue();
        assertThat(s.publishBlocked("flow_1", "tok-approved")).isFalse();
        // The approval names one token; a regenerated one is blocked until approved again.
        assertThat(s.publishBlocked("flow_1", "tok-new")).isTrue();
        assertThat(s.publishBlocked("flow_2", "tok-approved")).isTrue();
    }

    @Test
    void anOrganizationThatNeverWroteAPolicyHasNone(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.empty());
        OrgPolicyService s = serviceOn(dir, "enterprise-test.license");

        assertThat(s.effective().requireFacade()).isFalse();
        assertThat(s.maxPermissionMode()).isEmpty();
        assertThat(s.monthlyBudgetUsd()).isNull();
        assertThat(s.publishBlocked("flow_1", "tok")).isFalse();
    }

    @Test
    void aSaveIsKeyedToTheOrganizationAndNormalised(@TempDir Path dir) throws Exception {
        when(store.save(any())).thenAnswer(i -> i.getArgument(0));
        OrgPolicyService s = serviceOn(dir, "enterprise-test.license");

        s.save(new OrgPolicy("somebody-elses-id", " fprof_reader ", false, " plan ", 0.0, false));

        ArgumentCaptor<OrgPolicy> saved = ArgumentCaptor.forClass(OrgPolicy.class);
        verify(store).save(saved.capture());
        // Never the id the request carried: the organization comes from the principal.
        assertThat(saved.getValue().id()).isEqualTo("default");
        assertThat(saved.getValue().defaultFacadeProfileId()).isEqualTo("fprof_reader");
        assertThat(saved.getValue().maxPermissionMode()).isEqualTo("plan");
        // Zero is "no budget", stored as such rather than as a ceiling of nothing.
        assertThat(saved.getValue().monthlyBudgetUsd()).isNull();
    }

    @Test
    void aCeilingThatIsNotAModeIsRefusedRatherThanStored(@TempDir Path dir) throws Exception {
        OrgPolicyService s = serviceOn(dir, "enterprise-test.license");

        // A typo stored as a ceiling would clamp nothing, which is a policy that says one thing
        // and does another.
        assertThatThrownBy(() -> s.save(new OrgPolicy(null, "", false, "bypass", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bypass");
        assertThatThrownBy(() -> s.save(new OrgPolicy(null, "", false, "", -5.0, false)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).save(any());
    }
}
