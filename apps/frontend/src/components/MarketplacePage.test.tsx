import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MarketplaceList } from '../api/types.ts'
import { mktItem } from '../test/marketplace.ts'
import { MarketplacePage } from './MarketplacePage.tsx'

const listMock = vi.fn()
const installMock = vi.fn()
const rejectMock = vi.fn()
const approveMock = vi.fn()
const publishFromMock = vi.fn()
const statusMock = vi.fn()
const listMcpDefsMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listMarketplaceItems: (...args: unknown[]) => listMock(...args),
    installMarketplaceItem: (id: string) => installMock(id),
    rejectMarketplaceItem: (id: string, reason: string) => rejectMock(id, reason),
    approveMarketplaceItem: (id: string) => approveMock(id),
    publishMarketplaceFrom: (body: unknown) => publishFromMock(body),
    marketplaceStatus: () => statusMock(),
    listMcpDefs: () => listMcpDefsMock(),
  },
}))

const linear = mktItem({
  id: 'mkt_linear',
  name: 'Linear',
  summary: 'Issues and cycles',
  tags: ['planning', 'mcp'],
  installs: 142,
  publishedAt: 100,
})
const techLead = mktItem({
  id: 'mkt_lead',
  kind: 'agent',
  name: 'Tech Lead',
  summary: 'Plans and delegates',
  scope: 'organization',
  tags: ['review'],
  installs: 3,
  publishedAt: 300,
  installed: { resourceId: 'agent_1', version: 1, installedAt: 1 },
  mine: true,
})
const reviewer = mktItem({
  id: 'mkt_rev',
  kind: 'facade',
  name: 'Reviewer',
  summary: 'Read-only facade',
  version: 2,
  installs: 20,
  publishedAt: 200,
  installed: { resourceId: 'facade_1', version: 1, installedAt: 1 },
})
const prReview = mktItem({
  id: 'mkt_pr',
  kind: 'flow',
  name: 'PR review',
  summary: 'A template',
  status: 'pending',
  publishedAt: null,
  createdAt: 400,
  mine: true,
  canEdit: true,
})
const rejected = mktItem({
  id: 'mkt_old',
  name: 'Old thing',
  summary: 'Refused',
  status: 'rejected',
  rejection: 'Too broad',
  publishedAt: null,
  createdAt: 50,
})

const list = (over: Partial<MarketplaceList> = {}): MarketplaceList => ({
  items: [linear, techLead, reviewer, prReview, rejected],
  tags: ['mcp', 'planning', 'review'],
  curator: false,
  pending: 0,
  ...over,
})

/** The cards in grid order, by name — the name is the first titled thing on a card. */
const cardNames = () =>
  screen.getAllByTestId('marketplace-card').map((c) => c.querySelector('[title]')?.getAttribute('title'))

function renderPage() {
  const onOpenResources = vi.fn()
  const onOpenFlow = vi.fn()
  const pushError = vi.fn()
  render(<MarketplacePage pushError={pushError} onOpenResources={onOpenResources} onOpenFlow={onOpenFlow} />)
  return { onOpenResources, onOpenFlow, pushError }
}

