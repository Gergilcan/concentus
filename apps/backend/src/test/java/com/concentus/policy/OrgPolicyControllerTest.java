package com.concentus.policy;

import com.concentus.auth.OrgContext;
import com.concentus.store.OrgPolicyStore;
import com.concentus.store.PublishApprovalStore;
import com.concentus.license.TestLicenses;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.store.FlowStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Policies panel's and the Input node's endpoints: who may write, what a member reads, and
 * that an approval is of the SAVED token — never of whatever a browser holds.
 */
class OrgPolicyControllerTest {

    private final OrgPolicyStore store = mock(OrgPolicyStore.class);
    private final PublishApprovalStore approvals = mock(PublishApprovalStore.class);
    private final FlowStore flows = mock(FlowStore.class);

    /** As {@code LicenseControllerTest}'s: an admin (or not) without a real signed-in session. */
    private static OrgContext context(boolean admin) {
        return new OrgContext("default") {
            @Override
            public boolean isAdmin() {
                return admin;
            }
        };
    }

    private OrgPolicyController controller(Path dir, String fixture, boolean admin) throws Exception {
        if (fixture != null) TestLicenses.installFixture(dir, fixture);
        OrgPolicyService service = new OrgPolicyService(store, approvals, TestLicenses.serviceOn(dir),
                context(admin));
        return new OrgPolicyController(service, flows, context(admin));
    }

    private static FlowGraph published(String id, String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", "manual");
        data.put("published", true);
        data.put("publishToken", token);
        return new FlowGraph(id, "Flow", "local", List.of(new FlowNode("in1", "input", null, data)),
                List.of(), null, List.of(), null, null);
    }

    @Test
    void aMemberReadsThePolicyAndCannotWriteIt(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(new OrgPolicy("default", "", false, "plan", null, false)));
        OrgPolicyController c = controller(dir, "enterprise-test.license", false);

        OrgPolicyController.PolicyView view = c.get();
        assertThat(view.enforced()).isTrue();
        assertThat(view.canEdit()).isFalse();
        assertThat(view.policy().maxPermissionMode()).isEqualTo("plan");

        assertThatThrownBy(() -> c.save(view.policy()))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        verify(store, never()).save(any());
    }

    @Test
    void onTeamTheViewCarriesTheRefusalAndEvenAnAdminCannotWrite(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.empty());
        OrgPolicyController c = controller(dir, "team-test.license", true);

        OrgPolicyController.PolicyView view = c.get();
        assertThat(view.enforced()).isFalse();
        assertThat(view.canEdit()).isFalse();
        assertThat(view.refusal()).contains("Enterprise feature");

        assertThatThrownBy(() -> c.save(new OrgPolicy(null, "", false, "plan", null, false)))
                .hasMessageContaining("Enterprise feature");
        verify(store, never()).save(any());
    }

    @Test
    void anAdminOnEnterpriseSavesThePolicy(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(i -> i.getArgument(0));
        OrgPolicyController c = controller(dir, "enterprise-test.license", true);

        OrgPolicyController.PolicyView view = c.save(new OrgPolicy(null, "fprof_1", true, "acceptEdits", 50.0, true));

        assertThat(view.canEdit()).isTrue();
        verify(store).save(any());
    }

    @Test
    void anApprovalIsOfTheSavedTokenAndARegeneratedTokenIsNotApproved(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(new OrgPolicy("default", "", false, "", null, true)));
        when(flows.get("f1")).thenReturn(Optional.of(published("f1", "tok-saved")));
        when(approvals.save(any())).thenAnswer(i -> i.getArgument(0));
        OrgPolicyController c = controller(dir, "enterprise-test.license", true);

        OrgPolicyController.ApprovalView before = c.approval("f1");
        assertThat(before.required()).isTrue();
        assertThat(before.savedToken()).isEqualTo("tok-saved");
        assertThat(before.approvedToken()).isNull();

        c.approve("f1");

        org.mockito.ArgumentCaptor<PublishApproval> saved = org.mockito.ArgumentCaptor.forClass(PublishApproval.class);
        verify(approvals).save(saved.capture());
        // The store's token, not a request's: what is approved is what the endpoint compares.
        assertThat(saved.getValue().token()).isEqualTo("tok-saved");
        assertThat(saved.getValue().flowId()).isEqualTo("f1");

        // The author regenerates and saves: the approval on record names the old token, so the
        // Input node reads "not approved" without anything having been cleared.
        when(approvals.get("f1")).thenReturn(Optional.of(saved.getValue()));
        when(flows.get("f1")).thenReturn(Optional.of(published("f1", "tok-new")));
        OrgPolicyController.ApprovalView after = c.approval("f1");
        assertThat(after.savedToken()).isEqualTo("tok-new");
        assertThat(after.approvedToken()).isEqualTo("tok-saved");
    }

    @Test
    void approvingAnUnpublishedFlowIsRefusedAndAMemberCannotApproveAtAll(@TempDir Path dir) throws Exception {
        when(store.get("default")).thenReturn(Optional.of(new OrgPolicy("default", "", false, "", null, true)));
        Map<String, Object> unpublished = new HashMap<>(Map.of("mode", "manual", "published", false));
        when(flows.get("f2")).thenReturn(Optional.of(new FlowGraph("f2", "Flow", "local",
                List.of(new FlowNode("in1", "input", null, unpublished)), List.of(), null, List.of(), null, null)));
        when(flows.get("f1")).thenReturn(Optional.of(published("f1", "tok")));

        assertThatThrownBy(() -> controller(dir, "enterprise-test.license", true).approve("f2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not published");
        assertThatThrownBy(() -> controller(dir, "enterprise-test.license", false).approve("f1"))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        verify(approvals, never()).save(any());
    }
}
