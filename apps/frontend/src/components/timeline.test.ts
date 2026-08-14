import { describe, expect, it } from 'vitest'
import type { NodeExec } from '../api/types.ts'
import { durationLabel, timelineRows } from './timeline.ts'

function node(over: Partial<NodeExec> & { nodeId: string }): NodeExec {
  return {
    kind: 'agent',
    label: over.nodeId,
    status: 'passed',
    inputTokens: 0,
    outputTokens: 0,
    startedAt: 0,
    endedAt: 0,
    ...over,
  } as NodeExec
}

describe('timelineRows', () => {
  it('lays bars out as percentages of the run’s own span', () => {
    const { rows, spanMs } = timelineRows(
      [
        node({ nodeId: 'a', startedAt: 1000, endedAt: 3000 }),
        node({ nodeId: 'b', startedAt: 3000, endedAt: 5000 }),
      ],
      9999,
    )

    expect(spanMs).toBe(4000)
    expect(rows.map((r) => [r.nodeId, r.leftPct, r.widthPct])).toEqual([
      ['a', 0, 50],
      ['b', 50, 50],
    ])
  })

  it('shows a fan-out as overlapping bars, which is the whole point', () => {
    // Two workers that ran at the same time must occupy the same stretch of the axis — that
    // overlap IS the parallelism the view exists to show.
    const { rows } = timelineRows(
      [
        node({ nodeId: 'w1', startedAt: 1000, endedAt: 5000 }),
        node({ nodeId: 'w2', startedAt: 1500, endedAt: 4500 }),
      ],
      9999,
    )

    const [w1, w2] = rows
    expect(w2.leftPct).toBeGreaterThan(w1.leftPct)
    expect(w2.leftPct).toBeLessThan(w1.leftPct + w1.widthPct)
  })

  it('orders bars by when they started, not by how they arrived', () => {
    const { rows } = timelineRows(
      [
        node({ nodeId: 'late', startedAt: 5000, endedAt: 6000 }),
        node({ nodeId: 'early', startedAt: 1000, endedAt: 2000 }),
      ],
      9999,
    )

    expect(rows.map((r) => r.nodeId)).toEqual(['early', 'late'])
  })

  it('runs a still-working block up to now and says it is still running', () => {
    const { rows, endedAt } = timelineRows([node({ nodeId: 'a', startedAt: 1000, endedAt: 0 })], 4000)

    expect(rows[0].running).toBe(true)
    expect(rows[0].durationMs).toBe(3000)
    expect(endedAt).toBe(4000)
  })

  it('leaves out blocks that never started rather than drawing them at zero', () => {
    // Pending is not an error and not a moment in time; a bar for it would be a lie.
    const { rows } = timelineRows(
      [node({ nodeId: 'a', startedAt: 1000, endedAt: 2000 }), node({ nodeId: 'pending', status: 'pending' })],
      9999,
    )

    expect(rows.map((r) => r.nodeId)).toEqual(['a'])
  })

  it('keeps a very short block visible instead of collapsing it to nothing', () => {
    const { rows } = timelineRows(
      [
        node({ nodeId: 'long', startedAt: 0, endedAt: 600_000 }),
        node({ nodeId: 'blink', startedAt: 300_000, endedAt: 300_200 }),
      ],
      600_000,
    )

    expect(rows.find((r) => r.nodeId === 'blink')!.widthPct).toBeGreaterThan(0.5)
  })

  it('survives a run whose blocks all share one millisecond', () => {
    // Dividing by a zero span would render every bar NaN wide.
    const { rows, spanMs } = timelineRows([node({ nodeId: 'a', startedAt: 1000, endedAt: 1000 })], 1000)

    expect(spanMs).toBe(1)
    expect(Number.isFinite(rows[0].widthPct)).toBe(true)
  })

  it('draws a block whose end precedes its start as a sliver, not backwards', () => {
    // A clock step or a restored run can produce this; a negative width would escape the panel.
    const { rows } = timelineRows(
      [
        node({ nodeId: 'ok', startedAt: 1000, endedAt: 5000 }),
        node({ nodeId: 'weird', startedAt: 4000, endedAt: 2000 }),
      ],
      9999,
    )

    const weird = rows.find((r) => r.nodeId === 'weird')!
    expect(weird.durationMs).toBe(0)
    expect(weird.widthPct).toBeGreaterThan(0)
  })

  it('has nothing to draw for a run that never started a block', () => {
    expect(timelineRows([], 1000)).toEqual({ rows: [], startedAt: 0, endedAt: 0, spanMs: 0 })
  })
})

describe('durationLabel', () => {
  it('reads at the scale of what it measures', () => {
    expect(durationLabel(940)).toBe('940ms')
    expect(durationLabel(8400)).toBe('8.4s')
    expect(durationLabel(42_000)).toBe('42s')
    expect(durationLabel(125_000)).toBe('2m 05s')
  })
})
