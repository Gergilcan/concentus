import { useEffect, useState } from 'react'

/**
 * The theme, in one place.
 *
 * It used to live inside the header's switch, which was fine while the switch was the only way to
 * change it. The command palette is a second way, and two copies of "what the theme is" drift the
 * moment one of them changes it — the header would keep offering "switch to Light" after the
 * palette had already switched to Light. So the value lives here and both read it.
 */

export type Theme = 'dark' | 'light' | 'contrast'

export const THEMES: Array<{ id: Theme; icon: string; label: string }> = [
  { id: 'dark', icon: '🌙', label: 'Dark' },
  { id: 'light', icon: '☀️', label: 'Light' },
  { id: 'contrast', icon: '◐', label: 'High contrast' },
]

const STORAGE_KEY = 'ui.theme'
const listeners = new Set<(t: Theme) => void>()

export function currentTheme(): Theme {
  const t = localStorage.getItem(STORAGE_KEY)
  return t === 'light' || t === 'contrast' ? t : 'dark'
}

/**
 * Applies a theme by stamping data-theme on <html> — the same contract index.html's pre-paint
 * script uses, so the choice survives reloads without a flash. Dark is the stylesheet default and
 * needs no attribute.
 */
export function setTheme(theme: Theme) {
  if (theme === 'dark') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = theme
  localStorage.setItem(STORAGE_KEY, theme)
  listeners.forEach((l) => l(theme))
}

/** The next theme in the cycle — what a single toggle lands on. */
export function nextTheme(from: Theme = currentTheme()): Theme {
  return THEMES[(THEMES.findIndex((t) => t.id === from) + 1) % THEMES.length].id
}

/** The current theme, re-rendering whoever asks when anything changes it. */
export function useTheme(): Theme {
  const [theme, set] = useState<Theme>(currentTheme)
  useEffect(() => {
    listeners.add(set)
    return () => {
      listeners.delete(set)
    }
  }, [])
  return theme
}
