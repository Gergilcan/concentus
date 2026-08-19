import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useFlowStore } from '../state/store.ts'
import { RerunBlockDialog } from './RerunBlockDialog.tsx'

const rerunBlockMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    rerunBlock: (runId: string, nodeId: string, input: string, downstream: boolean, model?: string) =>
      rerunBlockMock(runId, nodeId, input, downstream, model),
    getModelCatalog: () => Promise.reject(new Error('no catalog in this test')),
  },
}))

function open() {
  render(
    <RerunBlockDialog
      runId="run_1"
      nodeId="writer"
      label="Writer"
      recordedInput="Write the daily briefing."
      onClose={() => {}}
    />,
  )
}

// Re-running one block is cheap only if it reproduces that block's conditions; the input it
// actually received is the expensive half to reproduce, so it arrives already in the box.
describe('RerunBlockDialog', () => {
  it('opens with the input the block received', () => {
    open()
    expect(screen.getByLabelText(/input this block received/i)).toHaveValue(
      'Write the daily briefing.',
    )
  })

  it('sends the edited input and selects the run it started', async () => {
    rerunBlockMock.mockResolvedValueOnce({ id: 'run_2' })
    open()

    fireEvent.change(screen.getByLabelText(/input this block received/i), {
      target: { value: 'Write it in Catalan.' },
    })
    fireEvent.click(screen.getByRole('button', { name: /run this block/i }))

    await waitFor(() =>
      expect(rerunBlockMock).toHaveBeenCalledWith('run_1', 'writer', 'Write it in Catalan.', false, ''),
    )
    await waitFor(() => expect(useFlowStore.getState().activeRunId).toBe('run_2'))
  })

  it('carries the agents it delegates to when continuing from here', async () => {
    rerunBlockMock.mockResolvedValueOnce({ id: 'run_3' })
    open()

    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /run this block/i }))

    await waitFor(() =>
      expect(rerunBlockMock).toHaveBeenCalledWith(
        'run_1',
        'writer',
        'Write the daily briefing.',
        true,
        '',
      ),
    )
  })

  // Same input, same instructions, one thing changed — which is the only shape in which "would
  // the cheaper model do this just as well" has an answer worth reading.
  it('can send the block to a different model', async () => {
    rerunBlockMock.mockResolvedValueOnce({ id: 'run_4' })
    open()

    fireEvent.change(screen.getByLabelText(/another model/i), {
      target: { value: 'claude-haiku-4-5' },
    })
    fireEvent.click(screen.getByRole('button', { name: /run this block/i }))

    await waitFor(() =>
      expect(rerunBlockMock).toHaveBeenCalledWith(
        'run_1',
        'writer',
        'Write the daily briefing.',
        false,
        'claude-haiku-4-5',
      ),
    )
  })

  it('keeps the dialog open and says why when the run is refused', async () => {
    rerunBlockMock.mockRejectedValueOnce(new Error('This block recorded no input to run again.'))
    open()

    fireEvent.click(screen.getByRole('button', { name: /run this block/i }))

    expect(await screen.findByText(/recorded no input/i)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
