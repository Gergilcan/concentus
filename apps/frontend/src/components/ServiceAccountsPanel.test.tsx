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
 * Tokens for machines: what the list says about each, the one moment the token is on screen, and
 * the create button being honest about the Team cap before the form is filled in.
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

  it('lists every token with its role, its last use and whether it still works', async () => {
    await open()

    // Once on the row's chip, once in the legend underneath.
    expect(screen.getAllByText('Operator')).toHaveLength(2)
    expect(screen.getByText(/last used 2h ago/)).toBeInTheDocument()
    expect(screen.getByText(/never used/)).toBeInTheDocument()
    expect(screen.getByText('revoked')).toBeInTheDocument()
    expect(screen.getByText(/created .* by gerard@tecnovent.com/)).toBeInTheDocument()
    // A revoked token has nothing left to do to it.
    expect(screen.getAllByRole('button', { name: 'Revoke' })).toHaveLength(2)
    // The count shares its paragraph with the description sentence, hence the pattern.
    expect(screen.getByText(/2 tokens in use\./)).toBeInTheDocument()
  })

  // The token is not stored, so this is its only appearance anywhere — with the warning beside it.
  it('mints a token, shows it once with Copy and a warning, and Done hides it for good', async () => {
    createServiceAccount.mockResolvedValue({
      account: { id: 'sa_9', organizationId: 'org', name: 'ci', role: 'OPERATOR', createdBy: null, createdAt: Date.now(), lastUsedAt: null, revokedAt: null },
      token: 'csa_' + 'x'.repeat(40),
    })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'New service account' }))
    fireEvent.change(screen.getByPlaceholderText('nightly-report'), { target: { value: 'ci' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create token' }))

    await waitFor(() => expect(createServiceAccount).toHaveBeenCalledWith('ci', 'OPERATOR'))
    expect(await screen.findByText('csa_' + 'x'.repeat(40))).toBeInTheDocument()
    expect(screen.getByText(/only time it is shown/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Copy token' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('csa_' + 'x'.repeat(40)))
    expect(await screen.findByText('Copied ✓')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByText('csa_' + 'x'.repeat(40))).not.toBeInTheDocument()
  })

  // On Team the third token is refused by the backend; the button says so before the form opens.
  it('disables the create button at the Team cap and shows the refusal', async () => {
    const refusal =
      'Unlimited service accounts is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade. This deployment has 2 of 2 service accounts in use; revoke one to mint another.'
    listServiceAccounts.mockResolvedValue(listing({ limit: 2, active: 2, refusal }))
    await open()

    const button = screen.getByRole('button', { name: 'New service account' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', refusal)
    expect(screen.getByText(refusal)).toBeInTheDocument()
    expect(screen.getByText(/2 of 2 in use on this Team license\./)).toBeInTheDocument()
  })

  it('leaves the button enabled below the cap', async () => {
    listServiceAccounts.mockResolvedValue(listing({ limit: 2, active: 1, refusal: null }))
    await open()

    expect(screen.getByRole('button', { name: 'New service account' })).toBeEnabled()
    expect(screen.getByText(/1 of 2 in use on this Team license\./)).toBeInTheDocument()
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

  it('renames in place', async () => {
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

  it('says how a token is used, including the CLI variable', async () => {
    await open()

    expect(screen.getByText(/Authorization: Bearer csa_/)).toBeInTheDocument()
    expect(screen.getByText(/CONCENTUS_TOKEN=csa_/)).toBeInTheDocument()
    expect(screen.getByText(/No token can be an Admin/)).toBeInTheDocument()
  })
})
