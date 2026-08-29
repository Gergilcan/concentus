import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { Organization, SwitchableAccount } from '../api/types.ts'
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
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [accounts, setAccounts] = useState<SwitchableAccount[]>([])
  const [organizations, setOrganizations] = useState<Organization[]>([])
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
    // Same moment, same reason: which organizations this account can work in is a fact about the
    // account, and it changes when an admin invites it somewhere while the menu is closed.
    api
      .listOrganizations()
      .then(setOrganizations)
      .catch(() => setOrganizations([]))
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

  // The same person, a different workspace: the session stays, the organization behind every
  // store call changes, and a reload is what discards everything fetched under the old one.
  const switchOrganization = async (org: Organization) => {
    if (org.current) return setOpen(false)
    setBusy(org.id)
    setNote(null)
    try {
      await api.switchOrganization(org.id)
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
    if (result && !result.ok) setNote(result.error ?? t('The window could not be opened.'))
    else setOpen(false)
  }

  const others = accounts.filter((a) => !a.current)
  // One organization is not a choice, so the section only exists once there are two: a menu
  // entry that names the only place you could be answers no question.
  const otherOrganizations = organizations.length > 1 ? organizations.filter((o) => !o.current) : []

  return (
    <div className={styles.account} ref={box}>
      <button
        type="button"
        className={styles.avatarBtn}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={
          role
            ? t('Account: {{email}} ({{role}})', { email: signedInAs, role: roleLabel(role) })
            : t('Account: {{email}}', { email: signedInAs })
        }
        title={role ? `${signedInAs} — ${roleLabel(role)}` : signedInAs}
        onClick={() => setOpen((o) => !o)}
      >
        <span className={styles.avatar}>{initial(signedInAs)}</span>
      </button>

      {open && (
        <div className={styles.accountMenu} role="menu">
          {/* Built from the same parts as the rows below, including the space the × occupies:
              the disc, the address and the role then line up in three columns down the whole menu
              rather than nearly. */}
          <div className={styles.accountWho}>
            <div className={`${styles.accountRow} ${styles.currentRow}`}>
              <span className={styles.avatar}>{initial(signedInAs)}</span>
              <span className={styles.accountRowText} title={signedInAs}>
                {signedInAs}
              </span>
              {role && <span className={styles.roleChip}>{roleLabel(role)}</span>}
            </div>
            <span className={`${styles.forgetBtn} ${styles.spacerOnly}`} aria-hidden="true">
              ×
            </span>
          </div>

          {otherOrganizations.length > 0 && (
            <div className={styles.orgSection}>
              <span className={styles.orgLabel}>{t('Switch organization')}</span>
              <ul className={styles.accountList}>
                {otherOrganizations.map((o) => (
                  <li key={o.id}>
                    <button
                      type="button"
                      role="menuitem"
                      className={styles.accountRow}
                      disabled={busy === o.id}
                      title={t('Work in {{name}}. Its flows, credentials and runs are its own.', { name: o.name })}
                      onClick={() => void switchOrganization(o)}
                    >
                      <span className={styles.avatar}>{initial(o.name)}</span>
                      <span className={styles.accountRowText}>{o.name}</span>
                      {o.role && <span className={styles.roleChip}>{roleLabel(o.role)}</span>}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {others.length > 0 && (
            <ul className={styles.accountList}>
              {others.map((a) => (
                <li key={a.userId}>
                  <button
                    type="button"
                    role="menuitem"
                    className={styles.accountRow}
                    disabled={busy === a.userId}
                    // The address is what tells two accounts apart, and a long one is elided in a
                    // menu this narrow.
                    title={`${a.email} — ${roleLabel(a.role)}`}
                    onClick={() => void switchTo(a)}
                  >
                    <span className={styles.avatar}>{initial(a.email)}</span>
                    <span className={styles.accountRowText}>{a.email}</span>
                    <span className={styles.roleChip}>{roleLabel(a.role)}</span>
                  </button>
                  <button
                    type="button"
                    className={styles.forgetBtn}
                    title={t('Forget {{email}} on this device. Signing in again brings it back.', {
                      email: a.email,
                    })}
                    aria-label={t('Forget {{email}}', { email: a.email })}
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
            {t('Add another account')}
            <small>{t('Signs this window out so you can sign in as somebody else. Both stay here.')}</small>
          </button>

          {shellAccounts && (
            <button
              type="button"
              role="menuitem"
              className={styles.accountAction}
              onClick={() => void openAnotherWindow()}
            >
              {t('Open a second window')}
              <small>{t('Its own sign-in, side by side — the only way to have two roles on screen at once.')}</small>
            </button>
          )}

          <div className={styles.accountFoot}>
            <button
              type="button"
              role="menuitem"
              className={`${styles.accountAction} ${styles.accountSignOut}`}
              onClick={onSignOut}
            >
              {t('Sign out')}
            </button>
          </div>

          {note && <p className={styles.accountNote}>{note}</p>}
        </div>
      )}
    </div>
  )
}
