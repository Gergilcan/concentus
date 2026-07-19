import type { RunEvent } from '../api/types.ts'

/**
 * Identity of the agent behind a run event.
 *
 * Prefers `agentId` — the canvas node id, which stays unique even when two agents share a
 * display name — and falls back to the name for events produced before ids existed, so a
 * replayed buffer from an older run still groups sensibly instead of collapsing into one bucket.
 */
export function agentKey(e: RunEvent): string | null {
  return e.agentId ?? e.agent ?? null
}
