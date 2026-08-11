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

/** The catalog opens collapsed; almost every test starts by expanding it like a user would. */
function expandCatalog() {
  fireEvent.click(screen.getByText(/Catalog — one click to add/))
}

/** Entries live one category at a time now; reaching one means picking its tab first. */
function openCategory(category: string) {
  fireEvent.click(screen.getByRole('tab', { name: category }))
}

describe('McpCatalog', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('starts collapsed: one row, no wall of cards', () => {
    listMcpDefsMock.mockResolvedValue([])
    render(<McpCatalog onAdded={vi.fn()} />)

    expect(screen.getByText(/Catalog — one click to add/)).toBeInTheDocument()
    expect(screen.queryByText('GitHub')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it('expanding shows the categories as tabs, one category at a time', () => {
    listMcpDefsMock.mockResolvedValue([])
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()

    for (const category of ['Development', 'Planning & docs', 'Business', 'Data & content', 'Google', 'Microsoft']) {
      expect(screen.getByRole('tab', { name: category })).toBeInTheDocument()
    }
    // The first category is the open one; the others' entries stay off screen until picked.
    expect(screen.getByText('GitHub')).toBeInTheDocument()
    expect(screen.getByText('Issues, PRs, repositories')).toBeInTheDocument()
    expect(screen.queryByText('Linear')).not.toBeInTheDocument()

    openCategory('Planning & docs')
    expect(screen.getByText('Linear')).toBeInTheDocument()
    expect(screen.queryByText('GitHub')).not.toBeInTheDocument()
  })

  it('marks servers that are already in the user’s list and disables re-adding them', async () => {
    // Case-insensitive: the list stores whatever the user typed.
    listMcpDefsMock.mockResolvedValue([{ id: '1', name: 'linear', url: 'x', credentialId: '' }])
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Planning & docs')

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
    expandCatalog()
    openCategory('Planning & docs')

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
    expandCatalog()

    fireEvent.click(screen.getByText('GitLab').closest('button')!)

    await waitFor(() =>
      expect(saveMcpDefMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'GitLab', authHeader: 'PRIVATE-TOKEN' }),
      ),
    )
    expect(screen.getByText(/Resources → Credentials/)).toBeInTheDocument()
  })

  it('adds a stdio entry with its command, args and empty env — and says what to fill', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockResolvedValue({})
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Google')

    fireEvent.click(screen.getByText('Google Search Console').closest('button')!)

    await waitFor(() =>
      expect(saveMcpDefMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Google Search Console',
          url: '',
          command: 'npx',
          args: ['-y', 'mcp-server-gsc'],
          // Pre-created empty so the definition itself shows what must be filled.
          env: { GOOGLE_APPLICATION_CREDENTIALS: '' },
        }),
      ),
    )
    // A local server's next step is env + having the command installed, and the note says so.
    expect(screen.getByText(/runs on this machine/)).toBeInTheDocument()
    expect(screen.getByText(/"npx" is installed/)).toBeInTheDocument()
  })

  it('offers Microsoft Graph locally and the hosted Learn docs without sign-in', () => {
    listMcpDefsMock.mockResolvedValue([])
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Microsoft')

    expect(screen.getByText('Microsoft Graph')).toBeInTheDocument()
    expect(screen.getByText('runs locally')).toBeInTheDocument()
    expect(screen.getByText('Microsoft Learn Docs')).toBeInTheDocument()
    expect(screen.getByText('no auth')).toBeInTheDocument()
  })

  it('surfaces the failure when adding does not work', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockRejectedValue(new Error('storage is unavailable'))
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Business')

    fireEvent.click(screen.getByText('Stripe').closest('button')!)

    expect(await screen.findByText('storage is unavailable')).toBeInTheDocument()
    // A failed add must not pretend: the card stays actionable.
    expect(screen.getByText('Stripe').closest('button')).not.toBeDisabled()
  })
})
