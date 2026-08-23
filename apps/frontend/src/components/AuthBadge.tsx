import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { AuthStatus } from '../api/types.ts'
import { AUTH_POLL_INTERVAL_MS } from '../constants.ts'
import styles from './toolbar.module.scss'

const LABELS: Record<string, string> = {
  local: 'Local (subscription)',
  'api-key': 'API key',
  'auth-token': 'Auth token',
  none: 'Not signed in',
}

export function AuthBadge() {
  const { t } = useTranslation()
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
    const t = setInterval(load, AUTH_POLL_INTERVAL_MS)
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
    <>
      {status.appVersion && (
        <span
          className={styles.version}
          title={t('Installed Concentus version. Updates download in the background and install when you quit.')}
        >
          v{status.appVersion}
        </span>
      )}
      <span className={`${styles.auth} ${cls}`} title={status.hint ?? status.detail ?? ''}>
        <span className={styles.authDot} />
        {LABELS[status.source] ? t(LABELS[status.source]) : status.source}
      </span>
    </>
  )
}
