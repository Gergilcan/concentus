import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { NodeExec } from '../api/types.ts'
import { RunTimeline } from './RunTimeline.tsx'

function node(over: Partial<NodeExec> & { nodeId: string; label: string }): NodeExec {
  return {
    kind: 'agent',
    status: 'passed',
    inputTokens: 0,
    outputTokens: 0,
    startedAt: 0,
    endedAt: 0,
    ...over,
  } as NodeExec
}

describe('RunTimeline', () => {
  it('draws one bar per block, with its own duration', () => {
    render(
      <RunTimeline
        nodes={[
          node({ nodeId: 'a', label: 'Planner', startedAt: 1000, endedAt: 3000 }),
          node({ nodeId: 'b', label: 'Worker', startedAt: 2000, endedAt: 6000 }),
        ]}
        now={6000}
      />,
    )

    expect(screen.getByText('Planner')).toBeInTheDocument()
    expect(screen.getByText('2.0s')).toBeInTheDocument()
    expect(screen.getByText('4.0s')).toBeInTheDocument()
    // The axis names the run's whole span, which is what the bars are fractions of.
    expect(screen.getByText('5.0s end to end')).toBeInTheDocument()
  })

  it('places overlapping work over the same stretch of the axis', () => {
    // The evidence a fan-out really was parallel — the reason this view exists.
    render(
      <RunTimeline
        nodes={[
          node({ nodeId: 'w1', label: 'Worker 1', startedAt: 1000, endedAt: 5000 }),
          node({ nodeId: 'w2', label: 'Worker 2', startedAt: 2000, endedAt: 5000 }),
        ]}
        now={5000}
      />,
    )

    const [first, second] = screen.getAllByTitle(/Worker \d:/)
    expect(first.style.left).toBe('0%')
    expect(second.style.left).toBe('25%')
    expect(second.style.width).toBe('75%')
  })

  it('says a running block is still running rather than giving it a final time', () => {
    render(<RunTimeline nodes={[node({ nodeId: 'a', label: 'Worker', startedAt: 1000, endedAt: 0 })]} now={6000} />)

    expect(screen.getByTitle(/still running/)).toBeInTheDocument()
    expect(screen.getByText(/5\.0s…/)).toBeInTheDocument()
  })

  it('puts the exact times and tokens in the tooltip, since a bar can only approximate', () => {
    render(
      <RunTimeline
        nodes={[node({ nodeId: 'a', label: 'Planner', startedAt: 1000, endedAt: 3000, inputTokens: 1200, outputTokens: 340 })]}
        now={3000}
      />,
    )

    expect(screen.getByTitle(/Planner: .* → .* \(2\.0s\) · 1\.2k in \/ 340 out/)).toBeInTheDocument()
  })

  it('says so plainly when nothing has run yet', () => {
    render(<RunTimeline nodes={[node({ nodeId: 'a', label: 'Waiting', status: 'pending' })]} now={1000} />)

    expect(screen.getByText(/Nothing has run yet/)).toBeInTheDocument()
  })
})
