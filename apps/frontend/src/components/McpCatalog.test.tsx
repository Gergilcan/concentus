import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { McpCatalog } from './McpCatalog.tsx'

const listMcpDefsMock = vi.fn()
const saveMcpDefMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listMcpDefs: () => listMcpDefsMock(),
    saveMcpDef: (d: unknown) => saveMcpDefMock(d),
  },
}))

describe('McpCatalog', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('groups the entries under their category headers', async () => {
    listMcpDefsMock.mockResolvedValue([])
    render(<McpCatalog onAdded={vi.fn()} />)

    for (const category of ['Development', 'Planning & docs', 'Business', 'Data & content']) {
      expect(screen.getByText(category)).toBeInTheDocument()
    }
    expect(screen.getByText('GitHub')).toBeInTheDocument()
    // What the server does is visible on the card, not buried in a tooltip.
    expect(screen.getByText('Issues, PRs, repositories')).toBeInTheDocument()
  })

  it('marks servers that are already in the user’s list and disables re-adding them', async () => {
    // Case-insensitive: the list stores whatever the user typed.
    listMcpDefsMock.mockResolvedValue([{ id: '1', name: 'linear', url: 'x', credentialId: '' }])
    render(<McpCatalog onAdded={vi.fn()} />)

    expect(await screen.findByText('✓ added')).toBeInTheDocument()
    const card = screen.getByText('Linear').closest('button')!
    expect(card).toBeDisabled()
    expect(saveMcpDefMock).not.toHaveBeenCalled()
  })

  it('adds an entry, flips its card to added, and says what to do next', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockResolvedValue({})
    const onAdded = vi.fn()
    render(<McpCatalog onAdded={onAdded} />)

    fireEvent.click(screen.getByText('Linear').closest('button')!)

    await waitFor(() => expect(onAdded).toHaveBeenCalled())
    expect(saveMcpDefMock).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Linear', url: 'https://mcp.linear.app/mcp' }),
    )
    // An OAuth server's next step is signing in from the node.
    expect(screen.getByText(/Sign in to this server/)).toBeInTheDocument()
    expect(screen.getByText('✓ added')).toBeInTheDocument()
    expect(screen.getByText('Linear').closest('button')).toBeDisabled()
  })

  it('sends GitLab’s token in the PRIVATE-TOKEN header and asks for a credential next', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockResolvedValue({})
    render(<McpCatalog onAdded={vi.fn()} />)

    fireEvent.click(screen.getByText('GitLab').closest('button')!)

    await waitFor(() =>
      expect(saveMcpDefMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'GitLab', authHeader: 'PRIVATE-TOKEN' }),
      ),
    )
    expect(screen.getByText(/Resources → Credentials/)).toBeInTheDocument()
  })

  it('surfaces the failure when adding does not work', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockRejectedValue(new Error('storage is unavailable'))
    render(<McpCatalog onAdded={vi.fn()} />)

    fireEvent.click(screen.getByText('Stripe').closest('button')!)

    expect(await screen.findByText('storage is unavailable')).toBeInTheDocument()
    // A failed add must not pretend: the card stays actionable.
    expect(screen.getByText('Stripe').closest('button')).not.toBeDisabled()
  })
})
