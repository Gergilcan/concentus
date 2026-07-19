import type { BackendFlow, RunSummary } from '../api/types.ts'
import { decided, kindOf, type Sort } from './flowFormat.ts'

/** Groups runs by flow id, most-recent first within each group. */
export function groupRunsByFlow(runs: RunSummary[]): Map<string, RunSummary[]> {
  const m = new Map<string, RunSummary[]>()
  for (const r of runs) {
    if (!r.flowId) continue
    const list = m.get(r.flowId) ?? []
    list.push(r)
    m.set(r.flowId, list)
  }
  for (const list of m.values()) list.sort((a, b) => b.createdAt - a.createdAt)
  return m
}

/** Distinct flow tags, alphabetically sorted, for the tag filter bar. */
export function collectTags(flows: BackendFlow[]): string[] {
  const s = new Set<string>()
  flows.forEach((f) => (f.tags ?? []).forEach((t) => s.add(t)))
  return [...s].sort()
}

export interface DashboardStats {
  flows: number
  executions: number
  active: number
  /** Percent of decided (non-stopped) runs that succeeded, or null with no decided runs yet. */
  success: number | null
  cost: number
}

export function computeStats(flows: BackendFlow[], runs: RunSummary[]): DashboardStats {
  const finished = runs.filter(decided)
  const ok = finished.filter((r) => kindOf(r.status) === 'ok').length
  return {
    flows: flows.length,
    executions: runs.length,
    active: runs.filter((r) => kindOf(r.status) === 'active').length,
    success: finished.length ? Math.round((ok / finished.length) * 100) : null,
    cost: runs.reduce((sum, r) => sum + (r.estimatedCostUsd ?? 0), 0),
  }
}

/** Filters flows by name/tag, then sorts with favourites always floated to the top. */
export function visibleFlows(
  flows: BackendFlow[],
  runsByFlow: Map<string, RunSummary[]>,
  query: string,
  sort: Sort,
  tagFilter: string | null,
): BackendFlow[] {
  const q = query.trim().toLowerCase()
  const list = flows.filter(
    (f) => (!q || f.name.toLowerCase().includes(q)) && (!tagFilter || (f.tags ?? []).includes(tagFilter)),
  )
  const lastRunAt = (f: BackendFlow) => (f.id ? (runsByFlow.get(f.id)?.[0]?.createdAt ?? 0) : 0)
  return [...list].sort((a, b) => {
    if (!!a.favorite !== !!b.favorite) return a.favorite ? -1 : 1
    if (sort === 'name') return a.name.localeCompare(b.name)
    if (sort === 'runs') {
      return (b.id ? (runsByFlow.get(b.id)?.length ?? 0) : 0) - (a.id ? (runsByFlow.get(a.id)?.length ?? 0) : 0)
    }
    return lastRunAt(b) - lastRunAt(a)
  })
}

export function recentRuns(runs: RunSummary[], limit = 12): RunSummary[] {
  return [...runs].sort((a, b) => b.createdAt - a.createdAt).slice(0, limit)
}

/** Validates and normalizes a flow parsed from an imported JSON file (fresh id, "(imported)" name). */
export function normalizeImportedFlow(parsed: BackendFlow): BackendFlow {
  if (!parsed || !Array.isArray(parsed.nodes)) throw new Error('That file is not a Concentus flow.')
  return {
    ...parsed,
    id: undefined,
    name: parsed.name ? `${parsed.name} (imported)` : 'Imported flow',
  }
}

/** Triggers a browser download of a flow as a `.flow.json` file. */
export function downloadFlowJson(flow: BackendFlow): void {
  const blob = new Blob([JSON.stringify(flow, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${flow.name.replace(/[^\w.-]+/g, '-').toLowerCase()}.flow.json`
  a.click()
  URL.revokeObjectURL(a.href)
}
