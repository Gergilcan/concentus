import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import { FlowsPage } from './FlowsPage.tsx'

// The page asks which flows have a golden reference on mount. Empty by default — the state of a
// dashboard nobody has marked a reference on yet — and stubbed per test where it matters.
const listGoldenStatusMock = vi.fn().mockResolvedValue([])
const goldenRerunMock = vi.fn()
const compareRunsMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listGoldenStatus: () => listGoldenStatusMock(),
    goldenRerun: (id: string) => goldenRerunMock(id),
    compareRuns: (a: string, b: string) => compareRunsMock(a, b),
  },
}))

// One flow at the root keeps the page out of its empty state so the folder grid renders.
const flow: BackendFlow = { id: 'f1', name: 'Mail triage', nodes: [], edges: [] }

const renderPage = (runs: RunSummary[] = []) => {
  const onSaveFlow = vi.fn().mockResolvedValue(undefined)
  const view = render(
    <FlowsPage
      flows={[flow]}
      runs={runs}
      onOpen={vi.fn()}
      onRun={vi.fn()}
      onDuplicate={vi.fn()}
      onDelete={vi.fn()}
      onNew={vi.fn()}
      onGenerated={vi.fn()}
      onOpenRun={vi.fn()}
      onSaveFlow={onSaveFlow}
      onRetryRun={vi.fn()}
      pushError={vi.fn()}
    />,
  )
  return { onSaveFlow, view }
}

/** The same page again with a different run list — how a poll reaches it in the real app. */
function rerenderWith(view: ReturnType<typeof render>, runs: RunSummary[]) {
  view.rerender(
    <FlowsPage
      flows={[flow]}
      runs={runs}
      onOpen={vi.fn()}
      onRun={vi.fn()}
      onDuplicate={vi.fn()}
      onDelete={vi.fn()}
      onNew={vi.fn()}
      onGenerated={vi.fn()}
      onOpenRun={vi.fn()}
      onSaveFlow={vi.fn()}
      onRetryRun={vi.fn()}
      pushError={vi.fn()}
    />,
  )
}

function run(id: string, status: RunSummary['status']): RunSummary {
  return { id, flowId: 'f1', flowName: 'Mail triage', status, createdAt: 1 }
}

/** jsdom has no DataTransfer; the drag tests carry one just real enough for the handlers. */
const makeDataTransfer = () => {
  const data: Record<string, string> = {}
  return {
    setData: (type: string, value: string) => {
      data[type] = value
    },
    getData: (type: string) => data[type] ?? '',
    get types() {
      return Object.keys(data)
    },
    dropEffect: 'none',
    effectAllowed: 'all',
  }
}

// Folder creation happens in an inline input, never window.prompt — Electron's renderer
// throws on prompt(), which made the "+ New folder" button die silently in the desktop app.
describe('FlowsPage folder creation', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('turns the tile into an input and creates the folder on Enter', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: '  Team A  ' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(screen.getByLabelText('Folder Team A')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('flows.folderDrafts') ?? '[]')).toEqual(['Team A'])
    // The input hands back to the button once the name is committed.
    expect(screen.getByText('+ New folder')).toBeInTheDocument()
  })

  it('cancels on Escape without creating anything', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Nope' } })
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(screen.queryByLabelText('Folder Nope')).not.toBeInTheDocument()
    expect(localStorage.getItem('flows.folderDrafts')).toBeNull()
    expect(screen.getByText('+ New folder')).toBeInTheDocument()
  })

  it('surfaces a nested draft ("A/B") as its first segment at the current level', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Team A/Ops' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    // At the root the draft shows as "Team A"; entering it reveals "Ops".
    fireEvent.click(screen.getByLabelText('Folder Team A'))
    expect(screen.getByLabelText('Folder Ops')).toBeInTheDocument()
  })

  it('moves a flow into a folder when its card is dropped on the tile', () => {
    const { onSaveFlow } = renderPage()
    fireEvent.click(screen.getByText('+ New folder'))
    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Team A' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    const card = screen.getByText('Mail triage').closest('article') as HTMLElement
    const tile = screen.getByLabelText('Folder Team A')
    const dataTransfer = makeDataTransfer()
    fireEvent.dragStart(card, { dataTransfer })
    fireEvent.dragOver(tile, { dataTransfer })
    fireEvent.drop(tile, { dataTransfer })

    expect(onSaveFlow).toHaveBeenCalledWith(expect.objectContaining({ id: 'f1', folder: 'Team A' }))
  })
})

// Editing a golden-covered flow should surface the check without anyone remembering it exists.
describe('FlowsPage golden regression chip', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    listGoldenStatusMock.mockResolvedValue([])
  })

  it('offers the check only when the flow drifted from its golden reference', async () => {
    listGoldenStatusMock.mockResolvedValue([
      { flowId: 'f1', runId: 'run_g', stale: false, autoRun: false },
    ])
    renderPage()

    // A flow that still matches its reference says nothing: a chip that is always there is
    // furniture, and furniture gets ignored on the day it matters.
    await waitFor(() => expect(listGoldenStatusMock).toHaveBeenCalled())
    expect(screen.queryByText(/golden outdated/)).not.toBeInTheDocument()
  })

  it('fires the golden re-run and opens the comparison once it finishes', async () => {
    listGoldenStatusMock.mockResolvedValue([
      { flowId: 'f1', runId: 'run_g', stale: true, autoRun: false },
    ])
    goldenRerunMock.mockResolvedValue(run('run_new', 'RUNNING'))
    compareRunsMock.mockResolvedValue({
      reference: { run: run('run_g', 'COMPLETED'), nodes: [], finalOutput: 'the reference answer' },
      candidate: { run: run('run_new', 'COMPLETED'), nodes: [], finalOutput: 'the candidate answer' },
    })
    const { view } = renderPage()

    fireEvent.click(await screen.findByText(/golden outdated/))
    await waitFor(() => expect(goldenRerunMock).toHaveBeenCalledWith('run_g'))
    // While it runs the chip says so rather than inviting a second run.
    expect(await screen.findByText(/testing…/)).toBeInTheDocument()

    // The dashboard already polls runs; the finished one arriving is what opens the comparison.
    rerenderWith(view, [run('run_new', 'COMPLETED')])

    expect(await screen.findByText('the reference answer')).toBeInTheDocument()
    expect(screen.getByText('the candidate answer')).toBeInTheDocument()
    expect(compareRunsMock).toHaveBeenCalledWith('run_g', 'run_new')
  })
})
