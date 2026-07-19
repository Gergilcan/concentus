import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, BackendFlowNode, RunSummary } from '../api/types.ts'
import { KIND_LABEL, compact, countsOf, decided, kindOf, money, timeAgo, triggerOf } from './flowFormat.ts'

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

function flow(nodes: BackendFlowNode[]): BackendFlow {
  return { name: 'Flow', mode: 'local', nodes, edges: [] }
}

function node(type: BackendFlowNode['type'], data: Record<string, unknown> = {}): BackendFlowNode {
  return { id: `${type}-${Math.random()}`, type, data }
}

describe('kindOf', () => {
  it('maps ERROR to fail', () => {
    expect(kindOf('ERROR')).toBe('fail')
  })

  it('maps TERMINATED to stopped (neither success nor failure)', () => {
    expect(kindOf('TERMINATED')).toBe('stopped')
  })

  it('maps RUNNING and STARTING to active', () => {
    expect(kindOf('RUNNING')).toBe('active')
    expect(kindOf('STARTING')).toBe('active')
  })

  it('maps any other status (e.g. IDLE) to ok', () => {
    expect(kindOf('IDLE')).toBe('ok')
    expect(kindOf('')).toBe('ok')
  })
})

describe('KIND_LABEL', () => {
  it('has a human label for every Kind returned by kindOf', () => {
    expect(KIND_LABEL).toEqual({
      ok: 'Succeeded',
      fail: 'Failed',
      active: 'Running',
      stopped: 'Stopped',
    })
  })
})

describe('decided', () => {
  it('is true for a succeeded run', () => {
    expect(decided(run({ status: 'IDLE' }))).toBe(true)
  })

  it('is true for a failed run', () => {
    expect(decided(run({ status: 'ERROR' }))).toBe(true)
  })

  it('is false for a still-active run', () => {
    expect(decided(run({ status: 'RUNNING' }))).toBe(false)
  })

  it('is false for a run stopped by hand (neither success nor failure)', () => {
    expect(decided(run({ status: 'TERMINATED' }))).toBe(false)
  })
})

describe('timeAgo', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-19T12:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const now = () => Date.now()

  it('reports "just now" just under the 45s threshold', () => {
    expect(timeAgo(now() - 44_000)) .toBe('just now')
  })

  it('switches to minutes once seconds crosses under a minute (0m ago at 45s)', () => {
    expect(timeAgo(now() - 45_000)).toBe('0m ago')
  })

  it('reports whole minutes just under the hour boundary', () => {
    expect(timeAgo(now() - 59 * 60_000)).toBe('59m ago')
  })

  it('switches to hours exactly at the hour boundary', () => {
    expect(timeAgo(now() - 60 * 60_000)).toBe('1h ago')
  })

  it('reports whole hours just under the day boundary', () => {
    expect(timeAgo(now() - 23 * 60 * 60_000)).toBe('23h ago')
  })

  it('switches to days exactly at the day boundary', () => {
    expect(timeAgo(now() - 24 * 60 * 60_000)).toBe('1d ago')
  })

  it('reports whole days just under the 30-day boundary', () => {
    expect(timeAgo(now() - 29 * 24 * 60 * 60_000)).toBe('29d ago')
  })

  it('falls back to a locale date string at 30 days and beyond', () => {
    const ts = now() - 30 * 24 * 60 * 60_000
    expect(timeAgo(ts)).toBe(new Date(ts).toLocaleDateString())
  })

  it('clamps a future timestamp to "just now" instead of a negative duration', () => {
    expect(timeAgo(now() + 60_000)).toBe('just now')
  })
})

describe('compact', () => {
  it('renders small numbers as-is', () => {
    expect(compact(0)).toBe('0')
    expect(compact(999)).toBe('999')
  })

  it('switches to "k" at the 1,000 boundary', () => {
    expect(compact(1000)).toBe('1.0k')
    expect(compact(1500)).toBe('1.5k')
  })

  it('switches to "M" at the 1,000,000 boundary', () => {
    expect(compact(1_000_000)).toBe('1.0M')
    expect(compact(2_500_000)).toBe('2.5M')
  })

  it('renders negative numbers as-is (below the k/M thresholds)', () => {
    expect(compact(-5)).toBe('-5')
  })
})

describe('money', () => {
  it('renders exactly zero as "$0"', () => {
    expect(money(0)).toBe('$0')
  })

  it('renders sub-cent positive amounts as "<$0.01"', () => {
    expect(money(0.001)).toBe('<$0.01')
  })

  it('renders exactly one cent and above with two decimals', () => {
    expect(money(0.01)).toBe('$0.01')
    expect(money(5)).toBe('$5.00')
  })

  it('treats a negative amount as below the cent threshold', () => {
    expect(money(-5)).toBe('<$0.01')
  })
})

describe('triggerOf', () => {
  it('reports a cron trigger with its schedule text', () => {
    const f = flow([node('input', { mode: 'cron', cron: '0 * * * *' })])
    expect(triggerOf(f)).toEqual({ label: '⏱ 0 * * * *', tone: 'cron', scheduled: true })
  })

  it('falls back to a generic label when a cron trigger has no schedule text', () => {
    const f = flow([node('input', { mode: 'cron', cron: '' })])
    expect(triggerOf(f)).toEqual({ label: '⏱ scheduled', tone: 'cron', scheduled: true })
  })

  it('reports a webhook trigger as not scheduled', () => {
    const f = flow([node('input', { mode: 'webhook' })])
    expect(triggerOf(f)).toEqual({ label: '⚡ Webhook', tone: 'webhook', scheduled: false })
  })

  it('reports a prompt trigger', () => {
    const f = flow([node('input', { mode: 'prompt' })])
    expect(triggerOf(f)).toEqual({ label: '▶ Prompt', tone: 'prompt', scheduled: false })
  })

  it('defaults to manual when the input node has no recognized mode', () => {
    const f = flow([node('input', {})])
    expect(triggerOf(f)).toEqual({ label: '✋ Manual', tone: 'manual', scheduled: false })
  })

  it('defaults to manual when there is no input node at all', () => {
    const f = flow([node('agent')])
    expect(triggerOf(f)).toEqual({ label: '✋ Manual', tone: 'manual', scheduled: false })
  })
})

describe('countsOf', () => {
  it('counts zero agents/tools for an empty flow', () => {
    expect(countsOf(flow([]))).toEqual({ agents: 0, tools: 0 })
  })

  it('counts agent nodes as agents and non-input/non-agent nodes as tools', () => {
    const f = flow([node('input'), node('agent'), node('agent'), node('mcp'), node('sql'), node('repo')])
    expect(countsOf(f)).toEqual({ agents: 2, tools: 3 })
  })

  it('excludes input nodes from both counts', () => {
    const f = flow([node('input'), node('input')])
    expect(countsOf(f)).toEqual({ agents: 0, tools: 0 })
  })
})
