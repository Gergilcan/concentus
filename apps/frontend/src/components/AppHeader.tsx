import { AuthBadge } from './AuthBadge.tsx'
import styles from './appheader.module.scss'

export type View = 'flows' | 'studio' | 'resources'

interface Props {
  view: View
  onView: (v: View) => void
}

export function AppHeader({ view, onView }: Props) {
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
      </nav>
      <div className={styles.spacer} />
      <AuthBadge />
    </header>
  )
}
