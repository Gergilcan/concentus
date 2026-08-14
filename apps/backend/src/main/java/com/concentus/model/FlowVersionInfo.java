package com.concentus.model;

/**
 * One entry in a flow's version history.
 *
 * <p>{@code author} is whoever saved the revision — the signed-in email, or {@code "local"} when
 * authentication is off. It is null for revisions written before authorship was recorded; the UI
 * shows those as "—" rather than attributing them to the current user.
 *
 * <p>The counts and the deltas against the previous (older) revision are derived when the history
 * is listed rather than stored, so revisions saved before this existed report them too. The oldest
 * revision has nothing to compare against, so its deltas are zero — not "everything is new".
 */
public record FlowVersionInfo(int version, String name, long createdAt, String author,
                              int nodeCount, int edgeCount,
                              int nodesDelta, int edgesDelta, boolean renamed) {
}
