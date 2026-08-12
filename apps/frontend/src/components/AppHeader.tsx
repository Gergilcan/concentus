import { useState } from 'react'
import { AuthBadge } from './AuthBadge.tsx'
import styles from './appheader.module.scss'

export type View = 'flows' | 'studio' | 'resources' | 'usage'

export type Theme = 'dark' | 'light' | 'contrast'

const THEMES: Array<{ id: Theme; icon: string; label: string }> = [
  { id: 'dark', icon: '🌙', label: 'Dark' },
  { id: 'light', icon: '☀️', label: 'Light' },
  { id: 'contrast', icon: '◐', label: 'High contrast' },
]

/**
 * Applies a theme by stamping data-theme on <html> — the same contract index.html's pre-paint
 * script uses, so the choice survives reloads without a flash. Dark is the stylesheet default
 * and needs no attribute.
 */
function applyTheme(theme: Theme) {
  if (theme === 'dark') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = theme
  localStorage.setItem('ui.theme', theme)
}

function currentTheme(): Theme {
  const t = localStorage.getItem('ui.theme')
  return t === 'light' || t === 'contrast' ? t : 'dark'
}

function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(currentTheme)
  const next = THEMES[(THEMES.findIndex((t) => t.id === theme) + 1) % THEMES.length]
  const active = THEMES.find((t) => t.id === theme) ?? THEMES[0]
  return (
    <button
      type="button"
      className={styles.themeBtn}
      title={`Theme: ${active.label} — click for ${next.label}`}
      aria-label={`Theme: ${active.label}. Switch to ${next.label}`}
      onClick={() => {
        applyTheme(next.id)
        setTheme(next.id)
      }}
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
      <ThemeSwitch />
      <AuthBadge />
      {signedInAs && (
        <button type="button" className={styles.signOut} onClick={onSignOut} title={signedInAs}>
          Sign out
        </button>
      )}
    </header>
  )
}
