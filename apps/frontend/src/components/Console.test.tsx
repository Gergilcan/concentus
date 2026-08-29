import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunDiff, RunEvent } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { Console } from './Console.tsx'

const apiMock = {
  stopRun: vi.fn(),
  retryRun: vi.fn(),
  resumeRun: vi.fn(),
  sendCommand: vi.fn(),
  approveRun: vi.fn(),
  rejectRun: vi.fn(),
  getRunDiffs: vi.fn(),
}
const closeMock = vi.fn()
const openRunSocketMock = vi.fn(() => ({ close: closeMock }))

vi.mock('../api/client.ts', () => ({
  api: {
    stopRun: (...a: unknown[]) => apiMock.stopRun(...a),
    retryRun: (...a: unknown[]) => apiMock.retryRun(...a),
    resumeRun: (...a: unknown[]) => apiMock.resumeRun(...a),
    sendCommand: (...a: unknown[]) => apiMock.sendCommand(...a),
    approveRun: (...a: unknown[]) => apiMock.approveRun(...a),
    rejectRun: (...a: unknown[]) => apiMock.rejectRun(...a),
    getRunDiffs: (...a: unknown[]) => apiMock.getRunDiffs(...a),
  },
  openRunSocket: (...a: unknown[]) => openRunSocketMock(...(a as [])),
}))

const event = (over: Partial<RunEvent>): RunEvent => ({
  type: 'agent_message',
  text: 'hello',
  agent: 'Writer',
  agentId: 'writer',
  ts: 0,
  ...over,
})

/** Events reach the store through the socket; the Console clears the buffer on mount, so they
 *  are pushed after render — the way the socket would. */
function speak(...events: RunEvent[]) {
  act(() => {
    for (const e of events) useFlowStore.getState().addRunEvent(e)
  })
}

const diff = (over: Partial<RunDiff> = {}): RunDiff => ({
  nodeId: 'writer',
  label: 'Writer',
  folder: 'concentus',
  patch: 'diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1,2 @@\n one\n+two\n',
  stats: { files: 1, additions: 1, deletions: 0 },
  note: null,
  takenAt: 1,
  ...over,
})

