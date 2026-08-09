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
 * once. An update prompt that interrupts work teaches people to dismiss update prompts.
 *
 * <p>Where it does not apply, it says so and stands down rather than erroring on a loop:
 * development runs have no packaged app to update, and a .deb install is updated by the system's
 * package manager, not by us (electron-updater supports NSIS and AppImage).
 */
export function startAutoUpdates(): void {
  if (!app.isPackaged) {
    log.info('Auto-update: skipped (development run).')
    return
  }
  if (process.platform === 'linux' && !process.env.APPIMAGE) {
    log.info('Auto-update: skipped (.deb installs update through the package manager).')
    return
  }

  // Required lazily: electron-updater reads app paths at import time, and pulling it in at module
  // scope would also make the dev run pay for it.
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { autoUpdater } = require('electron-updater') as typeof import('electron-updater')

  autoUpdater.logger = {
    info: (m: unknown) => log.info(`Auto-update: ${String(m)}`),
    warn: (m: unknown) => log.warn(`Auto-update: ${String(m)}`),
    error: (m: unknown) => log.error(`Auto-update: ${String(m)}`),
    debug: () => {},
  }
  // Install silently when the app exits; nothing interrupts a session.
  autoUpdater.autoInstallOnAppQuit = true

  autoUpdater.on('update-downloaded', (info) => {
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
