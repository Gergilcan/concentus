import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { NodeExec, RunComparison, RunSummary } from '../api/types.ts'
import { CompareRunsModal } from './CompareRunsModal.tsx'

const comparison: RunComparison = {
  reference: { run: run('r1'), nodes: [node('Coordinator', 87_000)], finalOutput: 'golden answer' },
  candidate: { run: run('r2'), nodes: [node('Coordinator', 120_000)], finalOutput: 'new answer' },
}

vi.mock('../api/client.ts', () => ({
  api: { compareRuns: () => Promise.resolve(comparison) },
}))

function run(id: string): RunSummary {
  return {
    id,
    flowId: 'f1',
    flowName: 'Flow',
    status: 'COMPLETED',
    createdAt: 0,
    totalInputTokens: 1_000,
    totalOutputTokens: 500,
    estimatedCostUsd: 0.5,
  }
}

function node(label: string, ctx: number): NodeExec {
  return {
    nodeId: label,
    kind: 'agent',
    label,
    status: 'passed',
    inputTokens: 1_000,
    outputTokens: 8_400,
    startedAt: 0,
    endedAt: 0,
    contextTokens: ctx,
    contextStartTokens: 24_000,
    contextWindow: 200_000,
    input: 'x'.repeat(4_000),
  }
}

// A step's context is one figure and one bar in the comparison; the /context-style split behind
// it is a dialog, so the two columns stay readable side by side.
describe('CompareRunsModal context breakdown', () => {
  it('opens the breakdown for the step whose ctx figure is clicked', async () => {
    render(<CompareRunsModal referenceId="r1" candidateId="r2" onClose={() => {}} />)
    const [golden] = await screen.findAllByTitle('Context breakdown for Coordinator')
    expect(screen.queryByRole('dialog', { name: 'Context usage' })).not.toBeInTheDocument()

    fireEvent.click(golden)
    const dialog = screen.getByRole('dialog', { name: 'Context usage' })
    // The golden side's own numbers, not the candidate's 120k.
    expect(dialog.textContent).toContain('87.0k / 200.0k tokens (44%)')
  })

  it('lets Escape close the breakdown without closing the comparison under it', async () => {
    const onClose = vi.fn()
    render(<CompareRunsModal referenceId="r1" candidateId="r2" onClose={onClose} />)
    fireEvent.click((await screen.findAllByTitle('Context breakdown for Coordinator'))[0])

    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Context usage' })).not.toBeInTheDocument(),
    )
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: /Two executions, side by side/ })).toBeInTheDocument()

    // With the breakdown gone, the next Escape belongs to the comparison again.
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  // The title names what the reader is actually looking at. "Compared with the golden reference"
  // was true of every comparison there used to be, and is a lie about two ordinary runs.
  it('says which kind of comparison this is', async () => {
    render(<CompareRunsModal referenceId="r1" candidateId="r2" onClose={() => {}} />)

    expect(await screen.findByRole('dialog', { name: 'Two executions, side by side' }))
        .toBeInTheDocument()
  })
})
