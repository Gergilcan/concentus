import { describe, expect, it } from 'vitest'
import type { NodeExec } from '../api/types.ts'
import { pctDelta, peakContext, stepSummary } from './compareRuns.ts'

describe('pctDelta', () => {
  it('signs the change relative to the reference', () => {
    expect(pctDelta(100, 125)).toBe('+25%')
    expect(pctDelta(100, 75)).toBe('−25%')
  })

  it('keeps a decimal only below ten percent, where it still means something', () => {
    expect(pctDelta(1000, 1042)).toBe('+4.2%')
    expect(pctDelta(100, 251)).toBe('+151%')
  })

  it('reads near-zero changes as equal rather than inventing precision', () => {
    expect(pctDelta(1000, 1001)).toBe('≈')
  })

  it('refuses a percentage when the reference is zero', () => {
    // "+Infinity%" is not a comparison. The caller shows the raw numbers instead.
    expect(pctDelta(0, 50)).toBeNull()
  })
})

describe('stepSummary', () => {
  const node = (status: NodeExec['status']): NodeExec =>
    ({ nodeId: 'n', kind: 'agent', label: 'A', status, inputTokens: 0, outputTokens: 0, startedAt: 0, endedAt: 0 })

  it('omits zero counts so the common all-passed case stays short', () => {
    expect(stepSummary([node('passed'), node('passed'), node('failed')])).toBe('2 passed · 1 failed')
    expect(stepSummary([node('passed')])).toBe('1 passed')
  })

  it('says when there are no steps at all', () => {
    expect(stepSummary([])).toBe('no steps')
  })
})

// Context compares by its peak: each node runs in its own window, so summing them would
// describe a window nothing ever ran in. The fullest node decides whether the flow fits.
describe('peakContext', () => {
  const node = (over: Partial<NodeExec>): NodeExec => ({
    nodeId: 'n',
    kind: 'agent',
    label: 'A',
    status: 'passed',
    inputTokens: 0,
    outputTokens: 0,
    startedAt: 0,
    endedAt: 0,
    ...over,
  })

  it('is null when no node recorded context (runs from before tracking existed)', () => {
    expect(peakContext([node({}), node({ contextTokens: 0 })])).toBeNull()
  })

  it('picks the fullest node and carries its window', () => {
    const nodes = [
      node({ nodeId: 'a', contextTokens: 12_000, contextWindow: 200_000 }),
      node({ nodeId: 'b', contextTokens: 87_000, contextWindow: 200_000 }),
      node({ nodeId: 'c', contextTokens: 40_000, contextWindow: null }),
    ]
    expect(peakContext(nodes)).toEqual({ tokens: 87_000, window: 200_000 })
  })
})
