import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

  it('badges the flow version an execution ran', () => {
    renderPanel([run({ flowVersion: 12 })], 'f1')

    expect(screen.getByText('v12')).toBeInTheDocument()
  })

  it('shows no version badge for a run that recorded none', () => {
    // Runs from before versions were recorded, and runs of an unsaved flow: no revision to name,
    // so no badge — a made-up "v0" would read as a real revision.
    renderPanel([run({ flowVersion: 0 })], 'f1')

    expect(screen.queryByText(/^v\d/)).not.toBeInTheDocument()
  })

  it('names the runner an execution ran on, and says so on hover', () => {
    // Stamped at launch: the name is the runner's as it was then, whatever happened to it since.
    renderPanel([run({ runnerId: 'rn_1', runnerName: 'office-pc' })], 'f1')

    expect(screen.getByText('office-pc')).toHaveAttribute('title', 'Ran on runner office-pc')
  })

  it('shows no runner chip for a run on this server', () => {
    renderPanel([run({ runnerId: null, runnerName: null })], 'f1')

    expect(screen.queryByTitle(/Ran on runner/)).not.toBeInTheDocument()
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
// Comparing used to require a golden reference, which is the right default and was the wrong
  // only option: two runs of the same block on different models have no known-good side at all.
  it('compares two ordinary runs on a flow that has no reference', async () => {
    compareRunsMock.mockResolvedValue({
      reference: { run: run({ id: 'r1' }), nodes: [], finalOutput: 'the first answer' },
      candidate: { run: run({ id: 'r2' }), nodes: [], finalOutput: 'the second answer' },
    })
    renderPanel([run({ id: 'r1' }), run({ id: 'r2' })], 'f1', { selected: 'r2' })

    fireEvent.click(screen.getByText('⇄ Compare'))

    // Exactly one other run: no dialog, because a choice of one teaches nothing.
    await waitFor(() => expect(compareRunsMock).toHaveBeenCalledWith('r1', 'r2'))
    expect(await screen.findByText('the first answer')).toBeInTheDocument()
  })

  it('asks which run to compare against once there is more than one', async () => {
    renderPanel([run({ id: 'r1', golden: true }), run({ id: 'r2' }), run({ id: 'r3' })], 'f1',
      { selected: 'r3' })

    fireEvent.click(screen.getByText('⇄ Compare'))

    expect(await screen.findByRole('dialog', { name: /Compare with which execution/ }))
        .toBeInTheDocument()
    expect(compareRunsMock).not.toHaveBeenCalled()
  })

  it('compares against the run picked in the dialog', async () => {
    compareRunsMock.mockResolvedValue({
      reference: { run: run({ id: 'r2' }), nodes: [], finalOutput: 'the picked answer' },
      candidate: { run: run({ id: 'r3' }), nodes: [], finalOutput: 'the selected answer' },
    })
    renderPanel([run({ id: 'r1', golden: true }), run({ id: 'r2' }), run({ id: 'r3' })], 'f1',
      { selected: 'r3' })

    fireEvent.click(screen.getByText('⇄ Compare'))
    const dialog = await screen.findByRole('dialog', { name: /Compare with which execution/ })
    // Every row is a run; the first is the golden one, pinned there because it is still the
    // answer more often than any other single row.
    const rows = within(dialog).getAllByRole('button').filter((b) => b.textContent?.includes('IDLE'))
    fireEvent.click(rows[rows.length - 1])

    await waitFor(() => expect(compareRunsMock).toHaveBeenCalled())
  })

  it('keeps Compare disabled when the flow has a single run', () => {
    renderPanel([run()], 'f1', { selected: 'r1' })

    expect(screen.getByText('⇄ Compare')).toBeDisabled()
  })
// The cost was only visible inside a comparison, which is one click and one decision too late
  // for the question it answers: is another run of this worth it?
  it('shows what each execution cost in the list', () => {
    renderPanel([run({ id: 'r1', estimatedCostUsd: 4.35 })], 'f1')

    expect(screen.getByTitle(/equivalent usage/)).toHaveTextContent('4.35')
  })

  it('says nothing where there is no estimate rather than showing a zero', () => {
    renderPanel([run({ id: 'r1' })], 'f1')

    expect(screen.queryByTitle(/equivalent usage/)).not.toBeInTheDocument()
  })
})

