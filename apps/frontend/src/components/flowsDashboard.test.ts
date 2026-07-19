import { describe, expect, it } from 'vitest'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import {
  collectTags,
  computeStats,
  groupRunsByFlow,
  normalizeImportedFlow,
  recentRuns,
  visibleFlows,
} from './flowsDashboard.ts'

function run(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'r1',
    flowId: 'f1',
    flowName: 'Flow',
    mode: 'local',
    status: 'IDLE',
    createdAt: 0,
    ...overrides,
  }
}

function flow(overrides: Partial<BackendFlow> = {}): BackendFlow {
  return { name: 'Flow', mode: 'local', nodes: [], edges: [], ...overrides }
}

describe('groupRunsByFlow', () => {
  it('groups runs under their flow id, most recent first', () => {
    const runs = [
      run({ id: 'a', flowId: 'f1', createdAt: 1 }),
      run({ id: 'b', flowId: 'f1', createdAt: 3 }),
      run({ id: 'c', flowId: 'f1', createdAt: 2 }),
    ]
    const m = groupRunsByFlow(runs)
    expect(m.get('f1')?.map((r) => r.id)).toEqual(['b', 'c', 'a'])
  })

  it('skips runs with no flowId', () => {
    const m = groupRunsByFlow([run({ id: 'a', flowId: null })])
    expect(m.size).toBe(0)
  })

  it('returns an empty map for an empty run list', () => {
    expect(groupRunsByFlow([]).size).toBe(0)
  })
})

describe('collectTags', () => {
  it('returns a deduplicated, alphabetically sorted list of tags', () => {
    const flows = [flow({ tags: ['ops', 'nightly'] }), flow({ tags: ['nightly', 'beta'] })]
    expect(collectTags(flows)).toEqual(['beta', 'nightly', 'ops'])
  })

  it('treats a missing tags field as no tags', () => {
    expect(collectTags([flow({ tags: undefined })])).toEqual([])
  })

  it('returns an empty list for no flows', () => {
    expect(collectTags([])).toEqual([])
  })
})

describe('computeStats', () => {
  it('counts flows and executions directly', () => {
    const stats = computeStats([flow(), flow()], [run(), run(), run()])
    expect(stats.flows).toBe(2)
    expect(stats.executions).toBe(3)
  })

  it('counts RUNNING/STARTING runs as active', () => {
    const stats = computeStats([], [run({ status: 'RUNNING' }), run({ status: 'STARTING' }), run({ status: 'IDLE' })])
    expect(stats.active).toBe(2)
  })

  it('reports success as a rounded percentage over decided runs only, excluding stopped runs', () => {
    const stats = computeStats(
      [],
      [run({ status: 'IDLE' }), run({ status: 'IDLE' }), run({ status: 'ERROR' }), run({ status: 'TERMINATED' })],
    )
    // 2 of 3 decided runs succeeded (the TERMINATED run doesn't count either way) -> 67%
    expect(stats.success).toBe(67)
  })

  it('reports success as null when there are no decided runs yet', () => {
    expect(computeStats([], []).success).toBeNull()
    expect(computeStats([], [run({ status: 'RUNNING' })]).success).toBeNull()
  })

  it('sums cost, treating a missing estimatedCostUsd as zero', () => {
    const stats = computeStats([], [run({ estimatedCostUsd: 1.5 }), run({ estimatedCostUsd: undefined })])
    expect(stats.cost).toBe(1.5)
  })
})

describe('visibleFlows', () => {
  const flows = [
    flow({ id: 'f1', name: 'Nightly ETL', tags: ['ops'] }),
    flow({ id: 'f2', name: 'Weekly report', tags: ['reports'] }),
    flow({ id: 'f3', name: 'Favourite flow', favorite: true }),
  ]
  const emptyRunsByFlow = new Map<string, RunSummary[]>()

  it('filters by a case-insensitive name substring', () => {
    const result = visibleFlows(flows, emptyRunsByFlow, 'nightly', 'recent', null)
    expect(result.map((f) => f.id)).toEqual(['f1'])
  })

  it('filters by tag', () => {
    const result = visibleFlows(flows, emptyRunsByFlow, '', 'recent', 'reports')
    expect(result.map((f) => f.id)).toEqual(['f2'])
  })

  it('always floats favourites to the top regardless of sort', () => {
    const result = visibleFlows(flows, emptyRunsByFlow, '', 'name', null)
    expect(result[0].id).toBe('f3')
  })

  it('sorts by name (within the same favourite tier)', () => {
    const nonFav = flows.filter((f) => !f.favorite)
    const result = visibleFlows(nonFav, emptyRunsByFlow, '', 'name', null)
    expect(result.map((f) => f.name)).toEqual(['Nightly ETL', 'Weekly report'])
  })

  it('sorts by run count', () => {
    const runsByFlow = new Map<string, RunSummary[]>([
      ['f1', [run(), run()]],
      ['f2', [run()]],
    ])
    const nonFav = flows.filter((f) => !f.favorite)
    const result = visibleFlows(nonFav, runsByFlow, '', 'runs', null)
    expect(result.map((f) => f.id)).toEqual(['f1', 'f2'])
  })

  it('sorts by most-recent run', () => {
    const runsByFlow = new Map<string, RunSummary[]>([
      ['f1', [run({ createdAt: 1 })]],
      ['f2', [run({ createdAt: 5 })]],
    ])
    const nonFav = flows.filter((f) => !f.favorite)
    const result = visibleFlows(nonFav, runsByFlow, '', 'recent', null)
    expect(result.map((f) => f.id)).toEqual(['f2', 'f1'])
  })
})

describe('recentRuns', () => {
  it('sorts most-recent first', () => {
    const runs = [run({ id: 'a', createdAt: 1 }), run({ id: 'b', createdAt: 3 }), run({ id: 'c', createdAt: 2 })]
    expect(recentRuns(runs).map((r) => r.id)).toEqual(['b', 'c', 'a'])
  })

  it('defaults to a limit of 12', () => {
    const runs = Array.from({ length: 20 }, (_, i) => run({ id: String(i), createdAt: i }))
    expect(recentRuns(runs)).toHaveLength(12)
  })

  it('respects a custom limit', () => {
    const runs = [run({ id: 'a' }), run({ id: 'b' }), run({ id: 'c' })]
    expect(recentRuns(runs, 2)).toHaveLength(2)
  })
})

describe('normalizeImportedFlow', () => {
  it('strips the id and appends "(imported)" to an existing name', () => {
    const result = normalizeImportedFlow(flow({ id: 'old-id', name: 'My Flow' }))
    expect(result.id).toBeUndefined()
    expect(result.name).toBe('My Flow (imported)')
  })

  it('falls back to a generic name when the imported flow has none', () => {
    const result = normalizeImportedFlow(flow({ name: '' }))
    expect(result.name).toBe('Imported flow')
  })

  it('rejects a payload without a nodes array', () => {
    expect(() => normalizeImportedFlow({ name: 'x' } as unknown as BackendFlow)).toThrow(
      'That file is not a Concentus flow.',
    )
  })

  it('rejects a falsy payload', () => {
    expect(() => normalizeImportedFlow(null as unknown as BackendFlow)).toThrow(
      'That file is not a Concentus flow.',
    )
  })
})
