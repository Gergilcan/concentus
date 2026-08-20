import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountMenu } from './AccountMenu.tsx'
import { PermissionsProvider } from '../state/permissions.tsx'

const openWindow = vi.fn()

/**
 * The corner of the header that says who this window is.
 *
 * Two things it must get right, both of which only matter once roles are in use: naming the
 * account, and offering the second window — because the session is a cookie, and a second account
 * needs a second cookie jar that only the desktop shell can make.
 */
describe('AccountMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    openWindow.mockResolvedValue({ ok: true })
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

  const open = (role: string | null = 'ADMIN') => {
    render(
      <PermissionsProvider role={role} authEnabled>
        <AccountMenu signedInAs="gerard@tecnovent.com" onSignOut={vi.fn()} />
      </PermissionsProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: /gerard@tecnovent.com/i }))
  }

  // "You cannot do that" and "this app is broken" look the same on screen. The difference is
  // knowing which account is being told no.
  it('names the account and its role without being opened', () => {
    inShell()
    render(
      <PermissionsProvider role="VIEWER" authEnabled>
        <AccountMenu signedInAs="auditoria@tecnovent.com" onSignOut={vi.fn()} />
      </PermissionsProvider>,
    )

    expect(screen.getByText('auditoria@tecnovent.com')).toBeInTheDocument()
    expect(screen.getByText('Viewer')).toBeInTheDocument()
  })

  it('opens a second window through the shell', async () => {
    inShell()
    open()

    fireEvent.click(screen.getByRole('menuitem', { name: /another account/i }))

    expect(openWindow).toHaveBeenCalled()
  })

  // A browser tab has one cookie jar and no way to make another, so offering the entry there
  // would be offering something that cannot work.
  it('does not offer a second window outside the desktop app', () => {
    open()

    expect(screen.queryByRole('menuitem', { name: /another account/i })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('signs out', () => {
    const onSignOut = vi.fn()
    render(
      <PermissionsProvider role="ADMIN" authEnabled>
        <AccountMenu signedInAs="gerard@tecnovent.com" onSignOut={onSignOut} />
      </PermissionsProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: /gerard@tecnovent.com/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    expect(onSignOut).toHaveBeenCalled()
  })

  it('closes when Escape is pressed', () => {
    open()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('menuitem', { name: 'Sign out' })).not.toBeInTheDocument()
  })
})
