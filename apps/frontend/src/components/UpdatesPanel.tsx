import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { shellBridge, type ShellUpdateState } from '../api/shell.ts'
import { clockTime } from '../utils/format.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * The auto-updater, made visible: current version, a manual check, and restart-and-install.
 *
 * The updater itself is deliberately quiet (download in the background, install on quit), which
 * also makes it unverifiable from the outside — this panel exists so someone can press the
 * button and watch each phase actually happen. Rendered only inside the desktop shell; the
 * bridge is absent in a browser, and the Updates tab with it.
 */
export function UpdatesPanel() {
  const { t } = useTranslation()
  const bridge = shellBridge()
  const [st, setSt] = useState<ShellUpdateState | null>(null)
  const [installError, setInstallError] = useState<string | null>(null)
  const busy = st?.phase === 'checking' || st?.phase === 'downloading'

  const refresh = useCallback(async () => {
    const b = shellBridge()
    if (b) setSt(await b.updates.status())
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  // A check or a download is a moving target — poll while one is in flight, stand down after.
  useEffect(() => {
    if (!busy) return
    const t = setInterval(() => void refresh(), 1000)
    return () => clearInterval(t)
  }, [busy, refresh])

  if (!bridge) return null

  const check = async () => {
    setInstallError(null)
    setSt(await bridge.updates.check())
  }

  const install = async () => {
    setInstallError(null)
    const r = await bridge.updates.install()
    // On success the app is already quitting — there is nothing left to render.
    if (!r.ok) setInstallError(r.error ?? t('The update could not be installed.'))
  }

  const line = (s: ShellUpdateState): string => {
    if (!s.supported) return s.reason ?? t('This run cannot update itself.')
    switch (s.phase) {
      case 'idle':
        return t('Not checked yet in this session — the app also checks on its own every 4 hours.')
      case 'checking':
        return t('Checking GitHub Releases…')
      case 'up-to-date':
        return s.checkedAt
          ? t('You are on the latest version. Checked at {{time}}.', { time: clockTime(s.checkedAt) })
          : t('You are on the latest version.')
      case 'downloading':
        return t('Downloading {{what}}… {{percent}}%', {
          what: s.available ?? t('the update'),
          percent: s.progressPercent ?? 0,
        })
      case 'downloaded':
        return t(
          'Version {{version}} is downloaded — installing reopens the app when it is done, and it installs by itself when you quit either way.',
          { version: s.available },
        )
      case 'error':
        return t('The last check failed: {{error}}', { error: s.error ?? t('unknown error') })
    }
  }

  return (
    <div className={`${styles.crudForm} ${styles.lone}`}>
      <h3 className={styles.h4}>{t('Application updates')}</h3>
      <p className={styles.status}>
        {t('Current version:')} <b>{st ? st.version : '…'}</b>
        {st?.available && st.phase !== 'up-to-date' && <> · {t('found {{version}}', { version: st.available })}</>}
      </p>
      <p className={panels.hint} role="status">
        {st ? line(st) : <Spinner />}
      </p>
      <div className={styles.crudActions}>
        <button
          className={styles.saveBtn}
          onClick={() => void check()}
          disabled={!st?.supported || busy}
          title={t('Ask GitHub Releases whether a newer version exists; if one does, it downloads in the background')}
        >
          {st?.phase === 'checking' ? t('Checking…') : t('Check for updates')}
        </button>
        <button
          className={styles.newBtn}
          onClick={() => void install()}
          disabled={st?.phase !== 'downloaded'}
          title={
            st?.phase === 'downloaded'
              ? t('Quit and run the installer now')
              : t('Available once an update has been downloaded')
          }
        >
          {t('Restart & update')}
        </button>
      </div>
      {installError && <p className={styles.errText}>{installError}</p>}
    </div>
  )
}
