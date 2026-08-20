import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountMenu } from './AccountMenu.tsx'
import { PermissionsProvider } from '../state/permissions.tsx'

const openWindow = vi.fn()
const switchableAccounts = vi.fn()
const useAccount = vi.fn()
const forgetAccount = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    switchableAccounts: () => switchableAccounts(),
    useAccount: (id: string) => useAccount(id),
    forgetAccount: (id: string) => forgetAccount(id),
  },
}))

/**
 * The corner of the header that says who this window is, and every account it can become.
 *
 * Two things it has to get right, both of which only matter once roles are in use. Naming the role
 * beside every address, because "you cannot do that" and "this app is broken" look identical on
 * screen without it. And switching in one click, because the alternative — sign out, retype an
 * address, retype a password, and the same in reverse — is enough friction that permissions stop
 * being checked at all.
 */
describe('AccountMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    openWindow.mockResolvedValue({ ok: true })
    switchableAccounts.mockResolvedValue([
      { userId: '1', email: 'gerard@tecnovent.com', role: 'ADMIN', current: true },
      { userId: '2', email: 'auditoria@tecnovent.com', role: 'VIEWER', current: false },
    ])
    useAccount.mockResolvedValue({ userId: '2' })
    forgetAccount.mockResolvedValue(undefined)
  })

  afterEach(() => {
    delete (window as { concentusShell?: unknown }).concentusShell
  })

  const inShell = () => {
    ;(window as { concentusShell?: unknown }).concentusShell = {
      updates: {},
      accounts: { openWindow: () => openWindow() },
    }
  }

  const render1 = (role: string | null = 'ADMIN', email = 'gerard@tecnovent.com') =>
    render(
      <PermissionsProvider role={role} authEnabled>
        <AccountMenu signedInAs={email} onSignOut={vi.fn()} />
      </PermissionsProvider>,
    )

  const open = (role: string | null = 'ADMIN') => {
    render1(role)
    fireEvent.click(screen.getByRole('button', { name: /Account:/i }))
  }

  it('is a face with the account behind it, not a wall of text in the header', () => {
    render1('VIEWER', 'auditoria@tecnovent.com')

    const trigger = screen.getByRole('button', { name: /Account: auditoria@tecnovent.com/i })
    expect(trigger).toHaveTextContent('A')
    expect(trigger).toHaveAttribute('title', 'auditoria@tecnovent.com — Viewer')
  })

  it('lists the other accounts with the role each one has', async () => {
    open()

    expect(await screen.findByText('auditoria@tecnovent.com')).toBeInTheDocument()
    expect(screen.getByText('Viewer')).toBeInTheDocument()
    expect(screen.getByText('Admin')).toBeInTheDocument()
  })

  // The account already in use is the heading, not an option: offering it would be offering to do
  // nothing, and it would read as though this window were not already it.
  it('does not offer the account already in use', async () => {
    open()
    await screen.findByText('auditoria@tecnovent.com')

    expect(screen.getAllByText('gerard@tecnovent.com')).toHaveLength(1)
  })

  it('switches to another account in one click', async () => {
    open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /auditoria@tecnovent.com/i }))

    await waitFor(() => expect(useAccount).toHaveBeenCalledWith('2'))
  })

  // The way out of "this machine can become four people".
  it('forgets an account without touching the account itself', async () => {
    open()
    await screen.findByText('auditoria@tecnovent.com')

    fireEvent.click(screen.getByRole('button', { name: /Forget auditoria@tecnovent.com/i }))

    await waitFor(() => expect(forgetAccount).toHaveBeenCalledWith('2'))
    await waitFor(() =>
      expect(screen.queryByText('auditoria@tecnovent.com')).not.toBeInTheDocument(),
    )
  })

  it('opens a second window through the shell', async () => {
    inShell()
    open()

    fireEvent.click(await screen.findByRole('menuitem', { name: /second window/i }))

    expect(openWindow).toHaveBeenCalled()
  })

  // A browser tab has one cookie jar and no way to make another, so offering the entry there would
  // be offering something that cannot work.
  it('does not offer a second window outside the desktop app', async () => {
    open()
    await screen.findByText('auditoria@tecnovent.com')

    expect(screen.queryByRole('menuitem', { name: /second window/i })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('signs out', () => {
    const onSignOut = vi.fn()
    render(
      <PermissionsProvider role="ADMIN" authEnabled>
        <AccountMenu signedInAs="gerard@tecnovent.com" onSignOut={onSignOut} />
      </PermissionsProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: /Account:/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    expect(onSignOut).toHaveBeenCalled()
  })

  it('closes when Escape is pressed', () => {
    open()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('menuitem', { name: 'Sign out' })).not.toBeInTheDocument()
  })
})
