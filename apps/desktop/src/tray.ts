import { Menu, Tray, app, nativeImage } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { loadSettings, saveSettings } from './settings'
import { log } from './log'

/**
 * The tray icon, and the two settings that make it matter.
 *
 * <p>The tray exists because of a structural gap: a flow triggered by cron or an incoming mail can
 * only fire while the backend is running, and the backend used to die with the window. "Keep
 * running in background" turns closing the window into hiding it, and "Start with system" brings
 * the backend up (hidden) at sign-in — together they turn "flows run while the app is open" into
 * "flows run while the machine is on", which is what anyone scheduling a 7am flow actually meant.
 *
 * <p>Both are opt-in and live in the tray menu, because the tray is the thing that represents the
 * app once the window is gone — the setting belongs where its effect is visible.
 */

let tray: Tray | null = null

export interface TrayActions {
  /** Show (or recreate) the main window. */
  openWindow: () => void
  /** Really quit, backend included. */
  quit: () => void
}

export function createTray(actions: TrayActions): void {
  if (tray) return
  const icon = nativeImage.createFromPath(path.join(__dirname, '..', 'build', 'icon.png'))
  // The tray renders at 16-24px; resizing here avoids a blurry auto-downscale on Linux.
  tray = new Tray(icon.resize({ width: 16, height: 16 }))
  tray.setToolTip('Concentus')
  tray.on('click', actions.openWindow)
  rebuildMenu(actions)
}

/** Rebuilt after every toggle, so the checkboxes always show the stored state. */
function rebuildMenu(actions: TrayActions): void {
  if (!tray) return
  const settings = loadSettings()
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Concentus', click: actions.openWindow },
    { type: 'separator' },
    {
      label: 'Keep running in background',
      sublabel: 'Closing the window keeps triggers firing',
      type: 'checkbox',
      checked: !!settings.runInBackground,
      click: (item) => {
        saveSettings({ ...loadSettings(), runInBackground: item.checked })
        log.info(`Run in background: ${item.checked}`)
        rebuildMenu(actions)
      },
    },
    {
      label: 'Start with system',
      sublabel: 'Launches hidden at sign-in',
      type: 'checkbox',
      checked: !!settings.startWithSystem,
      click: (item) => {
        saveSettings({ ...loadSettings(), startWithSystem: item.checked })
        applyStartWithSystem(item.checked)
        rebuildMenu(actions)
      },
    },
    { type: 'separator' },
    { label: 'Quit Concentus', click: actions.quit },
  ]))
}

/**
 * Registers (or removes) the login item.
 *
 * <p>Windows has a real API for this. Linux has a convention instead — a .desktop file in
 * ~/.config/autostart — which Electron does not wrap, so it is written by hand. Both launch with
 * {@code --hidden}: an autostarted app that opens a window over whatever the user was signing in
 * to do is exactly the behaviour that gets it removed from autostart.
 */
export function applyStartWithSystem(enabled: boolean): void {
  try {
    if (process.platform === 'linux') {
      const dir = path.join(app.getPath('home'), '.config', 'autostart')
      const file = path.join(dir, 'concentus.desktop')
      if (!enabled) {
        fs.rmSync(file, { force: true })
        log.info('Autostart entry removed.')
        return
      }
      fs.mkdirSync(dir, { recursive: true })
      // process.execPath is the installed binary in a packaged app. In development it is the
      // electron binary, which would not survive a repo move — acceptable for a developer machine,
      // and the packaged path is the one that matters.
      fs.writeFileSync(file, [
        '[Desktop Entry]',
        'Type=Application',
        'Name=Concentus',
        `Exec="${process.execPath}" --hidden`,
        'X-GNOME-Autostart-enabled=true',
        '',
      ].join('\n'))
      log.info(`Autostart entry written: ${file}`)
      return
    }
    app.setLoginItemSettings({ openAtLogin: enabled, args: ['--hidden'] })
    log.info(`Login item ${enabled ? 'registered' : 'removed'}.`)
  } catch (err) {
    log.error('Could not update the autostart setting', err)
  }
}

export function destroyTray(): void {
  tray?.destroy()
  tray = null
}