describe('RunsPanel run list width', () => {
  const KEY = 'studio.runs.listWidth'
  let rect: ReturnType<typeof vi.spyOn>

  // jsdom lays nothing out, so the dock — and everything else — measures 1000px wide at x=0.
  beforeEach(() => {
    localStorage.clear()
    rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 260,
      right: 1000,
      width: 1000,
      height: 260,
      toJSON: () => ({}),
    })
  })
  afterEach(() => {
    rect.mockRestore()
  })

  const handle = () => screen.getByRole('separator')
  const width = () => Number(handle().getAttribute('aria-valuenow'))
  const cssWidth = () => handle().parentElement!.style.getPropertyValue('--run-list-w')

  it('starts at the default width and lays the list out with it', () => {
    renderPanel([run()], 'f1')

    expect(handle()).toHaveAttribute('aria-orientation', 'vertical')
    expect(width()).toBe(260)
    expect(cssWidth()).toBe('260px')
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('starts at the remembered width, applied on the first render', () => {
    localStorage.setItem(KEY, '340')
    renderPanel([run()], 'f1')

    expect(width()).toBe(340)
    expect(cssWidth()).toBe('340px')
  })

  it('ignores a remembered value that is not a width', () => {
    localStorage.setItem(KEY, 'wide')
    renderPanel([run()], 'f1')

    expect(width()).toBe(260)
  })

  it('a drag moves the split, and the width is remembered once the pointer is released', () => {
    renderPanel([run()], 'f1')

    fireEvent.pointerDown(handle(), { button: 0, pointerId: 1, clientX: 260 })
    fireEvent.pointerMove(handle(), { pointerId: 1, clientX: 320 })
    expect(width()).toBe(320)
    // Dozens of moves a second: nothing is written until the drag ends.
    expect(localStorage.getItem(KEY)).toBeNull()

    fireEvent.pointerMove(handle(), { pointerId: 1, clientX: 400 })
    fireEvent.pointerUp(handle(), { pointerId: 1, clientX: 400 })
    expect(width()).toBe(400)
    expect(cssWidth()).toBe('400px')
    expect(localStorage.getItem(KEY)).toBe('400')
  })

  it('a move that is not a drag leaves the split alone', () => {
    renderPanel([run()], 'f1')

    fireEvent.pointerMove(handle(), { pointerId: 1, clientX: 500 })

    expect(width()).toBe(260)
  })

  it('holds the list between 200px and 60% of the dock', () => {
    renderPanel([run()], 'f1')

    fireEvent.pointerDown(handle(), { button: 0, pointerId: 1, clientX: 260 })
    fireEvent.pointerMove(handle(), { pointerId: 1, clientX: 40 })
    expect(width()).toBe(200)
    fireEvent.pointerMove(handle(), { pointerId: 1, clientX: 950 })
    expect(width()).toBe(600)
    fireEvent.pointerUp(handle(), { pointerId: 1, clientX: 950 })

    expect(localStorage.getItem(KEY)).toBe('600')
    expect(handle()).toHaveAttribute('aria-valuemin', '200')
    expect(handle()).toHaveAttribute('aria-valuemax', '600')
  })

  it('pulls a remembered width wider than the dock allows back before it is painted', () => {
    localStorage.setItem(KEY, '900')
    renderPanel([run()], 'f1')

    expect(width()).toBe(600)
  })

  it('the arrow keys move the split by 16px and remember it', () => {
    renderPanel([run()], 'f1')
    handle().focus()

    fireEvent.keyDown(handle(), { key: 'ArrowRight' })
    expect(width()).toBe(276)
    expect(localStorage.getItem(KEY)).toBe('276')

    fireEvent.keyDown(handle(), { key: 'ArrowLeft' })
    fireEvent.keyDown(handle(), { key: 'ArrowLeft' })
    expect(width()).toBe(244)
    expect(localStorage.getItem(KEY)).toBe('244')
  })

  it('a double-click puts the split back to the default and forgets the remembered width', () => {
    localStorage.setItem(KEY, '400')
    renderPanel([run()], 'f1')
    expect(width()).toBe(400)

    fireEvent.doubleClick(handle())

    expect(width()).toBe(260)
    expect(localStorage.getItem(KEY)).toBeNull()
  })
})
