import { useEffect, useRef, useState } from 'react'
import { shellBridge } from '../api/shell.ts'
import { usePermissions } from '../state/permissions.tsx'
import styles from './appheader.module.scss'

/** Title case for a role that arrives shouting from the API. */
function roleLabel(role: string): string {
  return role.charAt(0).toUpperCase() + role.slice(1).toLowerCase()
}

/**
 * Who you are signed in as, and the two things you can do about it.
 *
 * There was only a Sign out button, which answers neither question people actually have here:
 * <em>as whom</em> am I working, and <em>with what</em> may I work. Both matter most exactly when
 * roles are in use — an interface that hides what you cannot do looks identical to one that is
 * broken, unless it says which account it is hiding things from.
 *
 * The second window is the honest form of "several accounts at once": the session is a cookie, so
 * two accounts cannot share one browsing context. The shell opens a window with its own cookie
 * jar; it starts signed out, and from then on the two are independent — an admin in one, a viewer
 * in the other, side by side. In a browser there is no shell to ask, so the entry is not offered.
 */
export function AccountMenu({
  signedInAs,
  onSignOut,
}: {
  signedInAs: string
  onSignOut: () => void
}) {
  const [open, setOpen] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const box = useRef<HTMLDivElement>(null)
  const { role } = usePermissions()
  const accounts = shellBridge()?.accounts

  useEffect(() => {
    if (!open) return
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

  const openAnother = async () => {
    setNote(null)
    const result = await accounts?.openWindow()
    if (result && !result.ok) setNote(result.error ?? 'The window could not be opened.')
    else setOpen(false)
  }

  return (
    <div className={styles.account} ref={box}>
      <button
        type="button"
        className={styles.accountBtn}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        title={role ? `${signedInAs} — ${roleLabel(role)}` : signedInAs}
      >
        <span className={styles.accountEmail}>{signedInAs}</span>
        {role && <span className={styles.roleChip}>{roleLabel(role)}</span>}
      </button>

      {open && (
        <div className={styles.accountMenu} role="menu">
          <div className={styles.accountWho}>
            <span className={styles.accountWhoEmail}>{signedInAs}</span>
            <span className={styles.accountWhoRole}>
              {role ? `Signed in as ${roleLabel(role)}` : 'Signed in'}
            </span>
          </div>
          {accounts && (
            <button type="button" role="menuitem" onClick={() => void openAnother()}>
              Open a window for another account
              <small>Its own sign-in. Both stay open, so you can compare what each role sees.</small>
            </button>
          )}
          <button type="button" role="menuitem" className={styles.accountSignOut} onClick={onSignOut}>
            Sign out
          </button>
          {note && <p className={styles.accountNote}>{note}</p>}
        </div>
      )}
    </div>
  )
}
