import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SetupWizard } from './SetupWizard.tsx'

const createFirstAccount = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    createFirstAccount: (email: string, password: string) => createFirstAccount(email, password),
  },
}))

/**
 * The first launch.
 *
 * There is nobody to sign in as, and the account is not one the backend invented — that version
 * printed a generated password into a log nobody reads. So this screen has one job: get the person
 * in front of the machine to choose an account, and let them straight in with it.
 */
describe('SetupWizard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    createFirstAccount.mockResolvedValue({
      userId: '1',
      email: 'gerard@tecnovent.com',
      organizationId: 'local',
      role: 'ADMIN',
    })
  })

  const fill = (password: string, confirm = password) => {
    fireEvent.change(screen.getByLabelText('Email'), {
      target: { value: 'gerard@tecnovent.com' },
    })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
    fireEvent.change(screen.getByLabelText('Password again'), { target: { value: confirm } })
  }

  it('says what the account being created is for', () => {
    render(<SetupWizard onSignedIn={vi.fn()} />)

    expect(screen.getByText(/no accounts here yet/i)).toBeInTheDocument()
    expect(screen.getByText(/administers it/i)).toBeInTheDocument()
  })

  it('creates the first account and signs it in', async () => {
    const onSignedIn = vi.fn()
    render(<SetupWizard onSignedIn={onSignedIn} />)
    fill('a-long-enough-password')

    fireEvent.click(screen.getByRole('button', { name: /Create account/i }))

    await waitFor(() =>
      expect(createFirstAccount).toHaveBeenCalledWith('gerard@tecnovent.com', 'a-long-enough-password'),
    )
    // Straight in: somebody who chose a password seconds ago has proved what a login form asks.
    await waitFor(() => expect(onSignedIn).toHaveBeenCalled())
  })

  // Said while there is still a cursor in the field, rather than after a round trip: the backend
  // refuses a weak password either way, but only one of the two answers arrives in time to help.
  it('says how much more password is needed before anything is sent', () => {
    render(<SetupWizard onSignedIn={vi.fn()} />)
    fill('short')

    expect(screen.getByText('7 more characters')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Create account/i })).toBeDisabled()
  })

  it('will not create an account from two different passwords', () => {
    render(<SetupWizard onSignedIn={vi.fn()} />)
    fill('a-long-enough-password', 'a-long-enough-passwerd')

    expect(screen.getByText('These do not match.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Create account/i })).toBeDisabled()
  })

  // A work account is the better answer where there is one: nothing to invent, nothing to
  // remember, and the password that would otherwise be created here never exists.
  it('offers the configured providers as a way to set up', () => {
    render(
      <SetupWizard
        onSignedIn={vi.fn()}
        providers={[
          { id: 'microsoft', name: 'Microsoft' },
          { id: 'google', name: 'Google' },
        ]}
      />,
    )

    expect(screen.getByRole('link', { name: 'Set up with Microsoft' })).toHaveAttribute(
      'href',
      '/api/account/oidc/start?provider=microsoft',
    )
    expect(screen.getByRole('link', { name: 'Set up with Google' })).toBeInTheDocument()
  })

  it('says when the database cannot be reached, because nothing can be created then', () => {
    render(<SetupWizard onSignedIn={vi.fn()} storeUnavailable />)

    expect(screen.getByRole('alert')).toHaveTextContent(/cannot reach its database/i)
  })

  // A second tab that was open during the first one's setup. Nothing is wrong with the request; it
  // simply arrived after the question was settled, and the person needs to be told which it is.
  it('shows the refusal when somebody else got there first', async () => {
    createFirstAccount.mockRejectedValue(
      new Error('This installation already has an account. Sign in with it instead.'),
    )
    render(<SetupWizard onSignedIn={vi.fn()} />)
    fill('a-long-enough-password')

    fireEvent.click(screen.getByRole('button', { name: /Create account/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/already has an account/i)
  })
})
