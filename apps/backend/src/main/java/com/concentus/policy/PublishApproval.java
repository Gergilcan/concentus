package com.concentus.policy;

/**
 * An admin's approval of one flow's published endpoint — for one token.
 *
 * <p>Per token, not per flow, and that is the whole design: regenerating the token on the Input
 * node is how an author revokes what a client holds, and an approval that survived it would
 * approve an endpoint nobody has looked at. So the record names the token it approved, and a
 * flow whose current token differs is simply not approved — nothing has to be cleared.
 *
 * <p>Policy-owned rather than a field on the flow: a member can save a flow, and an approval a
 * member could write by saving would not be an approval.
 *
 * @param flowId     the flow — the record's key
 * @param token      the publish token that was approved, exactly as the flow carried it
 * @param approvedBy the admin's email, for the Input node to say who
 * @param approvedAt epoch millis
 */
public record PublishApproval(String flowId, String token, String approvedBy, long approvedAt) {

    public PublishApproval withFlowId(String newFlowId) {
        return new PublishApproval(newFlowId, token, approvedBy, approvedAt);
    }

    /** Whether this approval covers {@code presented} — the flow's current token. */
    public boolean covers(String presented) {
        return token != null && !token.isBlank() && token.equals(presented);
    }
}
