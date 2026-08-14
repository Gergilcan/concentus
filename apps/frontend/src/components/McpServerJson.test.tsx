import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { McpDef } from '../api/types.ts'
import { McpServerJson } from './McpServerJson.tsx'

const saveMcpDefMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: { saveMcpDef: (d: unknown) => saveMcpDefMock(d) },
}))

const ads: McpDef = {
  id: 'mcp_1',
  name: 'Google Ads',
  url: '',
  credentialId: '',
  command: 'pipx',
  args: ['run', 'google-ads-mcp'],
  env: {},
}

const box = () => screen.getByLabelText('This server as JSON') as HTMLTextAreaElement

afterEach(() => {
  vi.clearAllMocks()
})

describe('McpServerJson', () => {
  it('shows the selected server and nothing else', () => {
    render(<McpServerJson def={ads} onApplied={vi.fn()} />)

    const text = box().value
    expect(JSON.parse(text)).toEqual({ command: 'pipx', args: ['run', 'google-ads-mcp'], env: {} })
    // Not the registry: no other server, and no id to accidentally edit.
    expect(text).not.toContain('mcpServers')
    expect(text).not.toContain('mcp_1')
  })

  it('saves this one definition and hands the saved record back', async () => {
    const saved = { ...ads, env: { DEVELOPER_TOKEN: 'credential:cred_1' } }
    saveMcpDefMock.mockResolvedValue(saved)
    const onApplied = vi.fn()
    render(<McpServerJson def={ads} onApplied={onApplied} />)

    fireEvent.change(box(), {
      target: { value: '{"command":"pipx","args":["run","google-ads-mcp"],"env":{"DEVELOPER_TOKEN":"credential:cred_1"}}' },
    })
    fireEvent.click(screen.getByText('Save JSON'))

    await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
    // The id travels with it: this updates the server, it does not add a second one.
    expect(saveMcpDefMock.mock.calls[0][0]).toMatchObject({
      id: 'mcp_1',
      name: 'Google Ads',
      env: { DEVELOPER_TOKEN: 'credential:cred_1' },
    })
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(saved))
  })

  it('follows the list while untouched, and stops the moment it is edited', () => {
    const { rerender } = render(<McpServerJson def={ads} onApplied={vi.fn()} />)

    rerender(<McpServerJson def={{ ...ads, id: 'mcp_2', name: 'Other', command: 'npx', args: ['-y', 'other'] }} onApplied={vi.fn()} />)
    expect(box().value).toContain('npx')

    fireEvent.change(box(), { target: { value: '{"command":"uvx","args":[]}' } })
    // A keystroke in the Name field above re-renders this with a new object; what is typed here
    // must survive that.
    rerender(<McpServerJson def={{ ...ads, id: 'mcp_2', name: 'Other!', command: 'npx', args: ['-y', 'other'] }} onApplied={vi.fn()} />)
    expect(box().value).toContain('uvx')
  })

  it('drops the last server’s message when another is picked', async () => {
    saveMcpDefMock.mockResolvedValue(ads)
    const { rerender } = render(<McpServerJson def={ads} onApplied={vi.fn()} />)

    fireEvent.click(screen.getByText('Save JSON'))
    expect(await screen.findByText('Saved')).toBeInTheDocument()

    rerender(
      <McpServerJson
        def={{ ...ads, id: 'mcp_2', name: 'Linear', url: 'https://x', command: '' }}
        onApplied={vi.fn()}
      />,
    )

    // "Saved" left hanging over a different server is a lie about that server.
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()
    expect(box().value).toContain('https://x')
  })

  it('reports a bad paste without touching the server', async () => {
    render(<McpServerJson def={ads} onApplied={vi.fn()} />)

    fireEvent.change(box(), { target: { value: '{not json' } })
    fireEvent.click(screen.getByText('Save JSON'))

    expect(await screen.findByText(/Not valid JSON/)).toBeInTheDocument()
    expect(saveMcpDefMock).not.toHaveBeenCalled()
  })

  it('reverts to what is stored, once there is something to revert', () => {
    render(<McpServerJson def={ads} onApplied={vi.fn()} />)

    expect(screen.getByText('Revert')).toBeDisabled()
    fireEvent.change(box(), { target: { value: '{}' } })
    fireEvent.click(screen.getByText('Revert'))

    expect(box().value).toContain('google-ads-mcp')
  })
})
