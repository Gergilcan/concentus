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

  it('says so when the backend cannot reach its account store', () => {
    render(<SignIn onSignedIn={vi.fn()} storeUnavailable />)

    expect(screen.getByText(/cannot reach its database/)).toBeInTheDocument()
  })
})
