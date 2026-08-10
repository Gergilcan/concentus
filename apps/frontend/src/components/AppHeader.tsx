import { AuthBadge } from './AuthBadge.tsx'
import styles from './appheader.module.scss'

export type View = 'flows' | 'studio' | 'resources' | 'usage'

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
        <button className={view === 'flows' ? styles.active : ''} onClick={() => onView('flows')}>
          Flows
        </button>
        <button className={view === 'studio' ? styles.active : ''} onClick={() => onView('studio')}>
          Studio
        </button>
        <button
          className={view === 'resources' ? styles.active : ''}
          onClick={() => onView('resources')}
        >
          Resources
        </button>
        <button className={view === 'usage' ? styles.active : ''} onClick={() => onView('usage')}>
          Usage
        </button>
      </nav>
      <div className={styles.spacer} />
      <AuthBadge />
      {signedInAs && (
        <button type="button" className={styles.signOut} onClick={onSignOut} title={signedInAs}>
          Sign out
        </button>
      )}
    </header>
  )
}
