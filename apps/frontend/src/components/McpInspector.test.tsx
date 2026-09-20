import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { McpNodeData } from '../api/types.ts'
import { api } from '../api/client.ts'
import { McpInspector } from './McpInspector.tsx'

const statusMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listMcpServers: vi.fn(() => Promise.resolve([])),
    listMcpDefs: () => Promise.resolve([]),
    listModels: () => Promise.resolve({}),
    mcpCapabilities: () => Promise.resolve({ interactiveLogin: true, hint: '' }),
    mcpOAuthStatus: () => statusMock(),
    listCredentials: () => Promise.resolve([]),
  },
}))

const data = {
  name: 'holded',
  url: 'https://mcp.holded.com/mcp',
  credentialId: '',
  authHeader: '',
  tools: [],
} as unknown as McpNodeData

// Once a server is signed in with OAuth, the token fields are dead weight — and a leftover
// token used to shadow the grant, turning "Choose tools" and every run into a 401.
describe('McpInspector token fields vs OAuth', () => {
  it('shows the token fields while the server has no grant', async () => {
    statusMock.mockResolvedValue({ connected: false })
    render(<McpInspector data={data} set={vi.fn()} />)

    expect(await screen.findByTitle(/Approve once in the tab that opens/)).toBeInTheDocument()
    expect(screen.getByText('Access token (optional)')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.getByText(/Send token in/)).toBeInTheDocument()
  })

  it('hides every token field once the server is signed in with OAuth', async () => {
    statusMock.mockResolvedValue({ connected: true })
    render(<McpInspector data={data} set={vi.fn()} />)

    expect(await screen.findByText(/Signed in with OAuth/)).toBeInTheDocument()
    // The status lands in the child a commit before it reaches the parent that hides the field —
    // waited on, not asserted immediately, or this races React's second render.
    await waitFor(() => expect(screen.queryByText('Access token (optional)')).not.toBeInTheDocument())
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.queryByText(/Send token in/)).not.toBeInTheDocument()
    // The tool picker stays — it reads the server through the grant now.
    expect(screen.getByText('Choose tools…')).toBeInTheDocument()
  })
})

// The CLI spells a space as an underscore because `claude mcp add` refuses spaces. That is the
// CLI's problem, not something to read on a canvas.
describe('McpInspector: the CLI list, read as names', () => {
  it('shows a registered server with its spaces back, and gives the block that name', async () => {
    statusMock.mockResolvedValue({ connected: false })
    vi.mocked(api.listMcpServers).mockResolvedValue([
      { name: 'Ryze_Google_Ads', url: 'https://connector.get-ryze.ai/mcp', status: '✔ Connected' },
    ])
    const set = vi.fn()

    render(<McpInspector data={data} set={set} />)

    const option = await screen.findByRole('option', { name: 'Ryze Google Ads' })
    expect(option).toBeInTheDocument()
    // The value stays the CLI's own spelling: that is what the list is keyed by.
    expect(option).toHaveValue('Ryze_Google_Ads')

    fireEvent.change(screen.getByLabelText('Select existing (from Claude Code)'), {
      target: { value: 'Ryze_Google_Ads' },
    })
    expect(set).toHaveBeenCalledWith({
      name: 'Ryze Google Ads',
      url: 'https://connector.get-ryze.ai/mcp',
    })
  })
})
