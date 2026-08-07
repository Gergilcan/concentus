import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RunSummary } from '../api/types.ts'
import { RunsPanel } from './RunsPanel.tsx'

// The console fetches a run over the network the moment one is selected; nothing here is about
// that, and letting it run would make these tests depend on a backend.
vi.mock('./Console.tsx', () => ({ Console: () => <div>console</div> }))

function run(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'r1',
    flowId: 'f1',
    flowName: 'Flow one',
    mode: 'local',
    status: 'IDLE',
    createdAt: 0,
    ...overrides,
  }
}

function renderPanel(runs: RunSummary[], flowId: string | null) {
  return render(
    <RunsPanel runs={runs} selected={null} onSelect={() => {}} flowId={flowId} />,
  )
}

describe('RunsPanel', () => {
  it('shows only the open flow’s executions', () => {
    // The panel sits under the flow being edited, so other flows' runs are noise — and they make
    // the run you just started hard to find.
    renderPanel(
      [run({ id: 'a', flowId: 'f1', flowName: 'Mine' }), run({ id: 'b', flowId: 'f2', flowName: 'Other' })],
      'f1',
    )

    expect(screen.getByText('Mine')).toBeInTheDocument()
    expect(screen.queryByText('Other')).not.toBeInTheDocument()
  })

  it('shows ad-hoc runs when the open flow has not been saved', () => {
    // An unsaved flow has no id, and neither do the runs it starts.
    renderPanel([run({ id: 'a', flowId: null, flowName: 'Unsaved' }), run({ id: 'b', flowId: 'f2' })], null)

    expect(screen.getByText('Unsaved')).toBeInTheDocument()
  })

  it('says the flow has no executions rather than that there are none at all', () => {
    renderPanel([run({ flowId: 'f2' })], 'f1')

    expect(screen.getByText(/No executions for this flow yet/)).toBeInTheDocument()
  })

  it('falls back to the general empty message when nothing has ever run', () => {
    renderPanel([], 'f1')

    expect(screen.getByText(/Design a flow and press Run/)).toBeInTheDocument()
  })

  it('badges a mail-triggered run', () => {
    renderPanel([run({ trigger: 'mail' })], 'f1')

    expect(screen.getByText('✉ mail')).toBeInTheDocument()
  })

  it('does not badge a manual run, which is the unremarkable case', () => {
    renderPanel([run({ trigger: 'manual' })], 'f1')

    expect(screen.queryByText(/manual/)).not.toBeInTheDocument()
  })
})
