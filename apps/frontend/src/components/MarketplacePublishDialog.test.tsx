import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mktItem } from '../test/marketplace.ts'
import { MarketplacePublishDialog } from './MarketplacePublishDialog.tsx'

const statusMock = vi.fn()
const listMcpDefsMock = vi.fn()
const listAgentsMock = vi.fn()
const listFlowsMock = vi.fn()
const publishFromMock = vi.fn()
const publishItemMock = vi.fn()
const updateItemMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    marketplaceStatus: () => statusMock(),
    listMcpDefs: () => listMcpDefsMock(),
    listAgents: () => listAgentsMock(),
    listFlows: () => listFlowsMock(),
    publishMarketplaceFrom: (body: unknown) => publishFromMock(body),
    publishMarketplaceItem: (body: unknown) => publishItemMock(body),
    updateMarketplaceItem: (id: string, body: unknown) => updateItemMock(id, body),
  },
}))

function renderDialog(props: Partial<Parameters<typeof MarketplacePublishDialog>[0]> = {}) {
  const onPublished = vi.fn()
  const pushError = vi.fn()
  render(<MarketplacePublishDialog onClose={vi.fn()} onPublished={onPublished} pushError={pushError} {...props} />)
  return { onPublished, pushError }
}

const fillWords = () => {
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Tech Lead' } })
  fireEvent.change(screen.getByLabelText('Summary (one line)'), { target: { value: 'Plans and delegates' } })
}

