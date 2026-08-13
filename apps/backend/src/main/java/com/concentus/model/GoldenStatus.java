package com.concentus.model;

/**
 * Where a flow stands against its golden reference.
 *
 * <p>{@code stale} is DERIVED, not recorded: the golden run carries the exact graph it executed,
 * so "has the flow changed since" is a comparison, not a flag someone has to remember to set. A
 * marker would have to survive restarts, imports, restores and edits made through three different
 * endpoints — and the first one it missed would report a flow as tested when it was not.
 *
 * @param flowId   the flow this is about
 * @param runId    its golden reference
 * @param stale    the saved graph differs from the one the golden run executed
 * @param autoRun  saving this flow re-runs the golden check by itself
 */
public record GoldenStatus(String flowId, String runId, boolean stale, boolean autoRun) {
}
