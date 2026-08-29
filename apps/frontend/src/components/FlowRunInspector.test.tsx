import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, FlowRunNodeData } from '../api/types.ts'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { FlowRunInspector } from './FlowRunInspector.tsx'

const listFlowsMock = vi.fn<() => Promise<BackendFlow[]>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listFlows: () => listFlowsMock(),
  },
}))

const flow = (id: string, name: string): BackendFlow =>
  ({ id, name, mode: 'managed', nodes: [], edges: [] }) as BackendFlow

const data: FlowRunNodeData = { kind: 'flow', label: 'child', flowId: '', waitForResult: true }

const flowNode: AppNode = { id: 'fr', type: 'flow', position: { x: 0, y: 0 }, data }
const agent: AppNode = {
  id: 'a1',
  type: 'agent',
  position: { x: 0, y: 0 },
  data: { kind: 'agent', name: 'Writer', model: 'claude-sonnet-5', systemPrompt: '', maxTokens: 1, effort: 'medium' },
}

/** The canvas around the block: which way it is wired to the agent decides when it runs. */
function wire(edges: { source: string; target: string }[]) {
  useFlowStore.setState({
    nodes: [agent, flowNode],
    edges: edges.map((e, i) => ({ id: `e${i}`, ...e })),
    selectedId: 'fr',
  })
}

// "When does this run" is not a field: a block wired INTO an agent runs first, one wired OUT of
// it runs afterwards. The panel reads the drawing and says so; only nodes saved by an older
// version still carry a mode, and for those the saved setting wins and the panel says that too.
describe('FlowRunInspector', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    useFlowStore.setState({ flowId: 'me' })
    listFlowsMock.mockResolvedValue([flow('me', 'This flow'), flow('other', 'Nightly digest')])
    wire([])
  })

  it('offers every other saved flow, never this one, and forwards the choice', async () => {
    const set = vi.fn()
    render(<FlowRunInspector data={data} set={set} />)

    const select = screen.getByLabelText(/Flow to run/)
    expect(await screen.findByRole('option', { name: 'Nightly digest' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'This flow' })).not.toBeInTheDocument()
    expect(screen.getByTitle(/runs with its own budget and its own permission mode/)).toBeInTheDocument()

    fireEvent.change(select, { target: { value: 'other' } })
    expect(set).toHaveBeenCalledWith({ flowId: 'other' })
  })

  it('says there is nothing to pick when this is the only saved flow', async () => {
    listFlowsMock.mockResolvedValue([flow('me', 'This flow')])
    render(<FlowRunInspector data={data} set={vi.fn()} />)

    await waitFor(() => expect(listFlowsMock).toHaveBeenCalled())
    expect(screen.getByText(/There is no other saved flow yet/)).toBeInTheDocument()
  })

  it('a loose block is told it would never run, and still offers the wait switch', () => {
    render(<FlowRunInspector data={data} set={vi.fn()} />)

    expect(screen.getByText(/Not wired to an agent yet, so it would never run/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Wait for its answer/)).toBeChecked()
  })

  it('wired into an agent it runs first, as that agent’s context and tool', () => {
    wire([{ source: 'fr', target: 'a1' }])
    render(<FlowRunInspector data={data} set={vi.fn()} />)

    expect(screen.getByText(/so it runs first and its answer becomes that agent’s context/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Wait for its answer/)).toBeInTheDocument()
  })

  it('wired out of an agent it is a hand-off: no wait switch, and a note that a failed run hands nothing on', () => {
    wire([{ source: 'a1', target: 'fr' }])
    render(<FlowRunInspector data={data} set={vi.fn()} />)

    expect(screen.getByText(/so it runs when the flow finishes/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Wait for its answer/)).not.toBeInTheDocument()
    expect(screen.getByText(/A failed or stopped run hands nothing on/)).toBeInTheDocument()
  })

  it('wired both ways says so, rather than picking one', () => {
    wire([{ source: 'a1', target: 'fr' }, { source: 'fr', target: 'a1' }])
    render(<FlowRunInspector data={data} set={vi.fn()} />)
    expect(screen.getByText(/Wired both ways/)).toBeInTheDocument()
  })

  it('the wait switch forwards, and the hint under it follows the value', () => {
    const set = vi.fn()
    const { rerender } = render(<FlowRunInspector data={data} set={set} />)
    expect(screen.getByText(/Waits for the answer, up to ten minutes/)).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText(/Wait for its answer/))
    expect(set).toHaveBeenCalledWith({ waitForResult: false })

    rerender(<FlowRunInspector data={{ ...data, waitForResult: false }} set={set} />)
    expect(screen.getByText(/Starts the other flow and moves on/)).toBeInTheDocument()
  })

  it('a node saved with a mode reports that mode, explains the wiring separately, and can let go of it', () => {
    // The drawing says "loose"; the saved setting says "after". The compiler honours the setting.
    const set = vi.fn()
    render(<FlowRunInspector data={{ ...data, mode: 'after' }} set={set} />)

    expect(screen.getByText('Saved by an older version.')).toBeInTheDocument()
    expect(screen.getByText(/This box runs when the flow finishes, because that is what it was saved as/)).toBeInTheDocument()
    expect(screen.getByText('What the wiring would say:')).toBeInTheDocument()
    // A hand-off by its saved word: the wait switch is gone even though the wire is not there.
    expect(screen.queryByLabelText(/Wait for its answer/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Let the wiring decide instead' }))
    expect(set).toHaveBeenCalledWith({ mode: undefined })
  })
})
