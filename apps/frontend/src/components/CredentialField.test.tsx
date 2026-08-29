import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Credential } from '../api/types.ts'
import { CredentialField } from './CredentialField.tsx'

const listCredentialsMock = vi.fn<() => Promise<Credential[]>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listCredentials: () => listCredentialsMock(),
  },
}))

const cred = (id: string, label: string, hint: string | null = null): Credential => ({
  id,
  label,
  kind: 'token',
  hint,
  createdAt: 0,
  updatedAt: 0,
  lastUsedAt: null,
})

// A node keeps a credential's id, never the secret: the flow is saved into version history and
// duplicated whole, and a secret in the node would go everywhere the node goes.
describe('CredentialField', () => {
  // Block body on purpose: `mockResolvedValue` returns the mock, and a function returned from
  // beforeEach is a teardown vitest calls after the test — which would call the mock once more.
  beforeEach(() => {
    listCredentialsMock.mockResolvedValue([cred('c1', 'GitHub PAT', 'ghp_…9f2'), cred('c2', 'GitLab token')])
  })

  it('lists the stored credentials by label and masked hint, and forwards the id', async () => {
    const onChange = vi.fn()
    render(<CredentialField label="Provider token" value="" onChange={onChange} />)

    expect(screen.getByLabelText('Provider token')).toHaveValue('')
    expect(await screen.findByRole('option', { name: 'GitHub PAT (ghp_…9f2)' })).toBeInTheDocument()
    // No hint stored: a mask stands in, so the row still reads as a secret.
    expect(screen.getByRole('option', { name: 'GitLab token (••••)' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Provider token'), { target: { value: 'c2' } })
    expect(onChange).toHaveBeenCalledWith('c2')
  })

  it('names what the secret is for in the hint, and says where it lives', () => {
    render(<CredentialField label="Password" value="" onChange={vi.fn()} what="this database" />)
    expect(screen.getByText('Resources → Credentials')).toBeInTheDocument()
    expect(screen.getByText(/without carrying the secret for this database/)).toBeInTheDocument()
  })

  it('defaults the hint to "this connection" when nobody said what it is for', () => {
    render(<CredentialField label="Token" value="" onChange={vi.fn()} />)
    expect(screen.getByText(/without carrying the secret for this connection/)).toBeInTheDocument()
  })

  it('calls out a referenced credential that no longer exists — which is not the same as none', async () => {
    render(<CredentialField label="Provider token" value="deleted_id" onChange={vi.fn()} />)

    await waitFor(() => expect(listCredentialsMock).toHaveBeenCalled())
    expect(await screen.findByText('The selected credential no longer exists.')).toBeInTheDocument()
    expect(screen.getByText('Pick another one.')).toBeInTheDocument()
  })

  it('does not complain about one that exists, nor about having none', async () => {
    const { rerender } = render(<CredentialField label="Provider token" value="c1" onChange={vi.fn()} />)
    await screen.findByRole('option', { name: 'GitHub PAT (ghp_…9f2)' })
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Provider token')).toHaveValue('c1')

    rerender(<CredentialField label="Provider token" value="" onChange={vi.fn()} />)
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument()
  })

  it('offers only "none" when the list cannot be fetched', async () => {
    listCredentialsMock.mockRejectedValue(new Error('offline'))
    render(<CredentialField label="Provider token" value="" onChange={vi.fn()} />)

    await waitFor(() => expect(listCredentialsMock).toHaveBeenCalled())
    expect(screen.getAllByRole('option')).toHaveLength(1)
    expect(screen.getByRole('option', { name: '— none —' })).toBeInTheDocument()
  })
})
