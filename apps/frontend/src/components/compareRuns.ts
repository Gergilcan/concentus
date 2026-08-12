import type { NodeExec } from '../api/types.ts'

/**
 * Candidate relative to reference, as a signed percentage — or null when the reference is zero,
 * where any percentage would be an invention. Near-zero changes read as "≈" rather than "+0.3%",
 * because token counts wobble run to run and a decimal implies a precision the comparison lacks.
 */
export function pctDelta(reference: number, candidate: number): string | null {
  if (!Number.isFinite(reference) || !Number.isFinite(candidate) || reference === 0) return null
  const pct = ((candidate - reference) / reference) * 100
  if (Math.abs(pct) < 0.5) return '≈'
  const magnitude = Math.abs(pct) < 10 ? Math.abs(pct).toFixed(1) : String(Math.round(Math.abs(pct)))
  return `${pct > 0 ? '+' : '−'}${magnitude}%`
}

/**
 * The run's fullest context window: the node closest to compaction, which is the one that
 * decides whether the flow fits. Per-node contexts must not be summed across a run — each node
 * has its own window — so "the peak" is the honest single number. Null before any usage arrived
 * (e.g. runs recorded before context tracking existed).
 */
export function peakContext(nodes: NodeExec[]): { tokens: number; window: number | null } | null {
  let peak: { tokens: number; window: number | null } | null = null
  for (const n of nodes) {
    const tokens = n.contextTokens ?? 0
    if (tokens > 0 && (!peak || tokens > peak.tokens)) {
      peak = { tokens, window: n.contextWindow ?? null }
    }
  }
  return peak
}

/** "3 passed · 1 failed" — omitting zero counts, so the common all-passed case stays short. */
export function stepSummary(nodes: NodeExec[]): string {
  const counts = new Map<string, number>()
  for (const n of nodes) counts.set(n.status, (counts.get(n.status) ?? 0) + 1)
  const parts: string[] = []
  for (const status of ['passed', 'failed', 'running', 'pending']) {
    const c = counts.get(status)
    if (c) parts.push(`${c} ${status}`)
  }
  return parts.length ? parts.join(' · ') : 'no steps'
}
