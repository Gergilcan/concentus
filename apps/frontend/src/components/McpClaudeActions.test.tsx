import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { McpDef, McpServerInfo } from '../api/types.ts'
import { api } from '../api/client.ts'
import { McpClaudeActions } from './McpClaudeActions.tsx'

const listed: McpServerInfo[] = []

vi.mock('../api/client.ts', () => ({
  api: {
    listMcpServers: vi.fn(),
    mcpCapabilities: () => Promise.resolve({ interactiveLogin: true, hint: '' }),
    addMcpServer: vi.fn(),
    loginMcpServer: vi.fn(),
    removeMcpServer: vi.fn(),
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.listMcpServers).mockResolvedValue(listed.slice())
  vi.mocked(api.loginMcpServer).mockResolvedValue({ name: 'x', status: 'A terminal window opened' })
})

describe('McpClaudeActions', () => {
  it('renders a stdio definition, whose url the backend stores as null', async () => {
    // The regression: `url.trim()` on that null threw, and took the whole MCP panel down with it —
    // opening the Google Ads server, added from the catalogue, was enough to hit it.
    const stdio = { id: 'mcp_1', name: 'Google Ads', url: null, command: 'pipx' } as unknown as McpDef

    render(<McpClaudeActions name={stdio.name} url={stdio.url} credentialId={stdio.credentialId} />)

    expect(await screen.findByText(/nothing to register with the CLI/)).toBeInTheDocument()
    // No dead buttons next to that explanation — only the one that re-reads the CLI's list.
    expect(screen.queryByText('Add & authorize')).not.toBeInTheDocument()
    expect(screen.getByText('Recheck')).toBeInTheDocument()
  })

  it('offers the CLI registration for a server that has a URL', async () => {
    render(<McpClaudeActions name="Linear" url="https://mcp.linear.app/mcp" />)

    expect(await screen.findByText('Add & authorize')).toBeEnabled()
    expect(screen.queryByText(/nothing to register/)).not.toBeInTheDocument()
  })

  // The CLI's list is a registry of identifiers and refuses spaces, so a block named with them is
  // registered under a mapped name. Matching on the label alone reported a server sitting right
  // there as absent.
  it('recognises its server by URL when the CLI holds it under a mapped name', async () => {
    vi.mocked(api.listMcpServers).mockResolvedValue([
      { name: 'Ryze_Google_Ads', url: 'https://connector.get-ryze.ai/mcp', status: '! Needs authentication' },
    ])

    render(<McpClaudeActions name="Ryze Google Ads" url="https://connector.get-ryze.ai/mcp" />)

    expect(await screen.findByText('In Claude Code — needs authorization')).toBeInTheDocument()
    expect(screen.queryByText('Not yet in Claude Code')).not.toBeInTheDocument()
    // Already there: the button authorizes, it does not add again.
    expect(screen.getByText('Authorize (OAuth)')).toBeInTheDocument()
  })

  it('signs in under the name the CLI accepted, not the block label', async () => {
    vi.mocked(api.addMcpServer).mockResolvedValue({
      name: 'Ryze_Google_Ads',
      status: 'added as "Ryze_Google_Ads" — run `claude mcp login "Ryze_Google_Ads"` to authorize',
    })

    render(<McpClaudeActions name="Ryze Google Ads" url="https://connector.get-ryze.ai/mcp" />)
    fireEvent.click(await screen.findByText('Add & authorize'))

    await waitFor(() => expect(api.loginMcpServer).toHaveBeenCalledWith('Ryze_Google_Ads'))
  })

  // Three presses of this button said nothing about "Invalid name": the refusal was overwritten by
  // the status of a sign-in that should never have been started.
  it('stops at a refused registration and shows why, instead of signing in', async () => {
    vi.mocked(api.addMcpServer).mockResolvedValue({
      name: 'Ryze_Google_Ads',
      status: 'add failed: Invalid name. Names can only contain letters, numbers, hyphens, and underscores.',
    })

    render(<McpClaudeActions name="Ryze Google Ads" url="https://connector.get-ryze.ai/mcp" />)
    fireEvent.click(await screen.findByText('Add & authorize'))

    expect(await screen.findByText(/Invalid name/)).toBeInTheDocument()
    expect(api.loginMcpServer).not.toHaveBeenCalled()
  })
})
