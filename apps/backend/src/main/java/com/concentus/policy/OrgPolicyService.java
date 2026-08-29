package com.concentus.policy;

import com.concentus.auth.OrgContext;
import com.concentus.store.OrgPolicyStore;
import com.concentus.store.PublishApprovalStore;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The policy in force for the current organization, and the one gate every enforcement point
 * asks through.
 *
 * <p>Everything that enforces a rule — the compiler, the executors, the run service, the public
 * endpoint, the doctor — reads the policy from here and never from the store, because the answer
 * depends on the license as much as on the record: where {@link Feature#ORG_POLICIES} is not
 * licensed, {@link #effective()} is {@link OrgPolicy#NONE} whatever was saved, and every rule
 * becomes a no-op in one place rather than behind six separate conditions to forget.
 *
 * <p>Which organization: the signed-in principal's, and the deployment's default one on a thread
 * that has none — a cron tick, a published-endpoint call, a webhook. Flows and runs are shared
 * across the deployment rather than partitioned (see the README on isolation), so on a
 * single-organization deployment, which is every deployment today, the two answers coincide.
 */
@Service
public class OrgPolicyService {

    private final OrgPolicyStore store;
    private final PublishApprovalStore approvals;
    private final LicenseService license;
    private final OrgContext orgContext;

    public OrgPolicyService(OrgPolicyStore store, PublishApprovalStore approvals,
                            LicenseService license, OrgContext orgContext) {
        this.store = store;
        this.approvals = approvals;
        this.license = license;
        this.orgContext = orgContext;
    }

    /** Whether policies apply here at all — the Enterprise gate. */
    public boolean enforced() {
        return license.allows(Feature.ORG_POLICIES);
    }

    /** Why they do not, in words for the panel; null when they do. */
    public String refusal() {
        return license.refusal(Feature.ORG_POLICIES);
    }

    /** The organization whose policy applies to this thread. */
    public String organizationId() {
        return orgContext.currentOrganizationId();
    }

    /** The Enterprise gate on every write: refused with the sentence the panel already shows. */
    private void requireEnforced() {
        if (!enforced()) {
            throw new OrgContext.AccessDeniedForOrganization(refusal());
        }
    }

    /**
     * The record as saved, whether or not it is enforced — what the panel shows read-only on a
     * Team deployment, so a downgrade does not look like a deletion.
     */
    public OrgPolicy stored() {
        return store.getAcrossOrganizations(organizationId()).orElse(OrgPolicy.NONE.withId(organizationId()));
    }

    /** The policy every enforcement point applies: the record where licensed, nothing otherwise. */
    public OrgPolicy effective() {
        return enforced() ? stored() : OrgPolicy.NONE;
    }

    /**
     * Saves the organization's policy. Refused where the feature is not licensed — the panel is
     * read-only there, and a hand-written request must get the same answer — and validated so a
     * typo in the ceiling cannot widen anything: an unknown mode is rejected, never stored.
     */
    public OrgPolicy save(OrgPolicy draft) {
        requireEnforced();
        String ceiling = draft.maxPermissionModeOrEmpty();
        if (!ceiling.isEmpty() && !PermissionCeiling.known(ceiling)) {
            throw new IllegalArgumentException("'" + ceiling + "' is not a permission mode. One of: "
                    + String.join(", ", PermissionCeiling.ORDER) + ", or blank for no ceiling.");
        }
        if (draft.monthlyBudgetUsd() != null && draft.monthlyBudgetUsd() < 0) {
            throw new IllegalArgumentException("The organization budget cannot be negative.");
        }
        return store.save(new OrgPolicy(organizationId(), draft.defaultFacadeProfileIdOrEmpty(),
                draft.requireFacade(), ceiling, draft.hasBudget() ? draft.monthlyBudgetUsd() : null,
                draft.publishRequiresApproval()));
    }

    // ---------------------------------------------------------------- the rules, one by one

    /** The profile a worker with none of its own runs behind, when the policy names one. */
    public Optional<String> defaultFacadeProfileId() {
        String id = effective().defaultFacadeProfileIdOrEmpty();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    /** Whether a worker with servers wired may run with no profile at all. */
    public boolean requireFacade() {
        return effective().requireFacade();
    }

    /** The permission ceiling, or blank for none. */
    public String maxPermissionMode() {
        return effective().maxPermissionModeOrEmpty();
    }

    /** The organization's monthly ceiling in USD, or null for none. */
    public Double monthlyBudgetUsd() {
        OrgPolicy policy = effective();
        return policy.hasBudget() ? policy.monthlyBudgetUsd() : null;
    }

    /** Whether a published endpoint waits for an admin. */
    public boolean publishRequiresApproval() {
        return effective().publishRequiresApproval();
    }

    /**
     * Whether a published flow's endpoint must stay shut: approval is required and nobody has
     * approved this token. False wherever policies are not enforced — the endpoint then answers
     * exactly as it did before approvals existed.
     */
    public boolean publishBlocked(String flowId, String token) {
        if (!publishRequiresApproval()) return false;
        return approvals.getAcrossOrganizations(flowId).filter(a -> a.covers(token)).isEmpty();
    }

    /** The approval on record for a flow, whatever token it names. */
    public Optional<PublishApproval> approval(String flowId) {
        return approvals.get(flowId);
    }

    /** Records an admin's approval of {@code token} for the flow. Admin-ness is the controller's check. */
    public PublishApproval approve(String flowId, String token, String approvedBy) {
        requireEnforced();
        return approvals.save(new PublishApproval(flowId, token, approvedBy, System.currentTimeMillis()));
    }

    /** Withdraws the approval, so the endpoint answers 404 again until the next one. */
    public void revoke(String flowId) {
        requireEnforced();
        approvals.delete(flowId);
    }
}
