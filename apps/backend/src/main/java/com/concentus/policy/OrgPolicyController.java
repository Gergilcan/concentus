package com.concentus.policy;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowGraph;
import com.concentus.model.TriggerSpec;
import com.concentus.store.FlowStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the Policies panel and the Input node read and write.
 *
 * <p>Reading is every signed-in role — the panel renders for a member, read-only, and the Input
 * node has to tell a member their endpoint is waiting on an admin. Writing is admin only through
 * the same {@link OrgContext#requireAdmin()} gate the license and settings screens use: a policy
 * changes what every flow in the organization may do, which is the definition of administering.
 *
 * <p>The Enterprise gate is the service's, not this controller's: a save on a Team deployment is
 * refused with the feature's own sentence, the one the panel already shows.
 */
@RestController
@RequestMapping("/api/org-policy")
public class OrgPolicyController {

    private final OrgPolicyService policies;
    private final FlowStore flows;
    private final OrgContext orgContext;

    public OrgPolicyController(OrgPolicyService policies, FlowStore flows, OrgContext orgContext) {
        this.policies = policies;
        this.flows = flows;
        this.orgContext = orgContext;
    }

    /**
     * The policy as the panel needs it: the record, whether it is in force, why not, and
     * whether this caller may change it.
     *
     * @param policy   the stored record — shown even where not enforced, so a downgrade does not
     *                 read as a deletion
     * @param enforced whether any of it applies (the Enterprise gate)
     * @param refusal  the gate's own sentence when it does not; null when it does
     * @param canEdit  enforced, and the caller is an admin
     */
    public record PolicyView(OrgPolicy policy, boolean enforced, String refusal, boolean canEdit) { }

    /**
     * One flow's publish approval as the Input node needs it. {@code savedToken} is the token the
     * SAVED flow carries, so the node can tell an unsaved regeneration apart from a token an
     * admin has not yet looked at; a member can already read it off the flow itself.
     */
    public record ApprovalView(boolean required, String savedToken, String approvedToken,
                               String approvedBy, Long approvedAt) { }

    @GetMapping
    public PolicyView get() {
        return view();
    }

    @PutMapping
    public PolicyView save(@RequestBody OrgPolicy draft) {
        orgContext.requireAdmin();
        policies.save(draft == null ? OrgPolicy.NONE : draft);
        return view();
    }

    @GetMapping("/publish/{flowId}")
    public ApprovalView approval(@PathVariable String flowId) {
        return approvalView(flowId);
    }

    /**
     * Approves the flow's CURRENT saved token — read from the store, never from the request, so
     * what an admin approves is what the endpoint will actually compare against, and an unsaved
     * draft in someone's browser cannot be approved ahead of itself.
     */
    @PostMapping("/publish/{flowId}/approve")
    public ApprovalView approve(@PathVariable String flowId) {
        orgContext.requireAdmin();
        FlowGraph flow = flows.get(flowId)
                .orElseThrow(() -> new IllegalArgumentException("No such flow: " + flowId));
        TriggerSpec trigger = TriggerSpec.from(flow);
        if (!trigger.publishedWithToken()) {
            throw new IllegalArgumentException("'" + flow.name() + "' is not published, or its "
                    + "publishing has not been saved yet. Save the flow with publishing on, then approve.");
        }
        policies.approve(flowId, trigger.publishToken(),
                orgContext.currentUser().map(u -> u.email()).orElse(null));
        return approvalView(flowId);
    }

    @DeleteMapping("/publish/{flowId}/approve")
    public ApprovalView revoke(@PathVariable String flowId) {
        orgContext.requireAdmin();
        policies.revoke(flowId);
        return approvalView(flowId);
    }

    private PolicyView view() {
        boolean enforced = policies.enforced();
        return new PolicyView(policies.stored(), enforced, policies.refusal(),
                enforced && orgContext.isAdmin());
    }

    private ApprovalView approvalView(String flowId) {
        String saved = flows.get(flowId).map(TriggerSpec::from)
                .filter(TriggerSpec::publishedWithToken).map(TriggerSpec::publishToken).orElse(null);
        PublishApproval approval = policies.approval(flowId).orElse(null);
        return new ApprovalView(policies.publishRequiresApproval(), saved,
                approval == null ? null : approval.token(),
                approval == null ? null : approval.approvedBy(),
                approval == null ? null : approval.approvedAt());
    }
}
