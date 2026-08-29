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

/**
 * Whether a version is a prerelease, by the same rule the release workflow uses to mark one.
 *
 * <p>A string test rather than semver's parser, deliberately: this has to agree with
 * `contains(github.ref_name, '-')` in release.yml, and two different definitions of "is this a
 * prerelease" is how a build ends up on a channel its own release page disagrees with.
 */
export function isPrerelease(version: string): boolean {
  return version.includes('-')
}

/** Whether a run can update itself at all, and if not, why — in the user's terms. */
export interface UpdateSupport {
  supported: boolean
  reason?: string
}

/**
 * The stand-down rule, on its own so it can be read (and tested) without an Electron process
 * behind it. Only two kinds of install can be updated by us — NSIS on Windows and the AppImage on
 * Linux — and a development run has no packaged app to replace in the first place.
 */
export function updateSupport(run: { packaged: boolean; platform: NodeJS.Platform; appImage: boolean }): UpdateSupport {
  if (!run.packaged) {
    return { supported: false, reason: 'Development run — only the installed app can update itself.' }
  }
  if (run.platform === 'linux' && !run.appImage) {
    return { supported: false, reason: 'This install updates through the system package manager, not the app.' }
  }
  return { supported: true }
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

/**
 * What the shell must finish before the installer may start.
 *
 * <p>Set by main.ts. The installer overwrites the bundled Java runtime and the backend jar, both
 * of which live inside the installation directory — so if the backend process is still holding
 * them the installer stops and says the application is still open. Which it is: quitting the
 * window is not the same as ending the process tree underneath it.
 */
let closeEverything: (() => Promise<void>) | null = null

export function onBeforeInstall(close: () => Promise<void>): void {
  closeEverything = close
}

/**
 * Install the downloaded update, without asking anything.
 *
 * <p><b>Silently.</b> The person already pressed the button that means "install this"; a wizard
 * afterwards asks them to agree to what they just chose, one dialog at a time, and its first page
 * is a licence they have already accepted once. NSIS takes {@code /S} even for an assisted
 * installer, and reuses the directory the previous install recorded — so there is nothing left to
 * ask.
 *
 * <p><b>And it comes back.</b> A silent install that does not relaunch looks like the app crashed
 * on being asked to update itself.
 *
 * <p>The backend is stopped, tree and all, before the installer is spawned. That wait is the whole
 * difference between an update that works and an installer sitting on "Concentus is still
 * running" behind a window that has already gone.
 */
export async function installUpdateNow(): Promise<{ ok: boolean; error?: string }> {
  if (!updater) {
    return { ok: false, error: state.reason ?? 'This run cannot update itself.' }
  }
  if (state.phase !== 'downloaded') {
    return { ok: false, error: 'No downloaded update to install yet — check for updates first.' }
  }
  log.info('Auto-update: install requested from the UI.')
  try {
    if (closeEverything) await closeEverything()
  } catch (err) {
    // Reported, not fatal: the installer's own wait-for-exit is the backstop, and refusing to
    // update because a shutdown step complained would be the worse outcome.
    log.warn(`Auto-update: shutting down before install: ${err instanceof Error ? err.message : String(err)}`)
  }
  updater.quitAndInstall(true, true)
  return { ok: true }
}

/** Hands over the updater to drive. Injectable so the tests can pass a fake in place of the real one. */
export type UpdaterLoader = () => import('electron-updater').AppUpdater

// Required lazily: electron-updater reads app paths at import time, and pulling it in at module
// scope would also make the dev run pay for it.
const loadElectronUpdater: UpdaterLoader = () =>
  (require('electron-updater') as typeof import('electron-updater')).autoUpdater

export function startAutoUpdates(load: UpdaterLoader = loadElectronUpdater): void {
  const support = updateSupport({
    packaged: app.isPackaged,
    platform: process.platform,
    appImage: !!process.env.APPIMAGE,
  })
  if (!support.supported) {
    state = { supported: false, phase: 'idle', reason: support.reason }
    log.info(`Auto-update: skipped (${support.reason}).`)
    return
  }

  const autoUpdater = load()
  updater = autoUpdater
  state = { supported: true, phase: 'idle' }

  autoUpdater.logger = {
    info: (m: unknown) => log.info(`Auto-update: ${String(m)}`),
    warn: (m: unknown) => log.warn(`Auto-update: ${String(m)}`),
    error: (m: unknown) => log.error(`Auto-update: ${String(m)}`),
    debug: () => {},
  }
  // Install when the app exits, so an update lands without ever interrupting a session. The
  // shell's own before-quit already stops the backend and waits for it, which is what makes this
  // path safe: electron-updater spawns the installer as the process is going away, and an
  // installer racing a backend that still holds the bundled runtime is the "application is still
  // open" dialog nobody is there to answer.
  autoUpdater.autoInstallOnAppQuit = true

  // Which releases this build is willing to move to. Two facts about electron-updater's GitHub
  // provider decide the whole design here, and neither is visible from the outside:
  //
  // 1. The channel is `semver.prerelease(version)[0]`. A DOTTED prerelease (`0.1.3-beta.1`) gives
  //    "beta" for every release in the train; a glued one (`0.1.0-rc15`) gives "rc15", so the next
  //    release reads as a different channel and is skipped. That is what silently broke updates
  //    for every prerelease shipped before this — release.yml refuses the glued form now.
  //
  // 2. The provider only considers a STABLE release when the running channel is null, "alpha" or
  //    "beta". Those three names are hard-coded in its matching loop. Any other identifier — "rc"
  //    among them — is treated as a custom channel, and a build on one never sees a final release
  //    at all. Pinning `channel = 'rc'` therefore did more than fix (1): it meant 0.1.2, a real
  //    stable release, could not be offered to anybody. Hence "beta" as the identifier, and no
  //    pin: the version string says which train this build is on, which is the only place that
  //    fact belongs.
  //
  // What that produces: a prerelease build follows both betas and finals, taking whichever is
  // newest, and a final build follows finals only. Somebody who installed a stable release did
  // not ask to be moved onto a prerelease, and moving them would be the kind of surprise that
  // makes people turn updates off.
  autoUpdater.allowPrerelease = isPrerelease(app.getVersion())

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
