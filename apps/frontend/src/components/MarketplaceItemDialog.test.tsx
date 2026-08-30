import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MarketplaceItem } from '../api/types.ts'
import { mktItem } from '../test/marketplace.ts'
import { MarketplaceItemDialog } from './MarketplaceItemDialog.tsx'

const installMock = vi.fn()
const uninstallMock = vi.fn()
const deleteMock = vi.fn()
const approveMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    installMarketplaceItem: (id: string) => installMock(id),
    uninstallMarketplaceItem: (id: string) => uninstallMock(id),
    deleteMarketplaceItem: (id: string) => deleteMock(id),
    approveMarketplaceItem: (id: string) => approveMock(id),
  },
}))

function renderDialog(item: MarketplaceItem) {
  const props = {
    onClose: vi.fn(),
    onChanged: vi.fn(),
    onEdit: vi.fn(),
    onOpenResources: vi.fn(),
    onOpenFlow: vi.fn(),
    pushError: vi.fn(),
  }
  render(<MarketplaceItemDialog item={item} {...props} />)
  return props
}

const button = (name: string) => screen.queryByRole('button', { name })
const installed = { resourceId: 'res_1', version: 1, installedAt: 1 }

// The backend says what this person may do (canEdit, canCurate) and what their organization
// installed; the dialog only draws the buttons those facts allow.
describe('MarketplaceItemDialog', () => {
  afterEach(() => {
    vi.clearAllMocks()
    vi.restoreAllMocks()
  })

  it('a published item nobody installed offers Install and nothing else', () => {
    renderDialog(mktItem())
    expect(button('Install')).toBeInTheDocument()
    for (const name of ['Uninstall', 'Edit', 'Delete', 'Approve', 'Reject…']) expect(button(name)).toBeNull()
    expect(button('Install')).toHaveAttribute('title', expect.stringMatching(/Creates it in this organization/))
  })

  it('installed at the current version offers Uninstall; behind the current version offers Update', () => {
    const { unmount } = render(
      <MarketplaceItemDialog
        item={mktItem({ installed })}
        onClose={vi.fn()}
        onChanged={vi.fn()}
        onEdit={vi.fn()}
        onOpenResources={vi.fn()}
        onOpenFlow={vi.fn()}
        pushError={vi.fn()}
      />,
    )
    expect(button('Uninstall')).toBeInTheDocument()
    expect(button('Install')).toBeNull()
    expect(screen.getByText('Installed ✓')).toBeInTheDocument()
    unmount()

    renderDialog(mktItem({ installed, version: 3 }))
    expect(button('Update to v3')).toBeInTheDocument()
    expect(button('Uninstall')).toBeNull()
    expect(screen.getByText('Update ↑')).toBeInTheDocument()
  })

  it('Edit and Delete appear for the author or an admin, never on a built-in', () => {
    const { unmount } = render(
      <MarketplaceItemDialog
        item={mktItem({ canEdit: true })}
        onClose={vi.fn()}
        onChanged={vi.fn()}
        onEdit={vi.fn()}
        onOpenResources={vi.fn()}
        onOpenFlow={vi.fn()}
        pushError={vi.fn()}
      />,
    )
    expect(button('Edit')).toBeInTheDocument()
    expect(button('Delete')).toBeInTheDocument()
    unmount()

    renderDialog(mktItem({ canEdit: true, builtIn: true }))
    expect(button('Edit')).toBeNull()
    expect(button('Delete')).toBeNull()
    expect(screen.getByText('built-in')).toBeInTheDocument()
  })

  it('Approve and Reject appear for a curator on a pending item only', () => {
    const { unmount } = render(
      <MarketplaceItemDialog
        item={mktItem({ canCurate: true, status: 'pending', publishedAt: null })}
        onClose={vi.fn()}
        onChanged={vi.fn()}
        onEdit={vi.fn()}
        onOpenResources={vi.fn()}
        onOpenFlow={vi.fn()}
        pushError={vi.fn()}
      />,
    )
    expect(button('Approve')).toBeInTheDocument()
    expect(button('Reject…')).toBeInTheDocument()
    // Not installable while it waits.
    expect(button('Install')).toBeNull()
    unmount()

    renderDialog(mktItem({ canCurate: true }))
    expect(button('Approve')).toBeNull()
    expect(button('Reject…')).toBeNull()
  })

  it('Approve calls the endpoint and tells the page to read the list again', async () => {
    approveMock.mockResolvedValue(undefined)
    const { onChanged } = renderDialog(mktItem({ id: 'mkt_p', canCurate: true, status: 'pending' }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('mkt_p'))
    expect(onChanged).toHaveBeenCalled()
  })

  it('Uninstall asks first, then removes what the install created', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    uninstallMock.mockResolvedValue(undefined)
    const { onChanged } = renderDialog(mktItem({ id: 'mkt_u', installed }))
    fireEvent.click(screen.getByRole('button', { name: 'Uninstall' }))
    await waitFor(() => expect(uninstallMock).toHaveBeenCalledWith('mkt_u'))
    expect(onChanged).toHaveBeenCalled()
  })

  it('a refused Uninstall confirmation calls nothing', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderDialog(mktItem({ installed }))
    fireEvent.click(screen.getByRole('button', { name: 'Uninstall' }))
    expect(uninstallMock).not.toHaveBeenCalled()
  })

  it('Delete closes the dialog once the server has answered', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    deleteMock.mockResolvedValue(undefined)
    const { onClose, onChanged } = renderDialog(mktItem({ id: 'mkt_d', canEdit: true }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith('mkt_d'))
    expect(onClose).toHaveBeenCalled()
    expect(onChanged).toHaveBeenCalled()
  })

  it('Edit hands the item to the publish form', () => {
    const item = mktItem({ canEdit: true })
    const { onEdit } = renderDialog(item)
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(onEdit).toHaveBeenCalledWith(item)
  })

  it('an installed flow links to Flows; an API item says the node reads it', () => {
    const { onOpenFlow } = renderDialog(mktItem({ kind: 'flow', installed: { ...installed, resourceId: 'flow_7' } }))
    fireEvent.click(screen.getByRole('button', { name: 'Open in Flows' }))
    expect(onOpenFlow).toHaveBeenCalledWith('flow_7')
  })

  it('a failed install reaches the toast and leaves the dialog usable', async () => {
    installMock.mockRejectedValue(new Error('Your role (viewer) cannot install.'))
    const { pushError } = renderDialog(mktItem())
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(pushError).toHaveBeenCalledWith('Your role (viewer) cannot install.'))
    expect(screen.getByRole('button', { name: 'Install' })).toBeEnabled()
  })

  it('shows the payload the way the resource’s own panel would', () => {
    renderDialog(
      mktItem({
        kind: 'agent',
        payload: { model: 'claude-opus', effort: 'high', maxTokens: 8000, systemPrompt: 'You lead the team.' },
      }),
    )
    expect(screen.getByText('Model')).toBeInTheDocument()
    expect(screen.getByText('claude-opus')).toBeInTheDocument()
    expect(screen.getByText('System prompt')).toBeInTheDocument()
    expect(screen.getByText('You lead the team.')).toBeInTheDocument()
  })

  it('renders the description as minimal markdown', () => {
    renderDialog(mktItem({ description: '**Fast** reviews\n\n- one\n- two\n\nUse `gh`.' }))
    expect(screen.getByText('Fast').tagName).toBe('STRONG')
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByText('gh').tagName).toBe('CODE')
  })

  it('a rejected item shows the curator’s sentence', () => {
    renderDialog(mktItem({ status: 'rejected', rejection: 'Duplicates the built-in one.' }))
    expect(screen.getByText('Rejected: Duplicates the built-in one.')).toBeInTheDocument()
    expect(button('Install')).toBeNull()
  })
})
