import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { SwitchableAccount } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { shellBridge } from '../api/shell.ts'
import { usePermissions } from '../state/permissions.tsx'
import styles from './appheader.module.scss'

/** Title case for a role that arrives shouting from the API. */
function roleLabel(role: string | null | undefined): string {
  if (!role) return ''
  return role.charAt(0).toUpperCase() + role.slice(1).toLowerCase()
}

/** The letter on the disc. The address is right beside it; this is for picking a row out at a glance. */
function initial(email: string): string {
  return (email.trim()[0] ?? '?').toUpperCase()
}

/**
 * Who this window is working as, and every account it can become.
 *
 * <p>There was only a Sign out button, which answers neither question people have in a workspace
 * with roles: <em>as whom</em> am I working, and <em>with what</em> may I work. An interface that
 * hides what you cannot do looks exactly like one that is broken, unless it names the account it
 * is hiding things from — so the role sits beside every address here, not only the current one.
 *
 * <p>Switching is a click because the alternative is why permissions go unchecked. Verifying what
 * an operator sees and then going back to fix it otherwise means signing out, retyping an address,
 * retyping a password, and the same again in reverse. The accounts on this list got here by being
 * signed into on this browser, and that is the entire authorization for returning to one: the
 * browser proved it may be each of them, once, with their own password or their own provider.
 *
 * <p>One at a time, deliberately: a session is a cookie, and two accounts cannot share one
 * browsing context. Where the desktop shell is present it can open a second window with a cookie
 * jar of its own, which is the only honest way to have two on screen at once.
 */
export function AccountMenu({
  signedInAs,
  onSignOut,
}: {
  signedInAs: string
  onSignOut: () => void
}) {
  const [open, setOpen] = useState(false)
  const [accounts, setAccounts] = useState<SwitchableAccount[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const box = useRef<HTMLDivElement>(null)
  const { role } = usePermissions()
  const shellAccounts = shellBridge()?.accounts

  useEffect(() => {
    if (!open) return
    // Read when the menu opens rather than on every render of the header: the answer only matters
    // while it is on screen, and it changes when somebody signs in elsewhere in the app.
    api
      .switchableAccounts()
      .then(setAccounts)
      .catch(() => setAccounts([]))
    const away = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', away)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  // Not named useAccount: a function starting with "use" reads as a hook to every linter and to
  // the next person, and this one is an event handler that reloads the page.
  const switchTo = async (account: SwitchableAccount) => {
    if (account.current) return setOpen(false)
    setBusy(account.userId)
    setNote(null)
    try {
      await api.useAccount(account.userId)
      // Reload rather than swapping state in place: every hook holding data fetched as the
      // previous account has to be discarded, and a reload is the only way to be sure none is
      // missed — the same reasoning as signing out.
      window.location.reload()
    } catch (e) {
      setNote(errMessage(e))
      setBusy(null)
    }
  }

  const forget = async (account: SwitchableAccount) => {
    setBusy(account.userId)
    setNote(null)
    try {
      await api.forgetAccount(account.userId)
      setAccounts((prev) => prev.filter((a) => a.userId !== account.userId))
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const openAnotherWindow = async () => {
    setNote(null)
    const result = await shellAccounts?.openWindow()
    if (result && !result.ok) setNote(result.error ?? 'The window could not be opened.')
    else setOpen(false)
  }

  const others = accounts.filter((a) => !a.current)

  return (
    <div className={styles.account} ref={box}>
      <button
        type="button"
        className={styles.avatarBtn}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account: ${signedInAs}${role ? ` (${roleLabel(role)})` : ''}`}
        title={role ? `${signedInAs} — ${roleLabel(role)}` : signedInAs}
        onClick={() => setOpen((o) => !o)}
      >
        <span className={styles.avatar}>{initial(signedInAs)}</span>
      </button>

      {open && (
        <div className={styles.accountMenu} role="menu">
          <div className={styles.accountWho}>
            <span className={styles.avatarLarge}>{initial(signedInAs)}</span>
            <span className={styles.accountWhoText}>
              <span className={styles.accountWhoEmail}>{signedInAs}</span>
              {role && <span className={styles.roleChip}>{roleLabel(role)}</span>}
            </span>
          </div>

          {others.length > 0 && (
            <ul className={styles.accountList}>
              {others.map((a) => (
                <li key={a.userId}>
                  <button
                    type="button"
                    role="menuitem"
                    className={styles.accountRow}
                    disabled={busy === a.userId}
                    onClick={() => void switchTo(a)}
                  >
                    <span className={styles.avatar}>{initial(a.email)}</span>
                    <span className={styles.accountRowText}>{a.email}</span>
                    <span className={styles.roleChip}>{roleLabel(a.role)}</span>
                  </button>
                  <button
                    type="button"
                    className={styles.forgetBtn}
                    title={`Forget ${a.email} on this device. Signing in again brings it back.`}
                    aria-label={`Forget ${a.email}`}
                    disabled={busy === a.userId}
                    onClick={() => void forget(a)}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}

          <button type="button" role="menuitem" className={styles.accountAction} onClick={onSignOut}>
            Add another account
            <small>Signs this window out so you can sign in as somebody else. Both stay here.</small>
          </button>

          {shellAccounts && (
            <button
              type="button"
              role="menuitem"
              className={styles.accountAction}
              onClick={() => void openAnotherWindow()}
            >
              Open a second window
              <small>Its own sign-in, side by side — the only way to have two roles on screen at once.</small>
            </button>
          )}

          <button
            type="button"
            role="menuitem"
            className={`${styles.accountAction} ${styles.accountSignOut}`}
            onClick={onSignOut}
          >
            Sign out
          </button>

          {note && <p className={styles.accountNote}>{note}</p>}
        </div>
      )}
    </div>
  )
}
