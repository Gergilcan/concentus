import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, FlowMemoryView } from '../api/types.ts'
import { SettingsModal } from './FlowModals.tsx'

const getFlowMemoryMock = vi.fn()
const clearFlowMemoryMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getFlowMemory: (id: string) => getFlowMemoryMock(id),
    clearFlowMemory: (id: string) => clearFlowMemoryMock(id),
  },
}))

const flow: BackendFlow = { id: 'f1', name: 'Mail triage', mode: 'local', nodes: [], edges: [] }

const memory: FlowMemoryView = {
  available: true,
  count: 2,
  notes: [
    { id: 2, runId: 'run_b', note: 'invoices done through July', createdAt: Date.now() },
    { id: 1, runId: 'run_a', note: 'the API needs paging', createdAt: Date.now() - 60_000 },
  ],
}

describe('SettingsModal agent memory', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows the note count and reveals the notes on demand', async () => {
    getFlowMemoryMock.mockResolvedValue(memory)
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={vi.fn()} />)

    expect(await screen.findByText(/Agent memory · 2 notes/)).toBeInTheDocument()
    // Collapsed by default: the settings dialog stays short unless you ask.
    expect(screen.queryByText('invoices done through July')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('View'))
    expect(screen.getByText('invoices done through July')).toBeInTheDocument()
    expect(screen.getByText('the API needs paging')).toBeInTheDocument()
  })

  it('forgets every note only after the user confirms', async () => {
    getFlowMemoryMock.mockResolvedValue(memory)
    clearFlowMemoryMock.mockResolvedValue(undefined)
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={vi.fn()} />)

    fireEvent.click(await screen.findByText('Forget all'))

    await waitFor(() => expect(clearFlowMemoryMock).toHaveBeenCalledWith('f1'))
    expect(await screen.findByText(/Agent memory · 0 notes/)).toBeInTheDocument()
    confirmSpy.mockRestore()
  })

  it('does not clear when the user cancels the confirmation', async () => {
    getFlowMemoryMock.mockResolvedValue(memory)
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={vi.fn()} />)

    fireEvent.click(await screen.findByText('Forget all'))

    expect(clearFlowMemoryMock).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })

  it('says when storage is down instead of showing an innocent zero', async () => {
    getFlowMemoryMock.mockResolvedValue({ available: false, count: 0, notes: [] })
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={vi.fn()} />)

    expect(await screen.findByText(/Storage is unavailable/)).toBeInTheDocument()
  })

  it('shows no memory section for an unsaved flow', () => {
    render(
      <SettingsModal flow={{ ...flow, id: undefined }} onClose={vi.fn()} onSave={vi.fn()} />,
    )

    expect(getFlowMemoryMock).not.toHaveBeenCalled()
    expect(screen.queryByText(/Agent memory/)).not.toBeInTheDocument()
  })
})
