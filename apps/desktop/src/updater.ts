import { Notification, app } from 'electron'
import { log } from './log'

/**
 * Keeps the installed app current from GitHub Releases.
 *
 * <p>Without this, every fix ships as "download 280 MB and reinstall by hand", which in practice
 * means most installs stay on whatever version they started with. The release workflow already
 * publishes the blockmaps electron-updater diffs against, so updates download only what changed.
 *
 * <p>The policy is deliberately quiet: download in the background, install on quit, and say so
 * once. An update prompt that interrupts work teaches people to dismiss update prompts. The UI's
 * Updates panel is the loud path for whoever wants to SEE it work: every phase below is kept in
 * an observable state it can poll, and "check now" / "install now" are exposed through IPC.
 *
 * <p>Where it does not apply, it says so and stands down rather than erroring on a loop:
 * development runs have no packaged app to update, and a .deb install is updated by the system's
 * package manager, not by us (electron-updater supports NSIS and AppImage).
 */

export type UpdatePhase = 'idle' | 'checking' | 'up-to-date' | 'downloading' | 'downloaded' | 'error'

export interface UpdateState {
  /** False when this run cannot update itself; `reason` says why in user terms. */
  supported: boolean
  reason?: string
  /** The running app's version — what an update would replace. */
  version: string
  phase: UpdatePhase
  /** The version found upstream, once one is. */
  available?: string
  /** Download progress, 0-100, while `phase` is `downloading`. */
  progressPercent?: number
  error?: string
  /** When the last check finished, either way. */
  checkedAt?: number
}

let state: Omit<UpdateState, 'version'> = { supported: false, phase: 'idle' }
let updater: import('electron-updater').AppUpdater | null = null

/** The current update state; the version is read fresh so it is never stale. */
export function updateStatus(): UpdateState {
  // A dev run reports package.json's version, which sits at a fixed 1.0.0 — release builds are
  // stamped from the git tag instead (see release.yml). Showing "1.0.0" here would look like a
  // real version and be wrong twice over; the splash's convention is followed instead.
  return { ...state, version: app.isPackaged ? app.getVersion() : 'development build' }
}

/**
 * A user-initiated check. The same call the background timer makes, but errors surface in the
 * returned state instead of only in the log — the Updates panel exists to answer "does this
 * actually work?", and a check that fails silently answers nothing.
 */
export async function checkForUpdatesNow(): Promise<UpdateState> {
  if (!updater) return updateStatus()
  state = { ...state, phase: 'checking', error: undefined }
  try {
    await updater.checkForUpdates()
  } catch (err) {
    state = {
      ...state,
      phase: 'error',
      error: err instanceof Error ? err.message : String(err),
      checkedAt: Date.now(),
    }
  }
  return updateStatus()
}

/** Quit and run the downloaded installer. Refused until one has actually been downloaded. */
export function installUpdateNow(): { ok: boolean; error?: string } {
  if (!updater) {
    return { ok: false, error: state.reason ?? 'This run cannot update itself.' }
  }
  if (state.phase !== 'downloaded') {
    return { ok: false, error: 'No downloaded update to install yet — check for updates first.' }
  }
  log.info('Auto-update: restart-and-install requested from the UI.')
  updater.quitAndInstall()
  return { ok: true }
}

export function startAutoUpdates(): void {
  if (!app.isPackaged) {
    state = {
      supported: false,
      phase: 'idle',
      reason: 'Development run — only the installed app can update itself.',
    }
    log.info('Auto-update: skipped (development run).')
    return
  }
  if (process.platform === 'linux' && !process.env.APPIMAGE) {
    state = {
      supported: false,
      phase: 'idle',
      reason: 'This install updates through the system package manager, not the app.',
    }
    log.info('Auto-update: skipped (.deb installs update through the package manager).')
    return
  }

  // Required lazily: electron-updater reads app paths at import time, and pulling it in at module
  // scope would also make the dev run pay for it.
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { autoUpdater } = require('electron-updater') as typeof import('electron-updater')
  updater = autoUpdater
  state = { supported: true, phase: 'idle' }

  autoUpdater.logger = {
    info: (m: unknown) => log.info(`Auto-update: ${String(m)}`),
    warn: (m: unknown) => log.warn(`Auto-update: ${String(m)}`),
    error: (m: unknown) => log.error(`Auto-update: ${String(m)}`),
    debug: () => {},
  }
  // Install silently when the app exits; nothing interrupts a session.
  autoUpdater.autoInstallOnAppQuit = true

  // Every phase lands in `state`, which is all the Updates panel sees. The updater's own events
  // are the single source of truth — the manual check sets no phase beyond 'checking' itself.
  autoUpdater.on('checking-for-update', () => {
    state = { ...state, phase: 'checking', error: undefined }
  })
  autoUpdater.on('update-available', (info) => {
    // autoDownload is on (the default): finding one and fetching it are one motion.
    state = { ...state, phase: 'downloading', available: info.version, progressPercent: 0 }
  })
  autoUpdater.on('update-not-available', () => {
    state = { ...state, phase: 'up-to-date', available: undefined, checkedAt: Date.now() }
  })
  autoUpdater.on('download-progress', (p) => {
    state = { ...state, phase: 'downloading', progressPercent: Math.round(p.percent) }
  })
  autoUpdater.on('error', (err) => {
    state = {
      ...state,
      phase: 'error',
      error: err instanceof Error ? err.message : String(err),
      checkedAt: Date.now(),
    }
  })

  autoUpdater.on('update-downloaded', (info) => {
    state = {
      ...state,
      phase: 'downloaded',
      available: info.version,
      progressPercent: 100,
      checkedAt: Date.now(),
    }
    log.info(`Auto-update: ${info.version} downloaded; installs on quit.`)
    try {
      new Notification({
        title: 'Update ready',
        body: `Concentus ${info.version} is downloaded and will install the next time you quit.`,
      }).show()
    } catch {
      /* a machine with no notification support still gets the update */
    }
  })

  const check = () => autoUpdater.checkForUpdates().catch((err) => {
    // Offline, rate-limited, or the release page mid-publish — all routine, none fatal.
    log.warn(`Auto-update check failed: ${err instanceof Error ? err.message : String(err)}`)
  })

  void check()
  // Long-lived tray sessions would otherwise only ever check once, at boot.
  setInterval(() => void check(), 4 * 60 * 60 * 1000)
}
