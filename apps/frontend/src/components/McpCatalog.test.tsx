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

    for (const category of ['Development', 'Planning & docs', 'Business', 'Data & content', 'Google', 'Microsoft', 'Email']) {
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

  // The guided add, when the page offers one. Only entries that leave work behind go through it;
  // the rest keep the one click the catalogue is built around.
  describe('with a guided add available', () => {
    it('hands an entry that needs values to the wizard instead of half-adding it', () => {
      listMcpDefsMock.mockResolvedValue([])
      const onConfigure = vi.fn()
      render(<McpCatalog onAdded={vi.fn()} onConfigure={onConfigure} />)
      expandCatalog()
      openCategory('Google')

      fireEvent.click(screen.getByText('Google Search Console').closest('button')!)

      expect(saveMcpDefMock).not.toHaveBeenCalled()
      expect(onConfigure).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Google Search Console',
          command: 'npx',
          args: ['-y', 'mcp-server-gsc'],
          env: { GOOGLE_APPLICATION_CREDENTIALS: '' },
        }),
      )
    })

    it('hands over a local entry with no variables at all, because it still needs its runtime', () => {
      // Google Tag Manager declares no env. It is still launched by npx, and adding it on a
      // machine without node just moves the failure to the first run.
      listMcpDefsMock.mockResolvedValue([])
      const onConfigure = vi.fn()
      render(<McpCatalog onAdded={vi.fn()} onConfigure={onConfigure} />)
      expandCatalog()
      openCategory('Google')

      fireEvent.click(screen.getByText('Google Tag Manager').closest('button')!)

      expect(saveMcpDefMock).not.toHaveBeenCalled()
      expect(onConfigure).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Google Tag Manager', auth: 'stdio', command: 'npx' }),
      )
    })

    it('hands over a token server, which needs a credential picked', () => {
      listMcpDefsMock.mockResolvedValue([])
      const onConfigure = vi.fn()
      render(<McpCatalog onAdded={vi.fn()} onConfigure={onConfigure} />)
      expandCatalog()

      fireEvent.click(screen.getByText('GitLab').closest('button')!)

      expect(saveMcpDefMock).not.toHaveBeenCalled()
      expect(onConfigure).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'GitLab', auth: 'token', authHeader: 'PRIVATE-TOKEN' }),
      )
    })

    it('still adds an entry with nothing to fill in one click', async () => {
      listMcpDefsMock.mockResolvedValue([])
      saveMcpDefMock.mockResolvedValue({})
      const onConfigure = vi.fn()
      render(<McpCatalog onAdded={vi.fn()} onConfigure={onConfigure} />)
      expandCatalog()
      openCategory('Planning & docs')

      fireEvent.click(screen.getByText('Linear').closest('button')!)

      await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
      expect(onConfigure).not.toHaveBeenCalled()
    })
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

  it('offers hosted Resend with OAuth and local senders on the Email shelf', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockResolvedValue({})
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Email')

    // Resend is the one hosted entry; the senders that run locally wear the stdio pill.
    expect(screen.getByText('Resend')).toBeInTheDocument()
    expect(screen.getByText('OAuth')).toBeInTheDocument()
    expect(screen.getAllByText('runs locally')).toHaveLength(3)

    fireEvent.click(screen.getByText('Resend').closest('button')!)
    await waitFor(() =>
      expect(saveMcpDefMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Resend', url: 'https://mcp.resend.com/mcp' }),
      ),
    )
  })

  it('adds Mailchimp read-only by default — the safe direction ships pre-filled', async () => {
    listMcpDefsMock.mockResolvedValue([])
    saveMcpDefMock.mockResolvedValue({})
    render(<McpCatalog onAdded={vi.fn()} />)
    expandCatalog()
    openCategory('Email')

    fireEvent.click(screen.getByText('Mailchimp').closest('button')!)

    await waitFor(() =>
      expect(saveMcpDefMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Mailchimp',
          command: 'uvx',
          args: ['mailchimp-mcp'],
          // The key is empty (the user's to fill); the read-only flag arrives already ON.
          env: { MAILCHIMP_API_KEY: '', MAILCHIMP_READ_ONLY: 'true' },
        }),
      ),
    )
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
