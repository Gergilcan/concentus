import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { McpDef } from '../api/types.ts'
import { McpClaudeActions } from './McpClaudeActions.tsx'

vi.mock('../api/client.ts', () => ({
  api: {
    listMcpServers: () => Promise.resolve([]),
    mcpCapabilities: () => Promise.resolve({ interactiveLogin: true, hint: '' }),
  },
}))

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
})