// The console is the run's transcript and its controls: one socket feeding the store, the lines
// per agent, and the buttons that only do something while there is something to do it to.
describe('Console', () => {
  beforeAll(() => {
    // jsdom lays nothing out, so the scroll-to-bottom the log does after every event has nothing
    // to call; the stub keeps it from throwing.
    Element.prototype.scrollIntoView = vi.fn()
  })
  beforeEach(() => {
    useFlowStore.setState({
      runEvents: [],
      runDiffs: [],
      runTotals: { input: 0, output: 0, costUsd: 0 },
      runGraph: null,
      runExecByNode: {},
    })
    for (const fn of Object.values(apiMock)) fn.mockResolvedValue(undefined)
  })
  afterEach(() => vi.clearAllMocks())

  it('opens one socket for the run and closes it when it goes', () => {
    const { unmount } = render(<Console runId="run_1" status="RUNNING" />)
    expect(openRunSocketMock).toHaveBeenCalledWith('run_1', expect.any(Function), expect.any(Function))
    expect(screen.getByRole('status', { name: 'Waiting for output' })).toBeInTheDocument()

    unmount()
    expect(closeMock).toHaveBeenCalled()
  })

  it('renders each line with who said it, and offers per-agent chips once two agents have spoken', () => {
    render(<Console runId="run_1" status="RUNNING" />)
    speak(event({ text: 'drafting the summary' }))
    expect(screen.getByText('drafting the summary')).toBeInTheDocument()
    expect(screen.getByText('Writer')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'All agents' })).not.toBeInTheDocument()

    speak(event({ agent: 'Reviewer', agentId: 'reviewer', text: 'checking the draft' }))
    expect(screen.getByRole('button', { name: 'All agents' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reviewer' }))
    expect(screen.queryByText('drafting the summary')).not.toBeInTheDocument()
    expect(screen.getByText('checking the draft')).toBeInTheDocument()

    // The same chip again puts the filter away.
    fireEvent.click(screen.getByRole('button', { name: 'Reviewer' }))
    expect(screen.getByText('drafting the summary')).toBeInTheDocument()
  })

  it('Stop is live only while the run is in flight', () => {
    const { rerender } = render(<Console runId="run_1" status="RUNNING" />)
    const stop = screen.getByRole('button', { name: 'Stop' })
    expect(stop).toBeEnabled()
    expect(stop).toHaveAttribute('title', 'Stop the agents')
    fireEvent.click(stop)
    expect(apiMock.stopRun).toHaveBeenCalledWith('run_1')

    rerender(<Console runId="run_1" status="COMPLETED" />)
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop' })).toHaveAttribute('title', 'Nothing is running to stop')

    // IDLE is a turn-based run between commands: nothing to kill.
    rerender(<Console runId="run_1" status="IDLE" />)
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()
  })

  it('Retry and Resume act on this run, and say what they failed with', async () => {
    render(<Console runId="run_1" status="ERROR" />)

    fireEvent.click(screen.getByRole('button', { name: '⟳ Retry' }))
    expect(apiMock.retryRun).toHaveBeenCalledWith('run_1')
    fireEvent.click(screen.getByRole('button', { name: '⟲ Resume' }))
    expect(apiMock.resumeRun).toHaveBeenCalledWith('run_1')

    apiMock.resumeRun.mockRejectedValue(new Error('nothing to resume'))
    fireEvent.click(screen.getByRole('button', { name: '⟲ Resume' }))
    expect(await screen.findByText('nothing to resume')).toBeInTheDocument()
  })

  it('sends a command on Enter, clears the box, and ignores an empty one', async () => {
    render(<Console runId="run_1" status="IDLE" />)
    const box = screen.getByPlaceholderText('Send a command to the running agents…')

    fireEvent.keyDown(box, { key: 'Enter' })
    expect(apiMock.sendCommand).not.toHaveBeenCalled()

    fireEvent.change(box, { target: { value: '  ship it  ' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(apiMock.sendCommand).toHaveBeenCalledWith('run_1', 'ship it')
    await waitFor(() => expect(box).toHaveValue(''))
  })

  it('waiting for approval puts Approve and Reject on screen, and they decide the run', async () => {
    render(<Console runId="run_1" status="AWAITING_APPROVAL" />)
    expect(screen.getByText('Waiting for your approval.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(apiMock.approveRun).toHaveBeenCalledWith('run_1')
    // Both buttons sleep while a decision is in flight, so a double click cannot decide twice.
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled())

    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(apiMock.rejectRun).toHaveBeenCalledWith('run_1')
  })

  it('a question from the agent points at the command box, with no decision buttons', () => {
    render(<Console runId="run_1" status="AWAITING_ANSWER" />)
    expect(screen.getByText('The agent asked you something.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('offers Changes only for a run that cloned something, counts the checkouts that changed, and refreshes from disk', async () => {
    const { rerender } = render(<Console runId="run_1" status="COMPLETED" />)
    expect(screen.getByRole('button', { name: 'Output' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Timeline' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Changes/ })).not.toBeInTheDocument()

    act(() => useFlowStore.setState({ runDiffs: [diff(), diff({ nodeId: 'merge', label: 'Merge', patch: null })] }))
    rerender(<Console runId="run_1" status="COMPLETED" />)
    fireEvent.click(screen.getByRole('button', { name: 'Changes (1)' }))
    expect(screen.getByRole('region', { name: 'Writer · ./concentus' })).toBeInTheDocument()

    apiMock.getRunDiffs.mockResolvedValue([diff({ stats: { files: 2, additions: 3, deletions: 1 } })])
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(apiMock.getRunDiffs).toHaveBeenCalledWith('run_1')
    await waitFor(() => expect(useFlowStore.getState().runDiffs[0].stats.files).toBe(2))
  })

  it('the Timeline view says so while nothing has run', () => {
    render(<Console runId="run_1" status="STARTING" />)
    fireEvent.click(screen.getByRole('button', { name: 'Timeline' }))
    expect(screen.getByText(/Nothing has run yet/)).toBeInTheDocument()
  })

  it('names the flow revision and the token total when it has them', () => {
    useFlowStore.setState({ runTotals: { input: 1200, output: 300, costUsd: 0.05 } })
    render(<Console runId="run_1" status="COMPLETED" flowVersion={3} />)

    expect(screen.getByText('⑂ flow version v3')).toBeInTheDocument()
    // The thousands separator is the machine's own; the test must not assume English.
    expect(screen.getByText(`Σ execution tokens · in ${(1200).toLocaleString()} · out 300`)).toBeInTheDocument()
    expect(screen.getByText(/≈\$0\.05/)).toBeInTheDocument()
  })
})
