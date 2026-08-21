import { useCallback, useEffect, useRef, useState } from 'react'
import { shellBridge, type ShellUpdateState } from '../api/shell.ts'
import { clockTime } from '../utils/format.ts'
import styles from './appheader.module.scss'

/**
 * Whether there is a new version, in the corner where a person would look for one.
 *
 * <p>The updater is deliberately quiet — it downloads in the background and installs on quit — and
 * the panel that made it visible lived nine tabs deep in Resources, which is a place nobody visits
 * to find out whether their app is current. So the fact moves to the header: silent while there is
 * nothing to say, and marked when there is.
 *
 * <p><b>A dot, not a prompt.</b> An update dialog that interrupts work teaches people to dismiss
 * update dialogs; a mark that waits to be noticed does not spend anything. Clicking it explains
 * what state things are in and offers the one action that state has.
 *
 * <p>Absent entirely in a browser: there is no shell to update, and a control that cannot do
 * anything is worse than no control.
 */
export function UpdateBadge() {
  const bridge = shellBridge()
  const [st, setSt] = useState<ShellUpdateState | null>(null)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const box = useRef<HTMLDivElement>(null)

  const refresh = useCallback(async () => {
    const b = shellBridge()
    if (b) setSt(await b.updates.status())
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const busy = st?.phase === 'checking' || st?.phase === 'downloading'

  // Fast while something is happening, slow otherwise. The background updater checks every four
  // hours on its own; this only has to notice that it did.
  useEffect(() => {
    const every = busy ? 1000 : 60_000
    const t = setInterval(() => void refresh(), every)
    return () => clearInterval(t)
  }, [busy, refresh])

  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', away)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  if (!bridge || !st) return null

  const ready = st.phase === 'downloaded'
  const working = st.phase === 'downloading'

  const check = async () => {
    setError(null)
    setSt(await bridge.updates.check())
  }

  const install = async () => {
    setError(null)
    const r = await bridge.updates.install()
    // On success the app is already quitting — there is nothing left to render.
    if (!r.ok) setError(r.error ?? 'The update could not be installed.')
  }

  const line = (): string => {
    if (!st.supported) return st.reason ?? 'This build cannot update itself.'
    switch (st.phase) {
      case 'idle':
        return 'Not checked yet in this session. Concentus also checks on its own every 4 hours.'
      case 'checking':
        return 'Checking for a new version…'
      case 'up-to-date':
        return `You are on the latest version.${st.checkedAt ? ` Checked at ${clockTime(st.checkedAt)}.` : ''}`
      case 'downloading':
        return `Downloading ${st.available ?? 'the update'}… ${st.progressPercent ?? 0}%`
      case 'downloaded':
        return `Version ${st.available} is ready. Restarting applies it — or it installs by itself when you quit.`
      case 'error':
        return `The last check failed: ${st.error ?? 'unknown error'}`
    }
  }

  return (
    <div className={styles.updateWrap} ref={box}>
      <button
        type="button"
        className={styles.updateBtn}
        aria-label={ready ? `Version ${st.available} is ready to install` : 'Updates'}
        title={ready ? `Version ${st.available} is ready — restart to install` : 'Updates'}
        onClick={() => setOpen((v) => !v)}
      >
        {/* A download arrow into a tray: the shape every updater uses, so it needs no label. */}
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M12 3v10m0 0 4-4m-4 4-4-4" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" strokeLinecap="round" />
        </svg>
        {/* The mark. Only for "there is something to do about this" — a check in progress is not
            news, and a dot for it would train people to ignore the dot. */}
        {ready && <span className={styles.updateDot} aria-hidden="true" />}
        {working && <span className={styles.updateWorking} aria-hidden="true" />}
      </button>

      {open && (
        <div className={styles.updatePop} role="dialog" aria-label="Updates">
          <div className={styles.updateVersion}>Concentus {st.version}</div>
          <p className={styles.updateLine}>{line()}</p>
          {error && <p className={styles.updateError}>{error}</p>}
          <div className={styles.updateActions}>
            {ready ? (
              <button type="button" className={styles.updatePrimary} onClick={() => void install()}>
                Restart and install
              </button>
            ) : (
              <button
                type="button"
                className={styles.updatePrimary}
                disabled={!st.supported || busy}
                onClick={() => void check()}
              >
                {st.phase === 'checking' ? 'Checking…' : 'Check for updates'}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
