import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import { AccountMenu } from './AccountMenu.tsx'
import { AuthBadge } from './AuthBadge.tsx'
import { UpdateBadge } from './UpdateBadge.tsx'
import styles from './appheader.module.scss'

export type View = 'flows' | 'studio' | 'resources' | 'usage'

export const NAV: Array<{ id: View; label: string }> = [
  { id: 'flows', label: 'Flows' },
  { id: 'studio', label: 'Studio' },
  { id: 'resources', label: 'Resources' },
  { id: 'usage', label: 'Usage' },
]

interface Props {
  view: View
  onView: (v: View) => void
  /** The signed-in address, or null when authentication is switched off. */
  signedInAs?: string | null
  onSignOut?: () => void
}

export function AppHeader({ view, onView, signedInAs, onSignOut }: Props) {
  const [graceDaysLeft, setGraceDaysLeft] = useState<number | null>(null)

  // One-shot, not a poller: the grace window moves in days, not seconds, and AuthBadge already
  // owns the header's one polling loop. Fetched once on mount, same as everything else in this
  // header answers "what should I show right now?" rather than "watch this for me".
  useEffect(() => {
    let alive = true
    api
      .getLicense()
      .then((s) => {
        if (alive) setGraceDaysLeft(s.graceDaysLeft)
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
            {item.label}
          </button>
        ))}
      </nav>
      <div className={styles.spacer} />
      {/* The corner is for what changes without being asked. A theme does not — it is a
          preference, set once, and it moved to Resources → Settings where preferences live. */}
      <UpdateBadge />
      {/* Next to the version chip AuthBadge renders — both answer "what is this installation
          on", and only one of them is usually worth a second look. */}
      {graceDaysLeft != null && (
        <span
          className={styles.graceChip}
          title="Your license has expired. Paste a new token in Resources → Settings before the grace window runs out."
        >
          License grace: {graceDaysLeft} days left
        </span>
      )}
      <AuthBadge />
      {signedInAs && onSignOut && <AccountMenu signedInAs={signedInAs} onSignOut={onSignOut} />}
    </header>
  )
}
