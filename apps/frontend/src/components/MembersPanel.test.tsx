import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MembersPanel } from './MembersPanel.tsx'

const listMembers = vi.fn()
const changeMemberRole = vi.fn()
const addMember = vi.fn()
const session = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listMembers: () => listMembers(),
    changeMemberRole: (id: string, role: string) => changeMemberRole(id, role),
    addMember: (email: string, password: string, role: string) => addMember(email, password, role),
    session: () => session(),
  },
}))

/**
 * The roster, and the ladder it is a roster of.
 *
 * What this screen exists to answer is not "who is here" but "what may each of them do", so the
 * assertions are about the rungs being legible and changeable — and about the one row whose role
 * the reader can lock themselves out of.
 */
describe('MembersPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    session.mockResolvedValue({ authEnabled: true, signedIn: true, email: 'gerard@tecnovent.com' })
    listMembers.mockResolvedValue([
      { id: '1', email: 'gerard@tecnovent.com', role: 'ADMIN', createdAt: Date.now() - 86400000 },
      { id: '2', email: 'auditoria@tecnovent.com', role: 'VIEWER', createdAt: Date.now() - 3600000 },
    ])
  })

  const open = async () => {
    render(<MembersPanel pushError={vi.fn()} />)
    await screen.findByText('gerard@tecnovent.com')
  }

  it('lists everyone with the role each one has', async () => {
    await open()

    expect(screen.getByLabelText('Role for gerard@tecnovent.com')).toHaveValue('ADMIN')
    expect(screen.getByLabelText('Role for auditoria@tecnovent.com')).toHaveValue('VIEWER')
  })

  // Demoting yourself is the one change on this page you cannot undo from this page.
  it('marks the row belonging to the person reading it', async () => {
    await open()

    await waitFor(() => expect(screen.getByText('you')).toBeInTheDocument())
  })

  it('changes a role', async () => {
    changeMemberRole.mockResolvedValue({
      id: '2',
      email: 'auditoria@tecnovent.com',
      role: 'OPERATOR',
      createdAt: Date.now(),
    })
    await open()

    fireEvent.change(screen.getByLabelText('Role for auditoria@tecnovent.com'), {
      target: { value: 'OPERATOR' },
    })

    await waitFor(() => expect(changeMemberRole).toHaveBeenCalledWith('2', 'OPERATOR'))
  })

  // A role this build does not know must not read as the first option in the list: that would be
  // a confident, wrong statement about what the account can do.
  it('shows an unfamiliar role as itself rather than as the nearest option', async () => {
    listMembers.mockResolvedValue([
      { id: '9', email: 'legacy@tecnovent.com', role: 'SUPERUSER', createdAt: Date.now() },
    ])

    render(<MembersPanel pushError={vi.fn()} />)

    expect(await screen.findByText('SUPERUSER (unknown)')).toBeInTheDocument()
  })

  // The form used to own half the page for three fields while the roster was squeezed into a rail.
  it('keeps the add form out of the way until it is wanted', async () => {
    await open()
    expect(screen.queryByPlaceholderText('name@company.com')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Add member' }))

    expect(screen.getByPlaceholderText('name@company.com')).toBeInTheDocument()
  })

  it('adds a member and closes the form', async () => {
    addMember.mockResolvedValue({ id: '3' })
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }))
    fireEvent.change(screen.getByPlaceholderText('name@company.com'), {
      target: { value: 'marta@tecnovent.com' },
    })
    fireEvent.change(screen.getByPlaceholderText('At least 12 characters'), {
      target: { value: 'a-long-enough-one' },
    })

    fireEvent.click(screen.getByRole('button', { name: /^Adding|^Add member$/ }))

    await waitFor(() =>
      expect(addMember).toHaveBeenCalledWith('marta@tecnovent.com', 'a-long-enough-one', 'VIEWER'),
    )
  })

  // The rungs were explained in a select's tooltip, which is to say nowhere: the moment somebody
  // needs to know what Operator means is while deciding whether to grant it.
  it('says what every role may do, on the page rather than in a tooltip', async () => {
    await open()

    expect(screen.getByText('What each role may do')).toBeInTheDocument()
    expect(
      screen.getByText(/Also runs flows: start, stop, approve, retry, re-run a block/),
    ).toBeInTheDocument()
  })
})
