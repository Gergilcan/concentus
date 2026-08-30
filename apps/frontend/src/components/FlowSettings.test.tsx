import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SettingsModal } from './FlowModals.tsx'
import type { BackendFlow } from '../api/types.ts'

const listVariables = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listVariables: () => listVariables(),
    listCredentials: () => Promise.resolve([]),
    getFlowMemory: () => Promise.resolve({ notes: [] }),
    clearFlowMemory: vi.fn(),
  },
}))

const FLOW: BackendFlow = {
  id: 'flow_1',
  name: 'Daily briefing',
  nodes: [],
  edges: [],
}

/**
 * The flow's own settings.
 *
 * <p>Every field here is one somebody types into once and then forgets, which is exactly the kind
 * that breaks without anybody noticing for weeks — so what these hold still is that typing works
 * and that what was typed is what gets saved.
 */
describe('SettingsModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listVariables.mockResolvedValue([
      { name: 'COMPANY', value: 'Tecnovent', description: 'The trading name' },
    ])
  })

  const open = async (flow: BackendFlow = FLOW) => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<SettingsModal flow={flow} onClose={vi.fn()} onSave={onSave} />)
    await screen.findByText(/Variables/)
    return onSave
  }

  it('takes a failure webhook and saves what was typed', async () => {
    const onSave = await open()

    fireEvent.change(screen.getByLabelText(/Failure notification webhook/i), {
      target: { value: 'https://hooks.example.com/abc' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({ notifyWebhook: 'https://hooks.example.com/abc' }),
      ),
    )
  })

  it('overrides an organization variable for this flow', async () => {
    const onSave = await open()

    fireEvent.change(screen.getByLabelText(/COMPANY/), { target: { value: 'Tecnovent Ibérica' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({ variables: { COMPANY: 'Tecnovent Ibérica' } }),
      ),
    )
  })

  // The row has to survive being emptied. Deleting the key on a blank value took the field off
  // the screen mid-edit, which is what "it will not let me edit them" looks like from outside.
  it('keeps a variable row on screen while it is being retyped', async () => {
    await open({ ...FLOW, variables: { REGION: 'north' } })

    const field = screen.getByLabelText(/REGION/)
    fireEvent.change(field, { target: { value: '' } })

    expect(screen.getByLabelText(/REGION/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/REGION/), { target: { value: 's' } })
    expect(screen.getByLabelText(/REGION/)).toHaveValue('s')
  })

  // Slack answers; Teams only tells. Either is useful on its own, and the screen has to say so —
  // asking for both reads as "this does not work until you have both".
  it('accepts Slack alone, Teams alone, or both', async () => {
    const onSave = await open()

    fireEvent.change(screen.getByLabelText(/Teams webhook/i), {
      target: { value: 'https://tecnovent.webhook.office.com/x' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({
          approvalTeamsWebhook: 'https://tecnovent.webhook.office.com/x',
          approvalSlackChannel: '',
        }),
      ),
    )
  })

  // Folders are gone from here: a flow is filed by dragging its card, which is where somebody can
  // see what folders there are.
  it('does not ask which folder the flow goes in', async () => {
    await open()

    expect(screen.queryByText(/Folder/i)).not.toBeInTheDocument()
  })
})
