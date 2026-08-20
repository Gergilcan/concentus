import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SignIn } from './SignIn.tsx'

const signInMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    signIn: (email: string, password: string) => signInMock(email, password),
  },
}))

describe('SignIn', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('signs in and hands the user back to the caller', async () => {
    const user = { userId: 'usr_1', email: 'admin@x.com', organizationId: 'default', role: 'ADMIN' }
    signInMock.mockResolvedValue(user)
    const onSignedIn = vi.fn()
    render(<SignIn onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'admin@x.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'a-long-password' } })
    fireEvent.click(screen.getByText('Sign in'))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(user))
  })

  it('shows the failure and does not sign the user in', async () => {
    signInMock.mockRejectedValue(new Error('Invalid email or password.'))
    const onSignedIn = vi.fn()
    render(<SignIn onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'admin@x.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong-password' } })
    fireEvent.click(screen.getByText('Sign in'))

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument()
    expect(onSignedIn).not.toHaveBeenCalled()
  })

  it('offers no way to create an account', () => {
    // On a self-hosted install, open registration would let whoever reaches the server first
    // claim the organization.
    render(<SignIn onSignedIn={vi.fn()} />)

    expect(screen.queryByText(/sign up|create an account|register/i)).not.toBeInTheDocument()
  })

  // A company rarely has one way in: staff on the corporate directory, a contractor with a Google
  // account, and whoever installed the thing with a password. Each button has to go to its own
  // provider — one that sends people to the wrong directory is worse than no button.
  it('offers every configured provider, each to its own', () => {
    render(
      <SignIn
        onSignedIn={vi.fn()}
        providers={[
          { id: 'microsoft', name: 'Microsoft' },
          { id: 'google', name: 'Google' },
        ]}
      />,
    )

    expect(screen.getByRole('link', { name: 'Continue with Microsoft' })).toHaveAttribute(
      'href',
      '/api/account/oidc/start?provider=microsoft',
    )
    expect(screen.getByRole('link', { name: 'Continue with Google' })).toHaveAttribute(
      'href',
      '/api/account/oidc/start?provider=google',
    )
  })

  // Passwords alone is a real deployment, and an 'or' with nothing after it is a broken screen.
  it('shows no provider buttons when there are none', () => {
    render(<SignIn onSignedIn={vi.fn()} />)

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('or')).not.toBeInTheDocument()
  })

  it('says so when the backend cannot reach its account store', () => {
    render(<SignIn onSignedIn={vi.fn()} storeUnavailable />)

    expect(screen.getByText(/cannot reach its database/)).toBeInTheDocument()
  })
})
