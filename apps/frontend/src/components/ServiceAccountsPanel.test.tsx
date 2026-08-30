import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ServiceAccountListing } from '../api/types.ts'
import { ServiceAccountsPanel } from './ServiceAccountsPanel.tsx'

const listServiceAccounts = vi.fn()
const createServiceAccount = vi.fn()
const revokeServiceAccount = vi.fn()
const renameServiceAccount = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listServiceAccounts: () => listServiceAccounts(),
    createServiceAccount: (name: string, role: string) => createServiceAccount(name, role),
    revokeServiceAccount: (id: string) => revokeServiceAccount(id),
    renameServiceAccount: (id: string, name: string) => renameServiceAccount(id, name),
  },
}))

const HOUR = 3_600_000

function listing(over: Partial<ServiceAccountListing> = {}): ServiceAccountListing {
  return {
    accounts: [
      {
        id: 'sa_1',
        organizationId: 'org',
        name: 'nightly-report',
        role: 'OPERATOR',
        createdBy: 'gerard@tecnovent.com',
        createdAt: Date.now() - 48 * HOUR,
        lastUsedAt: Date.now() - 2 * HOUR,
        revokedAt: null,
      },
      {
        id: 'sa_2',
        organizationId: 'org',
        name: 'dashboard',
        role: 'VIEWER',
        createdBy: null,
        createdAt: Date.now() - 24 * HOUR,
        lastUsedAt: null,
        revokedAt: null,
      },
      {
        id: 'sa_3',
        organizationId: 'org',
        name: 'old-ci',
        role: 'MEMBER',
        createdBy: null,
        createdAt: Date.now() - 200 * HOUR,
        lastUsedAt: Date.now() - 100 * HOUR,
        revokedAt: Date.now() - 50 * HOUR,
      },
    ],
    active: 2,
    limit: null,
    refusal: null,
    ...over,
  }
}

/**
 * Tokens for machines: what each row says, the one moment the token is on screen, and the create
 * button being honest about the Team cap before the form is filled in. The explanations live in
 * tooltips, so the assertions read `title` where the old page had paragraphs.
 */
