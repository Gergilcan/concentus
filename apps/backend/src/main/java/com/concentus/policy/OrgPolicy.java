package com.concentus.policy;

/**
 * The rules an organization sets over every flow in it — one record per organization, edited by
 * an admin under Resources → Policies, enforced where each rule bites.
 *
 * <p>A flow's own settings say what one flow does; this says what no flow may exceed. The two
 * never contradict, because the policy only ever narrows: a default facade fills a blank, a
 * ceiling clamps a mode, an organization budget is checked beside the flow's own, and approval
 * gates a door the flow already opened.
 *
 * <p>Enterprise only. Where the feature is not licensed the record may still exist — an
 * organization that downgrades keeps what it wrote — but {@link OrgPolicyService#effective()}
 * answers {@link #NONE} and nothing here is applied: a Team deployment behaves exactly as it did
 * before policies existed.
 *
 * @param id                      the organization id — the record's key, one per organization
 * @param defaultFacadeProfileId  facade profile an independent worker runs behind when its node
 *                                names none; blank for no default
 * @param requireFacade           when true, a worker with MCP servers wired, no profile of its own
 *                                and no default is refused at compile time rather than run open
 * @param maxPermissionMode       the most a run may be allowed to do without asking: one of
 *                                {@code plan}, {@code default}, {@code acceptEdits},
 *                                {@code bypassPermissions}; blank for no ceiling
 * @param monthlyBudgetUsd        a ceiling on what every flow of the organization spends together
 *                                in a calendar month; null or zero for none
 * @param publishRequiresApproval when true, a published flow's endpoint answers only once an
 *                                admin approved its current token
 */
public record OrgPolicy(String id, String defaultFacadeProfileId, boolean requireFacade,
                        String maxPermissionMode, Double monthlyBudgetUsd,
                        boolean publishRequiresApproval) {

    /** No rules at all: what Team sees, and what an organization that never wrote one sees. */
    public static final OrgPolicy NONE = new OrgPolicy(null, "", false, "", null, false);

    /** This policy, keyed to an organization — the shape the store writes. */
    public OrgPolicy withId(String newId) {
        return new OrgPolicy(newId, defaultFacadeProfileId, requireFacade, maxPermissionMode,
                monthlyBudgetUsd, publishRequiresApproval);
    }

    /** The default profile id, or blank — never null, so a caller can test it without a guard. */
    public String defaultFacadeProfileIdOrEmpty() {
        return defaultFacadeProfileId == null ? "" : defaultFacadeProfileId.trim();
    }

    /** The ceiling, or blank — never null, for the same reason. */
    public String maxPermissionModeOrEmpty() {
        return maxPermissionMode == null ? "" : maxPermissionMode.trim();
    }

    /** Whether an organization budget is set at all: zero and null both mean "no ceiling". */
    public boolean hasBudget() {
        return monthlyBudgetUsd != null && monthlyBudgetUsd > 0;
    }
}
