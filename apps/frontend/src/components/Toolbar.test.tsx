import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import { PermissionsProvider } from '../state/permissions.tsx'
import { useFlowStore } from '../state/store.ts'
import { Toolbar } from './Toolbar.tsx'

const saveFlowMock = vi.fn<(f: BackendFlow) => Promise<BackendFlow>>()
const startRunMock = vi.fn<(f: BackendFlow) => Promise<RunSummary>>()

vi.mock('../api/client.ts', () => ({
  api: {
    saveFlow: (f: BackendFlow) => saveFlowMock(f),
    startRun: (f: BackendFlow) => startRunMock(f),
  },
}))

// The dialogs the toolbar opens have their own tests and their own API calls; here they only
// need to be seen opening.
vi.mock('./DoctorModal.tsx', () => ({
  DoctorModal: ({ flowName }: { flowName: string }) => <div>doctor for {flowName}</div>,
}))
vi.mock('./FlowVersions.tsx', () => ({ FlowVersions: () => <div>versions list</div> }))
vi.mock('./FlowEvaluationPanel.tsx', () => ({ FlowEvaluationPanel: () => <div>evaluations list</div> }))

type Props = Partial<React.ComponentProps<typeof Toolbar>>

function renderToolbar(role: string | null = 'ADMIN', props: Props = {}) {
  const handlers = {
    onFlowsChanged: vi.fn(),
    onRunStarted: vi.fn(),
    onBackToFlows: vi.fn(),
    pushError: vi.fn(),
    ...props,
  }
  render(
    <PermissionsProvider role={role}>
      <Toolbar {...handlers} />
    </PermissionsProvider>,
  )
  return handlers
}

// The toolbar is where the flow is named, saved and run. Every button that cannot do its job
// right now says why in its tooltip instead of disappearing or failing after the click.
describe('Toolbar', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    useFlowStore.setState({ name: 'Nightly digest' })
  })
  afterEach(() => vi.clearAllMocks())

  it('shows the flow name and mode from the store and writes edits back', () => {
    renderToolbar()

    const name = screen.getByLabelText('Flow name')
    expect(name).toHaveValue('Nightly digest')
    fireEvent.change(name, { target: { value: 'Weekly digest' } })
    expect(useFlowStore.getState().name).toBe('Weekly digest')

    fireEvent.change(screen.getByTitle('managed = multi-agent execution'), { target: { value: 'local' } })
    expect(useFlowStore.getState().mode).toBe('local')
  })

  it('Back hands control to the flows list', () => {
    const { onBackToFlows } = renderToolbar()
    fireEvent.click(screen.getByRole('button', { name: /Flows/ }))
    expect(onBackToFlows).toHaveBeenCalled()
  })

  it('Undo and Redo follow the history: nothing to undo, then one step, then one to redo', () => {
    renderToolbar()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeDisabled()

    act(() => useFlowStore.getState().addNode('agent'))
    expect(screen.getByRole('button', { name: 'Undo' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(useFlowStore.getState().nodes).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Redo' }))
    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })

  it('Tidy needs two blocks to have anything to arrange', () => {
    renderToolbar()
    const tidy = screen.getByRole('button', { name: /Tidy/ })
    expect(tidy).toBeDisabled()
    act(() => useFlowStore.getState().addNode('coordinator'))
    expect(tidy).toBeDisabled()
    act(() => useFlowStore.getState().addNode('agent'))
    expect(tidy).toBeEnabled()
    expect(tidy).toHaveAttribute('title', expect.stringContaining('One Ctrl+Z away from undone'))
  })

  it('Check, Versions and Evaluations wait for a saved flow and say so', () => {
    renderToolbar()
    for (const name of [/Check/, 'Versions', 'Evaluations']) {
      const b = screen.getByRole('button', { name })
      expect(b).toBeDisabled()
      expect(b).toHaveAttribute('title', expect.stringContaining('Save the flow first'))
    }

    act(() => useFlowStore.setState({ flowId: 'flow_1' }))
    fireEvent.click(screen.getByRole('button', { name: /Check/ }))
    expect(screen.getByText('doctor for Nightly digest')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Versions' }))
    expect(screen.getByText('Versions — Nightly digest')).toBeInTheDocument()
    expect(screen.getByText('versions list')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Evaluations' }))
    expect(screen.getByText('Evaluations — Nightly digest')).toBeInTheDocument()
    expect(screen.getByText('evaluations list')).toBeInTheDocument()
  })

  it('Save sends the canvas as a backend flow, loads what came back and tells the list', async () => {
    saveFlowMock.mockImplementation((f) => Promise.resolve({ ...f, id: 'flow_9' }))
    const { onFlowsChanged, pushError } = renderToolbar()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onFlowsChanged).toHaveBeenCalled())
    expect(saveFlowMock).toHaveBeenCalledWith(expect.objectContaining({ name: 'Nightly digest', mode: 'managed' }))
    expect(useFlowStore.getState().flowId).toBe('flow_9')
    expect(pushError).not.toHaveBeenCalled()
  })

  it('a failed Save is reported, not swallowed', async () => {
    saveFlowMock.mockRejectedValue(new Error('403 from the server'))
    const { onFlowsChanged, pushError } = renderToolbar()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(pushError).toHaveBeenCalledWith('403 from the server'))
    expect(onFlowsChanged).not.toHaveBeenCalled()
  })

  it('Run starts the canvas as it stands and hands the run over; a refusal is reported', async () => {
    const run = { id: 'run_1', flowId: null, flowName: null } as RunSummary
    startRunMock.mockResolvedValue(run)
    const { onRunStarted, pushError } = renderToolbar()

    fireEvent.click(screen.getByRole('button', { name: /Run/ }))
    await waitFor(() => expect(onRunStarted).toHaveBeenCalledWith(run))
    expect(startRunMock).toHaveBeenCalledWith(expect.objectContaining({ name: 'Nightly digest' }))

    startRunMock.mockRejectedValue(new Error('budget exhausted'))
    fireEvent.click(screen.getByRole('button', { name: /Run/ }))
    await waitFor(() => expect(pushError).toHaveBeenCalledWith('budget exhausted'))
  })

  it('New starts an empty flow', () => {
    useFlowStore.getState().addNode('agent')
    renderToolbar()
    fireEvent.click(screen.getByRole('button', { name: 'New' }))
    expect(useFlowStore.getState().nodes).toHaveLength(0)
    expect(useFlowStore.getState().name).toBe('Untitled flow')
  })

  it('a viewer can neither edit nor run, and each button names the role in its tooltip', () => {
    useFlowStore.getState().addNode('coordinator')
    useFlowStore.getState().addNode('agent')
    renderToolbar('VIEWER')

    for (const name of ['Save', 'New', 'Undo', /Tidy/]) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute(
      'title',
      'Your role (viewer) cannot change flows. An admin can change that under Resources → Members.',
    )
    expect(screen.getByRole('button', { name: /Run/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Run/ })).toHaveAttribute(
      'title',
      'Your role (viewer) can read this flow but not run it. An admin can change that under Resources → Members.',
    )
  })

  it('an operator may run but not save', () => {
    renderToolbar('OPERATOR')
    expect(screen.getByRole('button', { name: /Run/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /Run/ })).not.toHaveAttribute('title')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})
