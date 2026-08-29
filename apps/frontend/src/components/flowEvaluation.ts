import type { FlowEvalResult } from '../api/types.ts'

/**
 * The two most recent versions that have a finished score, oldest first — the headline.
 *
 * "v7 8/10 → v8 10/10" is what an edit did, in a form that can be argued with. Only the latest
 * result of each version counts: re-running the same version is a retry, not a new data point,
 * and a version's newest score is the one that reflects its current cases.
 */
export function versionHeadline(results: FlowEvalResult[]): FlowEvalResult[] {
  const latest = new Map<number, FlowEvalResult>()
  for (const r of results) {
    if (r.status !== 'done') continue
    const seen = latest.get(r.flowVersion)
    if (!seen || r.startedAt > seen.startedAt) latest.set(r.flowVersion, r)
  }
  return [...latest.values()]
    .sort((a, b) => b.flowVersion - a.flowVersion)
    .slice(0, 2)
    .reverse()
}