describe('ServiceAccountsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listServiceAccounts.mockResolvedValue(listing())
  })

  const open = async () => {
    render(<ServiceAccountsPanel pushError={vi.fn()} />)
    await screen.findByText('nightly-report')
  }

  it('lists every token as a row: role chip, last use, who minted it, and whether it still works', async () => {
    await open()

    expect(screen.getByText('Operator')).toHaveAttribute('title', expect.stringMatching(/runs flows/))
    expect(screen.getByText(/last used 2h ago/)).toBeInTheDocument()
    expect(screen.getByText(/never used/)).toBeInTheDocument()
    expect(screen.getByText(/created .* by gerard@tecnovent.com/)).toBeInTheDocument()
    // A revoked token has nothing left to do to it; the row stays, and says why on hover.
    expect(screen.getByText(/revoked 2d ago/)).toBeInTheDocument()
    expect(screen.getByText('old-ci').closest('li')).toHaveAttribute(
      'title',
      expect.stringMatching(/Kept as the record/),
    )
    expect(screen.getAllByRole('button', { name: 'Revoke' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Rename' })).toHaveLength(2)
    // The count is a chip; with no cap in force its tooltip says what is counted.
    expect(screen.getByText('2 in use')).toHaveAttribute('title', 'Working tokens; revoked ones do not count.')
  })

  // The token is not stored, so this is its only appearance anywhere — and the manual is the Copy
  // button's tooltip, not a block of text under the list.
  it('mints a token, shows it once with Copy and its usage on hover, and Done hides it for good', async () => {
    createServiceAccount.mockResolvedValue({
      account: { id: 'sa_9', organizationId: 'org', name: 'ci', role: 'OPERATOR', createdBy: null, createdAt: Date.now(), lastUsedAt: null, revokedAt: null },
      token: 'csa_' + 'x'.repeat(40),
    })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    await open()

    fireEvent.click(screen.getByRole('button', { name: '+ New' }))
    fireEvent.change(screen.getByPlaceholderText('nightly-report'), { target: { value: 'ci' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createServiceAccount).toHaveBeenCalledWith('ci', 'OPERATOR'))
    expect(await screen.findByText('csa_' + 'x'.repeat(40))).toBeInTheDocument()
    expect(screen.getByText(/Shown once/)).toBeInTheDocument()

    const copy = screen.getByRole('button', { name: 'Copy' })
    expect(copy).toHaveAttribute('title', expect.stringMatching(/Authorization: Bearer .* CONCENTUS_TOKEN/))
    fireEvent.click(copy)
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('csa_' + 'x'.repeat(40)))
    expect(await screen.findByText('Copied')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByText('csa_' + 'x'.repeat(40))).not.toBeInTheDocument()
  })

  // On Team the third token is refused by the backend; the button and the count chip say so on
  // hover before the form opens, and nothing on the page says it twice.
  it('disables + New at the Team cap and carries the refusal in the tooltips', async () => {
    const refusal =
      'Unlimited service accounts is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade. This deployment has 2 of 2 service accounts in use; revoke one to mint another.'
    listServiceAccounts.mockResolvedValue(listing({ limit: 2, active: 2, refusal }))
    await open()

    const button = screen.getByRole('button', { name: '+ New' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', refusal)
    expect(screen.getByText('2 of 2 in use')).toHaveAttribute('title', refusal)
    expect(screen.queryByText(refusal)).not.toBeInTheDocument()
  })

  it('leaves + New enabled below the cap, and the chip explains the cap', async () => {
    listServiceAccounts.mockResolvedValue(listing({ limit: 2, active: 1, refusal: null }))
    await open()

    const button = screen.getByRole('button', { name: '+ New' })
    expect(button).toBeEnabled()
    expect(button).toHaveAttribute('title', expect.stringMatching(/shown once/))
    expect(screen.getByText('1 of 2 in use')).toHaveAttribute('title', expect.stringMatching(/Team license/))
  })

  it('revokes after asking, and reloads', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    revokeServiceAccount.mockResolvedValue({})
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Revoke' })[0])

    await waitFor(() => expect(revokeServiceAccount).toHaveBeenCalledWith('sa_1'))
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('nightly-report'))
    await waitFor(() => expect(listServiceAccounts).toHaveBeenCalledTimes(2))
  })

  it('does nothing when the revocation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Revoke' })[0])

    expect(revokeServiceAccount).not.toHaveBeenCalled()
  })

  it('renames in place from the pencil', async () => {
    renameServiceAccount.mockResolvedValue({
      id: 'sa_2', organizationId: 'org', name: 'grafana', role: 'VIEWER', createdBy: null, createdAt: Date.now(), lastUsedAt: null, revokedAt: null,
    })
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Rename' })[1])
    const input = screen.getByLabelText('New name for dashboard')
    fireEvent.change(input, { target: { value: 'grafana' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(renameServiceAccount).toHaveBeenCalledWith('sa_2', 'grafana'))
    expect(await screen.findByText('grafana')).toBeInTheDocument()
  })

  it('opens the rename on a double-click of the name; Escape or an unchanged blur closes it without a request', async () => {
    await open()

    fireEvent.doubleClick(screen.getByText('dashboard'))
    const input = screen.getByLabelText('New name for dashboard')
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(screen.queryByLabelText('New name for dashboard')).not.toBeInTheDocument()

    fireEvent.doubleClick(screen.getByText('dashboard'))
    fireEvent.blur(screen.getByLabelText('New name for dashboard'))
    expect(screen.queryByLabelText('New name for dashboard')).not.toBeInTheDocument()
    expect(renameServiceAccount).not.toHaveBeenCalled()

    // A revoked one is history, not a thing to edit.
    fireEvent.doubleClick(screen.getByText('old-ci'))
    expect(screen.queryByLabelText('New name for old-ci')).not.toBeInTheDocument()
  })

  it('says it is empty in one line', async () => {
    listServiceAccounts.mockResolvedValue(listing({ accounts: [], active: 0 }))
    render(<ServiceAccountsPanel pushError={vi.fn()} />)

    expect(await screen.findByText('No service accounts yet.')).toBeInTheDocument()
    expect(screen.getByText('0 in use')).toBeInTheDocument()
  })
})
