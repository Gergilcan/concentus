package com.concentus.groups;

import com.concentus.policy.OrgPolicy;
import com.concentus.policy.PermissionCeiling;

/**
 * A group's policy: the organization's shape, every field nullable, and null means "inherit".
 *
 * <p>Layered over the organization's policy field by field by {@link #over}: a field the group
 * set is the group's, a field it left null is the organization's. The one exception is the
 * budget. {@code monthlyBudgetUsd} here is the group's OWN ceiling — the spend of every run that
 * carried this group's id, summed — and it is enforced beside the organization's, not instead of
 * it; so {@link #over} keeps the organization's figure and the run service asks for the group's
 * separately.
 *
 * <p>Blank strings are normalised to null at save, so a select left on "inherit" and a field
 * somebody cleared read the same way: a group cannot lift the organization's ceiling by sending
 * an empty one. What it CAN do is name a mode of its own, which is what a manager is trusted with.
 *
 * @param defaultFacadeProfileId  facade a worker with none of its own runs behind; null inherits
 * @param requireFacade           whether a worker with servers wired must have a facade; null inherits
 * @param maxPermissionMode       the most a run may do unasked; null inherits
 * @param monthlyBudgetUsd        the group's own monthly ceiling in USD; null or zero for none
 * @param publishRequiresApproval whether a published endpoint waits for an admin; null inherits
 */
public record GroupPolicy(String defaultFacadeProfileId, Boolean requireFacade, String maxPermissionMode,
                          Double monthlyBudgetUsd, Boolean publishRequiresApproval) {

    /** Inherits everything: what a group that never wrote a policy has. */
    public static final GroupPolicy NONE = new GroupPolicy(null, null, null, null, null);

    /** Whether a group budget is set at all: zero and null both mean "no ceiling". */
    public boolean hasBudget() {
        return monthlyBudgetUsd != null && monthlyBudgetUsd > 0;
    }

    /** Whether every field inherits — a policy that says nothing. */
    public boolean inheritsEverything() {
        return defaultFacadeProfileId == null && requireFacade == null && maxPermissionMode == null
                && monthlyBudgetUsd == null && publishRequiresApproval == null;
    }

    /**
     * Trimmed, blanks to null, a zero budget to null, and the ceiling checked against the modes
     * the CLI has — an unknown one is refused rather than stored, exactly as the organization's is.
     *
     * @throws IllegalArgumentException for a ceiling that is not a mode or a negative budget
     */
    public GroupPolicy normalized() {
        String facade = blankToNull(defaultFacadeProfileId);
        String ceiling = blankToNull(maxPermissionMode);
        if (ceiling != null && !PermissionCeiling.known(ceiling)) {
            throw new IllegalArgumentException("'" + ceiling + "' is not a permission mode. One of: "
                    + String.join(", ", PermissionCeiling.ORDER) + ", or blank to inherit the organization's.");
        }
        if (monthlyBudgetUsd != null && monthlyBudgetUsd < 0) {
            throw new IllegalArgumentException("The group budget cannot be negative.");
        }
        return new GroupPolicy(facade, requireFacade, ceiling, hasBudget() ? monthlyBudgetUsd : null,
                publishRequiresApproval);
    }

    /**
     * The organization's policy with this group's fields laid over it. The budget is the
     * organization's — see the class comment.
     */
    public OrgPolicy over(OrgPolicy base) {
        OrgPolicy under = base == null ? OrgPolicy.NONE : base;
        return new OrgPolicy(under.id(),
                defaultFacadeProfileId != null ? defaultFacadeProfileId : under.defaultFacadeProfileId(),
                requireFacade != null ? requireFacade : under.requireFacade(),
                maxPermissionMode != null ? maxPermissionMode : under.maxPermissionMode(),
                under.monthlyBudgetUsd(),
                publishRequiresApproval != null ? publishRequiresApproval : under.publishRequiresApproval());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
