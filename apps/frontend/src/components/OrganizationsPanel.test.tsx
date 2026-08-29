import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { OrganizationsPanel } from './OrganizationsPanel.tsx'

const listOrganizations = vi.fn()
const createOrganization = vi.fn()
const renameOrganization = vi.fn()
const listOrganizationMembers = vi.fn()
const inviteToOrganization = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listOrganizations: () => listOrganizations(),
    createOrganization: (name: string) => createOrganization(name),
    renameOrganization: (id: string, name: string) => renameOrganization(id, name),
    listOrganizationMembers: (id: string) => listOrganizationMembers(id),
    inviteToOrganization: (id: string, email: string, password: string, role: string) =>
      inviteToOrganization(id, email, password, role),
  },
}))

/**
 * Several organizations on one deployment, from the administrator's side.
 *
 * Two things matter here. The Enterprise gate on creating a second one has to be legible as a
 * sentence rather than as a missing button — the backend answers with the feature's own words
 * and the panel must show them where the person is looking. And the roster of another
 * organization has to be reachable without leaving the list, because "who is in the other one"
 * is asked beside it.
 */
describe('OrganizationsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listOrganizations.mockResolvedValue([
      { id: 'org_a', name: 'Tecnovent', role: 'ADMIN', current: true, createdAt: Date.now() - 86400000 },
      { id: 'org_b', name: 'Filial Norte', role: 'ADMIN', current: false, createdAt: Date.now() - 3600000 },
    ])
    listOrganizationMembers.mockResolvedValue([
      { id: '1', organizationId: 'org_b', email: 'gerard@tecnovent.com', role: 'ADMIN', enabled: true, createdAt: 1 },
      { id: '2', organizationId: 'org_b', email: 'norte@tecnovent.com', role: 'VIEWER', enabled: true, createdAt: 2 },
    ])
    inviteToOrganization.mockResolvedValue({ id: '3', email: 'nueva@tecnovent.com', role: 'MEMBER' })
  })

  const open = async () => {
    render(<OrganizationsPanel pushError={vi.fn()} />)
    await screen.findByText('Tecnovent')
  }

  it('lists every organization the admin is in, marking the current one', async () => {
    await open()

    expect(screen.getByText('Filial Norte')).toBeInTheDocument()
    expect(screen.getByText('current')).toBeInTheDocument()
  })

  // The gate is a sentence, not an absence: the button is there, and the refusal it gets names
  // the feature and the tier that has it.
  it('shows the Enterprise refusal beside the form when a second organization is refused', async () => {
    createOrganization.mockRejectedValue(
      new Error('Several organizations on one deployment is an Enterprise feature. Install an enterprise license to use it.'),
    )
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'New organization' }))
    fireEvent.change(screen.getByPlaceholderText('The team or company it is for'), {
      target: { value: 'Filial Sur' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('is an Enterprise feature')
    expect(createOrganization).toHaveBeenCalledWith('Filial Sur')
  })

  it('creates a second organization when the license allows it', async () => {
    createOrganization.mockResolvedValue({ id: 'org_c', name: 'Filial Sur', role: 'ADMIN', current: false, createdAt: 1 })
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'New organization' }))
    fireEvent.change(screen.getByPlaceholderText('The team or company it is for'), {
      target: { value: 'Filial Sur' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createOrganization).toHaveBeenCalledWith('Filial Sur'))
    // Reloaded from the API rather than appended: the list is whatever the backend says it is.
    await waitFor(() => expect(listOrganizations).toHaveBeenCalledTimes(2))
  })

  it('renames an organization in place', async () => {
    renameOrganization.mockResolvedValue({ id: 'org_b', name: 'Norte', role: 'ADMIN', current: false, createdAt: 1 })
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Rename' })[1])
    const input = screen.getByLabelText('New name for Filial Norte')
    fireEvent.change(input, { target: { value: 'Norte' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(renameOrganization).toHaveBeenCalledWith('org_b', 'Norte'))
  })

  it('opens another organization roster and adds somebody to it with a role', async () => {
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Members' })[1])
    expect(await screen.findByText('norte@tecnovent.com')).toBeInTheDocument()
    expect(listOrganizationMembers).toHaveBeenCalledWith('org_b')

    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'nueva@tecnovent.com' },
    })
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'MEMBER' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add to organization' }))

    await waitFor(() =>
      expect(inviteToOrganization).toHaveBeenCalledWith('org_b', 'nueva@tecnovent.com', '', 'MEMBER'),
    )
  })
})
