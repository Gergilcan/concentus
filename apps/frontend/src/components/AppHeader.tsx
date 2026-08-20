import { AccountMenu } from './AccountMenu.tsx'
import { AuthBadge } from './AuthBadge.tsx'
import { nextTheme, setTheme, THEMES, useTheme } from '../utils/theme.ts'
import styles from './appheader.module.scss'

export type View = 'flows' | 'studio' | 'resources' | 'usage'

export const NAV: Array<{ id: View; label: string }> = [
  { id: 'flows', label: 'Flows' },
  { id: 'studio', label: 'Studio' },
  { id: 'resources', label: 'Resources' },
  { id: 'usage', label: 'Usage' },
]

function ThemeSwitch() {
  // The theme lives in utils/theme so the command palette can change it too and this button
  // still knows — two copies of "what the theme is" drift the moment one of them changes it.
  const theme = useTheme()
  const next = THEMES.find((t) => t.id === nextTheme(theme)) ?? THEMES[0]
  const active = THEMES.find((t) => t.id === theme) ?? THEMES[0]
  return (
    <button
      type="button"
      className={styles.themeBtn}
      title={`Theme: ${active.label} — click for ${next.label}`}
      aria-label={`Theme: ${active.label}. Switch to ${next.label}`}
      onClick={() => setTheme(next.id)}
    >
      {active.icon}
    </button>
  )
}

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
      <ThemeSwitch />
      <AuthBadge />
      {signedInAs && onSignOut && <AccountMenu signedInAs={signedInAs} onSignOut={onSignOut} />}
    </header>
  )
}
