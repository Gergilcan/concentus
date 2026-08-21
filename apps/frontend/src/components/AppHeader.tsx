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
      <AuthBadge />
      {signedInAs && onSignOut && <AccountMenu signedInAs={signedInAs} onSignOut={onSignOut} />}
    </header>
  )
}
