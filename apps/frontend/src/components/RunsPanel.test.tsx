import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RunSummary } from '../api/types.ts'
import { RunsPanel } from './RunsPanel.tsx'

// The console fetches a run over the network the moment one is selected; nothing here is about
// that, and letting it run would make these tests depend on a backend.
vi.mock('./Console.tsx', () => ({ Console: () => <div>console</div> }))

const setGoldenRunMock = vi.fn()
const goldenRerunMock = vi.fn()
const compareRunsMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    setGoldenRun: (id: string, golden: boolean) => setGoldenRunMock(id, golden),
    goldenRerun: (id: string) => goldenRerunMock(id),
    compareRuns: (a: string, b: string) => compareRunsMock(a, b),
  },
}))

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

function renderPanel(
  runs: RunSummary[],
  flowId: string | null,
  props: Partial<Parameters<typeof RunsPanel>[0]> = {},
) {
  return render(
    <RunsPanel runs={runs} selected={null} onSelect={() => {}} flowId={flowId} {...props} />,
  )
}

describe('RunsPanel', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

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

describe('RunsPanel golden runs', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('offers the star only on runs of a saved flow', () => {
    // An ad-hoc run cannot be a reference (no flow to belong to), so it gets no star at all —
    // absent rather than present-but-erroring, same reasoning as the backend's tool list.
    renderPanel([run({ id: 'a', flowId: null, flowName: 'Unsaved' })], null)

    expect(screen.queryByLabelText(/golden reference/)).not.toBeInTheDocument()
  })

  it('marks a run golden through the API and fills its star immediately', async () => {
    setGoldenRunMock.mockResolvedValue(run({ golden: true }))
    renderPanel([run()], 'f1')

    fireEvent.click(screen.getByLabelText('Mark as golden reference'))

    await waitFor(() => expect(setGoldenRunMock).toHaveBeenCalledWith('r1', true))
    // The polled list is seconds behind; the local override keeps the star honest meanwhile.
    expect(await screen.findByLabelText('Unmark golden reference')).toBeInTheDocument()
  })

  it('moves the star when a second run is promoted', async () => {
    setGoldenRunMock.mockResolvedValue(run({ id: 'r2', golden: true }))
    renderPanel([run({ id: 'r1', golden: true }), run({ id: 'r2' })], 'f1')

    fireEvent.click(screen.getByLabelText('Mark as golden reference')) // the r2 star

    // One reference per flow: r2's star fills and r1's empties without waiting for the poll.
    await waitFor(() => expect(screen.getAllByLabelText('Unmark golden reference')).toHaveLength(1))
    expect(screen.getByLabelText('Mark as golden reference')).toBeInTheDocument()
  })

  it('re-runs the golden reference against the current flow and selects the new run', async () => {
    goldenRerunMock.mockResolvedValue(run({ id: 'r-new', trigger: 'golden' }))
    const onSelect = vi.fn()
    renderPanel([run({ golden: true })], 'f1', { onSelect })

    fireEvent.click(screen.getByText(/Test current flow/))

    await waitFor(() => expect(goldenRerunMock).toHaveBeenCalledWith('r1'))
    expect(onSelect).toHaveBeenCalledWith('r-new')
  })

  it('compares the selected run against the golden reference', async () => {
    compareRunsMock.mockResolvedValue({
      reference: { run: run({ golden: true }), nodes: [], finalOutput: 'the reference answer' },
      candidate: { run: run({ id: 'r2' }), nodes: [], finalOutput: 'the candidate answer' },
    })
    renderPanel([run({ golden: true }), run({ id: 'r2' })], 'f1', { selected: 'r2' })

    fireEvent.click(screen.getByText('⇄ Compare'))

    await waitFor(() => expect(compareRunsMock).toHaveBeenCalledWith('r1', 'r2'))
    expect(await screen.findByText('the reference answer')).toBeInTheDocument()
    expect(screen.getByText('the candidate answer')).toBeInTheDocument()
  })

  it('keeps Compare disabled while the golden run itself is selected', () => {
    renderPanel([run({ golden: true })], 'f1', { selected: 'r1' })

    expect(screen.getByText('⇄ Compare')).toBeDisabled()
  })

  it('shows no golden bar when the flow has no reference yet', () => {
    renderPanel([run()], 'f1')

    expect(screen.queryByText(/Test current flow/)).not.toBeInTheDocument()
  })
})
