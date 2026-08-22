import { useCallback, useEffect, useState } from 'react'
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
    if (!r.ok) setInstallError(r.error ?? 'The update could not be installed.')
  }

  const line = (s: ShellUpdateState): string => {
    if (!s.supported) return s.reason ?? 'This run cannot update itself.'
    switch (s.phase) {
      case 'idle':
        return 'Not checked yet in this session — the app also checks on its own every 4 hours.'
      case 'checking':
        return 'Checking GitHub Releases…'
      case 'up-to-date':
        return `You are on the latest version.${s.checkedAt ? ` Checked at ${clockTime(s.checkedAt)}.` : ''}`
      case 'downloading':
        return `Downloading ${s.available ?? 'the update'}… ${s.progressPercent ?? 0}%`
      case 'downloaded':
        return `Version ${s.available} is downloaded — installing reopens the app when it is done, and it installs by itself when you quit either way.`
      case 'error':
        return `The last check failed: ${s.error ?? 'unknown error'}`
    }
  }

  return (
    <div className={`${styles.crudForm} ${styles.lone}`}>
      <h3 className={styles.h4}>Application updates</h3>
      <p className={styles.status}>
        Current version: <b>{st ? st.version : '…'}</b>
        {st?.available && st.phase !== 'up-to-date' && <> · found {st.available}</>}
      </p>
      <p className={panels.hint} role="status">
        {st ? line(st) : <Spinner />}
      </p>
      <div className={styles.crudActions}>
        <button
          className={styles.saveBtn}
          onClick={() => void check()}
          disabled={!st?.supported || busy}
          title="Ask GitHub Releases whether a newer version exists; if one does, it downloads in the background"
        >
          {st?.phase === 'checking' ? 'Checking…' : 'Check for updates'}
        </button>
        <button
          className={styles.newBtn}
          onClick={() => void install()}
          disabled={st?.phase !== 'downloaded'}
          title={
            st?.phase === 'downloaded'
              ? 'Quit and run the installer now'
              : 'Available once an update has been downloaded'
          }
        >
          Restart &amp; update
        </button>
      </div>
      {installError && <p className={styles.errText}>{installError}</p>}
    </div>
  )
}
