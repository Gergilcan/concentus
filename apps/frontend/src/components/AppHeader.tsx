import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import { AccountMenu } from './AccountMenu.tsx'
import { AuthBadge } from './AuthBadge.tsx'
import { UpdateBadge } from './UpdateBadge.tsx'
import { NAV, type View } from './AppNav.ts'
import styles from './appheader.module.scss'


interface Props {
  view: View
  onView: (v: View) => void
  /** The signed-in address, or null when authentication is switched off. */
  signedInAs?: string | null
  /** The organization this window is working in — given only when the account is in more than one. */
  organizationName?: string | null
  onSignOut?: () => void
}

export function AppHeader({ view, onView, signedInAs, organizationName, onSignOut }: Props) {
  const { t } = useTranslation()
  // Both fields off the same fetch: the chip only means something while the license is still
  // valid (mid-grace, counting down). Once grace runs out, valid flips to false and graceDaysLeft
  // clamps to 0 rather than going back to null — the backend's memory of "how many days were left
  // when this last mattered" — so valid has to gate the chip too, or a license that expired weeks
  // ago would show "0 days left" forever. That post-grace state is the Settings screen's problem
  // text to carry, not this header's.
  const [license, setLicense] = useState<{ valid: boolean; graceDaysLeft: number | null }>({
    valid: true,
    graceDaysLeft: null,
  })

  // One-shot, not a poller: the grace window moves in days, not seconds, and AuthBadge already
  // owns the header's one polling loop. Fetched once on mount, same as everything else in this
  // header answers "what should I show right now?" rather than "watch this for me".
  useEffect(() => {
    let alive = true
    api
      .getLicense()
      .then((s) => {
        if (alive) setLicense({ valid: s.valid, graceDaysLeft: s.graceDaysLeft })
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])

  return (
    <header className={styles.header}>
      <div className={styles.brand}>
        <span className={styles.logo}>⬡</span> Concentus
      </div>
      <nav className={styles.nav}>
        {NAV.map((item) => (
          <button
            key={item.id}
            className={view === item.id ? styles.active : ''}
            onClick={() => onView(item.id)}
          >
            {t(item.label)}
          </button>
        ))}
      </nav>
      <div className={styles.spacer} />
      {/* The corner is for what changes without being asked. A theme does not — it is a
          preference, set once, and it moved to Resources → Settings where preferences live. */}
      <UpdateBadge />
      {/* Next to the version chip AuthBadge renders — both answer "what is this installation
          on", and only one of them is usually worth a second look. */}
      {license.valid && license.graceDaysLeft != null && (
        <span
          className={styles.graceChip}
          title={t('Your license has expired. Paste a new token in Resources → Settings before the grace window runs out.')}
        >
          {t('License grace: {{n}} days left', { n: license.graceDaysLeft })}
        </span>
      )}
      <AuthBadge />
      {/* Which organization's flows these are. Only shown past one: with a single organization the
          name labels nothing, and with two it is the one fact the rest of the screen cannot say. */}
      {organizationName && (
        <span
          className={styles.orgChip}
          title={t('The organization this window is working in. Switch from the account menu.')}
        >
          {organizationName}
        </span>
      )}
      {signedInAs && onSignOut && <AccountMenu signedInAs={signedInAs} onSignOut={onSignOut} />}
    </header>
  )
}
