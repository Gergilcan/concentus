import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, FlowMemoryView } from '../api/types.ts'
import { SettingsModal } from './FlowModals.tsx'

const getFlowMemoryMock = vi.fn()
const clearFlowMemoryMock = vi.fn()
const listCredentialsMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getFlowMemory: (id: string) => getFlowMemoryMock(id),
    clearFlowMemory: (id: string) => clearFlowMemoryMock(id),
    listCredentials: () => listCredentialsMock(),
    // The settings modal loads the organization variables to show as override defaults.
    listVariables: () => Promise.resolve([]),
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

describe('SettingsModal remote approval', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('saves the Slack channel and Teams webhook with the other settings', async () => {
    getFlowMemoryMock.mockResolvedValue({ available: true, count: 0, notes: [] })
    listCredentialsMock.mockResolvedValue([])
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={onSave} />)

    fireEvent.change(screen.getByLabelText(/Slack channel/), {
      target: { value: ' C0123456789 ' },
    })
    fireEvent.change(screen.getByLabelText(/Teams webhook/), {
      target: { value: 'https://x.webhook.office.com/y' },
    })
    fireEvent.click(screen.getByText('Save'))

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({
          approvalSlackChannel: 'C0123456789', // trimmed
          approvalTeamsWebhook: 'https://x.webhook.office.com/y',
        }),
      ),
    )
  })

  it('offers stored credentials for the bot token, never a raw token box', async () => {
    getFlowMemoryMock.mockResolvedValue({ available: true, count: 0, notes: [] })
    listCredentialsMock.mockResolvedValue([
      { id: 'cred_1', label: 'Slack bot', kind: 'token', hint: 'xoxb…', createdAt: 0, updatedAt: 0, lastUsedAt: null },
    ])
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={vi.fn()} />)

    // A dropdown of stored credentials: the flow keeps an id, the secret stays in the vault.
    expect(await screen.findByText(/Slack bot \(xoxb…\)/)).toBeInTheDocument()
  })
})

describe('SettingsModal agent memory', () => {
  beforeEach(() => {
    // The settings dialog always renders the credential picker now; these tests are not about
    // it, but its load must not explode under them.
    listCredentialsMock.mockResolvedValue([])
  })

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
