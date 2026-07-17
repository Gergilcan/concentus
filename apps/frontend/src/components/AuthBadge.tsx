import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { AuthStatus } from '../api/types.ts'
import styles from './toolbar.module.scss'

const LABELS: Record<string, string> = {
  local: 'Local (subscription)',
  'api-key': 'API key',
  'auth-token': 'Auth token',
  none: 'Not signed in',
}

export function AuthBadge() {
  const [status, setStatus] = useState<AuthStatus | null>(null)

  useEffect(() => {
    let alive = true
    const load = () =>
      api
        .authStatus()
        .then((s) => {
          if (alive) setStatus(s)
        })
        .catch(() => {})
    load()
    const t = setInterval(load, 15000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [])

  if (!status) return null

  const cls = !status.authenticated
    ? styles.authNone
    : status.source === 'local'
      ? styles.authOk
      : styles.authKey

  return (
    <span className={`${styles.auth} ${cls}`} title={status.hint ?? status.detail ?? ''}>
      <span className={styles.authDot} />
      {LABELS[status.source] ?? status.source}
    </span>
  )
}