// The server decides what the list holds; everything the page does with it is local and instant.
describe('MarketplacePage', () => {
  beforeEach(() => {
    listMock.mockResolvedValue(list())
    statusMock.mockResolvedValue({ curator: false, pending: 0, organizations: 1, tags: [] })
  })
  afterEach(() => vi.clearAllMocks())

  it('renders a card per item with its chips, most installed first', async () => {
    renderPage()
    expect(await screen.findByText('Linear')).toBeInTheDocument()
    expect(screen.getAllByTestId('marketplace-card')).toHaveLength(5)
    expect(screen.getByText('142 installs')).toBeInTheDocument()
    expect(screen.getByText('Installed ✓')).toBeInTheDocument()
    expect(screen.getByText('Update ↑')).toBeInTheDocument()
    expect(screen.getByText('Pending ⏳')).toBeInTheDocument()
    // The rejected chip carries the curator's sentence where a hover finds it.
    expect(screen.getByTitle('Too broad')).toHaveTextContent('Rejected')
    expect(cardNames()).toEqual(['Linear', 'Reviewer', 'Tech Lead', 'Old thing', 'PR review'])
  })

  it('searches name, summary and tags locally, without asking the server again', async () => {
    renderPage()
    await screen.findByText('Linear')
    const search = screen.getByLabelText('Search the Marketplace')

    fireEvent.change(search, { target: { value: 'planning' } })
    expect(cardNames()).toEqual(['Linear'])
    fireEvent.change(search, { target: { value: 'delegates' } })
    expect(cardNames()).toEqual(['Tech Lead'])
    fireEvent.change(search, { target: { value: 'zzz' } })
    expect(screen.getByText('Nothing matches those filters.')).toBeInTheDocument()
    expect(listMock).toHaveBeenCalledTimes(1)
  })

  it('filters by kind, scope and state, and sorts by name or by newest', async () => {
    renderPage()
    await screen.findByText('Linear')

    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } })
    expect(cardNames()).toEqual(['Tech Lead'])
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: '' } })

    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'organization' } })
    expect(cardNames()).toEqual(['Tech Lead'])
    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: '' } })

    fireEvent.change(screen.getByLabelText('State'), { target: { value: 'update' } })
    expect(cardNames()).toEqual(['Reviewer'])
    fireEvent.change(screen.getByLabelText('State'), { target: { value: 'mine' } })
    expect(cardNames()).toEqual(['Tech Lead', 'PR review'])
    fireEvent.change(screen.getByLabelText('State'), { target: { value: '' } })

    fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'name' } })
    expect(cardNames()).toEqual(['Linear', 'Old thing', 'PR review', 'Reviewer', 'Tech Lead'])
    fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'newest' } })
    expect(cardNames()).toEqual(['PR review', 'Tech Lead', 'Reviewer', 'Linear', 'Old thing'])
  })

  it('tag chips narrow to the items carrying every selected tag', async () => {
    renderPage()
    await screen.findByText('Linear')
    const tagRow = screen.getByLabelText('Tags')

    fireEvent.click(within(tagRow).getByRole('button', { name: 'planning' }))
    expect(cardNames()).toEqual(['Linear'])
    fireEvent.click(within(tagRow).getByRole('button', { name: 'review' }))
    expect(screen.getByText('Nothing matches those filters.')).toBeInTheDocument()
    fireEvent.click(within(tagRow).getByRole('button', { name: 'planning' }))
    expect(cardNames()).toEqual(['Tech Lead'])
  })

  it('a curator sees the Pending badge, and it narrows the grid to what waits', async () => {
    listMock.mockResolvedValue(list({ curator: true, pending: 1 }))
    renderPage()
    const badge = await screen.findByRole('button', { name: /1 pending/ })
    fireEvent.click(badge)
    expect(cardNames()).toEqual(['PR review'])
    expect(badge).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows no badge to somebody who does not curate, whatever is pending', async () => {
    listMock.mockResolvedValue(list({ curator: false, pending: 2 }))
    renderPage()
    await screen.findByText('Linear')
    expect(screen.queryByText(/2 pending/)).not.toBeInTheDocument()
  })

  it('opening a card shows the item; Install calls the endpoint and links to Resources', async () => {
    installMock.mockResolvedValue({ resourceId: 'mcp_9', kind: 'mcp', version: 1 })
    const { onOpenResources } = renderPage()
    fireEvent.click(await screen.findByText('Linear'))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/ana@example.com/)).toBeInTheDocument()
    expect(within(dialog).getByText('https://mcp.linear.app/mcp')).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(installMock).toHaveBeenCalledWith('mkt_linear'))
    // The list is read again so the card's chip catches up.
    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2))
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Open in Resources' }))
    expect(onOpenResources).toHaveBeenCalledWith('mcp')
  })

  it('rejecting needs a reason before the endpoint is called', async () => {
    listMock.mockResolvedValue(list({ curator: true, pending: 1, items: [{ ...prReview, canCurate: true }] }))
    rejectMock.mockResolvedValue(undefined)
    renderPage()
    fireEvent.click(await screen.findByText('PR review'))

    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reject…' }))
    const confirm = within(dialog).getByRole('button', { name: 'Confirm rejection' })
    expect(confirm).toBeDisabled()
    const reason = within(dialog).getByLabelText('Reason for rejecting')
    fireEvent.change(reason, { target: { value: '   ' } })
    expect(confirm).toBeDisabled()
    fireEvent.change(reason, { target: { value: 'Too broad for a template' } })
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)
    await waitFor(() => expect(rejectMock).toHaveBeenCalledWith('mkt_pr', 'Too broad for a template'))
  })

  it('publishing from a resource calls publish-from with the picked resource and says what was stripped', async () => {
    listMcpDefsMock.mockResolvedValue([{ id: 'mcp_1', name: 'linear', url: 'https://x', credentialId: 'cred_1' }])
    publishFromMock.mockResolvedValue({ ...linear, id: 'mkt_new', stripped: ['credentialId'] })
    renderPage()
    await screen.findByText('Linear')
    fireEvent.click(screen.getByRole('button', { name: '+ Publish' }))

    const dialog = screen.getByRole('dialog')
    // The organization's own servers fill the select, and the picked one lends its name.
    expect(await within(dialog).findByRole('option', { name: 'linear' })).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Resource'), { target: { value: 'mcp_1' } })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('linear')
    fireEvent.change(within(dialog).getByLabelText('Summary (one line)'), { target: { value: 'Issues and cycles' } })
    fireEvent.change(within(dialog).getByLabelText('Tags (comma-separated)'), { target: { value: 'planning, mcp' } })
    // One organization: no scope control, everything is global.
    expect(within(dialog).queryByLabelText('Scope')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))

    await waitFor(() =>
      expect(publishFromMock).toHaveBeenCalledWith({
        kind: 'mcp',
        resourceId: 'mcp_1',
        name: 'linear',
        summary: 'Issues and cycles',
        description: undefined,
        tags: ['planning', 'mcp'],
        icon: undefined,
        scope: 'global',
      }),
    )
    expect(await screen.findByRole('status')).toHaveTextContent('credentialId')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(listMock).toHaveBeenCalledTimes(2)
  })

  it('says when there is nothing published at all', async () => {
    listMock.mockResolvedValue(list({ items: [], tags: [] }))
    renderPage()
    expect(await screen.findByText(/Nothing published yet/)).toBeInTheDocument()
  })
})