describe('MarketplacePublishDialog', () => {
  beforeEach(() => {
    statusMock.mockResolvedValue({ curator: false, pending: 0, organizations: 1, tags: [] })
    listMcpDefsMock.mockResolvedValue([{ id: 'mcp_1', name: 'linear', url: 'https://x', credentialId: '' }])
    listAgentsMock.mockResolvedValue([
      { id: 'agent_1', name: 'Tech Lead', model: 'm', effort: 'high', maxTokens: 1, systemPrompt: 'x', description: 'Plans and delegates' },
    ])
    listFlowsMock.mockResolvedValue([{ id: 'flow_1', name: 'Mail triage', mode: 'local', nodes: [], edges: [] }])
  })
  afterEach(() => vi.clearAllMocks())

  it('lists the resources of the chosen kind, lends the picked one’s words, and publishes from it', async () => {
    publishFromMock.mockResolvedValue({ ...mktItem({ id: 'mkt_new' }), stripped: [] })
    const { onPublished } = renderDialog()

    // MCP first, then the kind changes and the list follows.
    expect(await screen.findByRole('option', { name: 'linear' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } })
    expect(await screen.findByRole('option', { name: 'Tech Lead' })).toBeInTheDocument()
    expect(listAgentsMock).toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Resource'), { target: { value: 'agent_1' } })
    expect(screen.getByLabelText('Name')).toHaveValue('Tech Lead')
    expect(screen.getByLabelText('Summary (one line)')).toHaveValue('Plans and delegates')

    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    await waitFor(() =>
      expect(publishFromMock).toHaveBeenCalledWith(
        expect.objectContaining({ kind: 'agent', resourceId: 'agent_1', name: 'Tech Lead', scope: 'global' }),
      ),
    )
    expect(onPublished).toHaveBeenCalledWith(expect.objectContaining({ id: 'mkt_new' }), [])
  })

  it('refuses to publish without a resource, a name or a summary', async () => {
    renderDialog()
    await screen.findByRole('option', { name: 'linear' })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(screen.getByText('A name and a one-line summary are required.')).toBeInTheDocument()
    fillWords()
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(screen.getByText('Pick the resource to publish.')).toBeInTheDocument()
    expect(publishFromMock).not.toHaveBeenCalled()
  })

  it('Paste JSON posts the payload; a bad payload is refused here and nothing is sent', async () => {
    publishItemMock.mockResolvedValue(mktItem({ id: 'mkt_json' }))
    const { onPublished } = renderDialog()
    fireEvent.click(screen.getByLabelText('Paste JSON'))
    const payload = screen.getByLabelText('Payload (JSON)')
    fillWords()

    fireEvent.change(payload, { target: { value: '{not json' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText('The payload is not valid JSON.')).toBeInTheDocument()
    fireEvent.change(payload, { target: { value: '[1, 2]' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText('The payload must be a JSON object.')).toBeInTheDocument()
    expect(publishItemMock).not.toHaveBeenCalled()

    fireEvent.change(payload, { target: { value: '{"name": "linear", "url": "https://x"}' } })
    fireEvent.change(screen.getByLabelText('Tags (comma-separated)'), { target: { value: ' a ,b,, ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    await waitFor(() =>
      expect(publishItemMock).toHaveBeenCalledWith(
        expect.objectContaining({ kind: 'mcp', payload: { name: 'linear', url: 'https://x' }, tags: ['a', 'b'] }),
      ),
    )
    expect(onPublished).toHaveBeenCalledWith(expect.objectContaining({ id: 'mkt_json' }), [])
  })

  it('an API item is JSON only: there is no resource to publish it from', () => {
    renderDialog()
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'api' } })
    expect(screen.queryByLabelText('Paste JSON')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Payload (JSON)')).toBeInTheDocument()
  })

  it('shows the scope only past one organization, with the sentence about review', async () => {
    statusMock.mockResolvedValue({ curator: false, pending: 0, organizations: 3, tags: [] })
    publishFromMock.mockResolvedValue({ ...mktItem(), stripped: ['credentialId'] })
    const { onPublished } = renderDialog()

    const scope = await screen.findByLabelText('Scope')
    expect(scope).toHaveValue('organization')
    expect(screen.getByText('Global items are reviewed before everyone sees them')).toBeInTheDocument()

    await screen.findByRole('option', { name: 'linear' })
    fireEvent.change(screen.getByLabelText('Resource'), { target: { value: 'mcp_1' } })
    fireEvent.change(screen.getByLabelText('Summary (one line)'), { target: { value: 'Issues' } })
    fireEvent.change(scope, { target: { value: 'global' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(publishFromMock).toHaveBeenCalledWith(expect.objectContaining({ scope: 'global' })))
    // What the server left behind travels to whoever shows the notice.
    expect(onPublished).toHaveBeenCalledWith(expect.anything(), ['credentialId'])
  })

  it('prefilled from a resource: the kind and the resource are fixed and the name is given', async () => {
    publishFromMock.mockResolvedValue({ ...mktItem({ kind: 'flow' }), stripped: [] })
    renderDialog({ prefill: { kind: 'flow', resourceId: 'flow_1', name: 'Mail triage' } })

    expect(screen.getByLabelText('Kind')).toHaveValue('flow')
    expect(screen.getByLabelText('Kind')).toBeDisabled()
    expect(await screen.findByRole('option', { name: 'Mail triage' })).toBeInTheDocument()
    expect(screen.getByLabelText('Resource')).toHaveValue('flow_1')
    expect(screen.getByLabelText('Resource')).toBeDisabled()
    expect(screen.getByLabelText('Name')).toHaveValue('Mail triage')

    fireEvent.change(screen.getByLabelText('Summary (one line)'), { target: { value: 'Sorts the inbox' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    await waitFor(() =>
      expect(publishFromMock).toHaveBeenCalledWith(expect.objectContaining({ kind: 'flow', resourceId: 'flow_1', name: 'Mail triage' })),
    )
  })

  it('editing sends PUT with the item’s id, its words and the payload from the box', async () => {
    const item = mktItem({ id: 'mkt_e', name: 'Linear', tags: ['planning'], icon: '⚙', description: 'Old text' })
    updateItemMock.mockResolvedValue({ ...item, version: 2 })
    const { onPublished } = renderDialog({ editing: item })

    expect(screen.getByRole('dialog', { name: 'Edit Linear' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Paste JSON')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Payload (JSON)')).toHaveValue(JSON.stringify(item.payload, null, 2))
    expect(screen.getByLabelText('Tags (comma-separated)')).toHaveValue('planning')

    fireEvent.change(screen.getByLabelText('Summary (one line)'), { target: { value: 'Issues, cycles, projects' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(updateItemMock).toHaveBeenCalledWith(
        'mkt_e',
        expect.objectContaining({ name: 'Linear', summary: 'Issues, cycles, projects', description: 'Old text', icon: '⚙', payload: item.payload }),
      ),
    )
    expect(onPublished).toHaveBeenCalledWith(expect.objectContaining({ version: 2 }), [])
  })

  it('a refusal from the server goes to the toast, a typo stays in the form', async () => {
    const refusal = Object.assign(new Error('Your role (viewer) cannot publish.'), { status: 403 })
    publishFromMock.mockRejectedValue(refusal)
    const { pushError } = renderDialog()
    await screen.findByRole('option', { name: 'linear' })
    fireEvent.change(screen.getByLabelText('Resource'), { target: { value: 'mcp_1' } })
    fireEvent.change(screen.getByLabelText('Summary (one line)'), { target: { value: 'Issues' } })
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(pushError).toHaveBeenCalledWith('Your role (viewer) cannot publish.'))
    expect(screen.getByRole('button', { name: 'Publish' })).toBeEnabled()
  })
})
