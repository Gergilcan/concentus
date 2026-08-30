package com.concentus.policy;

import com.concentus.auth.OrgContext;
import com.concentus.groups.GroupPolicy;
import com.concentus.groups.GroupPolicyStore;
import com.concentus.model.FlowGraph;
import com.concentus.store.FlowStore;
import com.concentus.store.OrgPolicyStore;
import com.concentus.store.PublishApprovalStore;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** A group's own policy, laid over the organization's; null on a service built without groups. */
    private final GroupPolicyStore groupPolicies;
    /** Which group a saved flow belongs to; null on a service built without groups. */
    private final FlowStore flows;

    /**
     * Without groups: every rule answers organization-wide, which is what the tests that predate
     * groups build and what a deployment with no group ever created gets anyway.
     */
    public OrgPolicyService(OrgPolicyStore store, PublishApprovalStore approvals,
                            LicenseService license, OrgContext orgContext) {
        this(store, approvals, license, orgContext, null, null);
    }

    // @Autowired is load-bearing: with two constructors and neither annotated, Spring picks none.
    @Autowired
    public OrgPolicyService(OrgPolicyStore store, PublishApprovalStore approvals,
                            LicenseService license, OrgContext orgContext,
                            GroupPolicyStore groupPolicies, FlowStore flows) {
        this.store = store;
        this.approvals = approvals;
        this.license = license;
        this.orgContext = orgContext;
        this.groupPolicies = groupPolicies;
        this.flows = flows;
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

    // ---------------------------------------------------------------- per group

    /**
     * The group a flow belongs to, or null: an unsaved flow, one the whole organization sees, or
     * a service built without groups. Read from the flow's row rather than the graph, because
     * the group is a property of the record — who sees it — and not of the drawing.
     */
    public String groupOf(FlowGraph flow) {
        if (flows == null || flow == null || flow.id() == null || flow.id().isBlank()) return null;
        return flows.groupOf(flow.id()).orElse(null);
    }

    /**
     * What a flow runs under: the organization's policy with its group's laid over it, field by
     * field — a null field in the group's inherits. The one every enforcement point asks with the
     * flow in hand: the compiler, the doctor, the run service at launch, the public endpoint.
     */
    public OrgPolicy effective(FlowGraph flow) {
        return effectiveForGroup(groupOf(flow));
    }

    /** As {@link #effective(FlowGraph)}, for a caller that holds the group id — a run does. */
    public OrgPolicy effectiveForGroup(String groupId) {
        if (!enforced()) return OrgPolicy.NONE;
        OrgPolicy base = stored();
        if (groupId == null || groupId.isBlank() || groupPolicies == null) return base;
        return groupPolicies.get(groupId).map(g -> g.over(base)).orElse(base);
    }

    /**
     * The group's own monthly ceiling in USD, or null for none — enforced beside the
     * organization's, over the runs that carried the group's id. Nothing where policies are not
     * enforced, like every other rule.
     */
    public Double groupBudgetUsd(String groupId) {
        if (!enforced() || groupPolicies == null || groupId == null || groupId.isBlank()) return null;
        return groupPolicies.get(groupId).filter(GroupPolicy::hasBudget)
                .map(GroupPolicy::monthlyBudgetUsd).orElse(null);
    }

    /** The profile a plan-born worker of a run in {@code groupId} runs behind, when a policy names one. */
    public Optional<String> defaultFacadeProfileIdForGroup(String groupId) {
        String id = effectiveForGroup(groupId).defaultFacadeProfileIdOrEmpty();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    public boolean requireFacadeForGroup(String groupId) {
        return effectiveForGroup(groupId).requireFacade();
    }

    /** The ceiling for this flow — its group's when it names one, the organization's otherwise. */
    public String maxPermissionMode(FlowGraph flow) {
        return effective(flow).maxPermissionModeOrEmpty();
    }

    public boolean publishRequiresApproval(FlowGraph flow) {
        return effective(flow).publishRequiresApproval();
    }

    /** As {@link #publishBlocked(String, String)}, under the flow's own policy. */
    public boolean publishBlocked(FlowGraph flow, String token) {
        if (flow == null || flow.id() == null) return false;
        if (!publishRequiresApproval(flow)) return false;
        return approvals.getAcrossOrganizations(flow.id()).filter(a -> a.covers(token)).isEmpty();
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
