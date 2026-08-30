import { Menu, Tray, app, nativeImage } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import type { RunnerSelf } from './backend-api'
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
 *
 * <p>The same logic puts the runner line here. A machine connected to a Concentus server executes
 * flows that were launched somewhere else, so the moment somebody wonders whether it is on the
 * line is a moment they have no window open — and the tray is what they have.
 */

let tray: Tray | null = null
/**
 * The last answer about the runner, so the menu can be built now and corrected when a fresh one
 * arrives. A context menu is a static template: it cannot wait on a request, and Electron gives
 * no event for "about to open" on every platform.
 */
let runner: RunnerSelf = { configured: false, connected: false, hubUrl: null, name: null, error: null }
let runnerPoll: NodeJS.Timeout | null = null
/**
 * Half a minute between refreshes while a server is configured: the line says whether a machine
 * that may have no window open is doing its job, and a loopback GET at that cadence is nothing.
 * No poll at all when no server is set — that is most installs, and their answer never changes.
 */
const RUNNER_POLL_MS = 30_000

export interface TrayActions {
  /** Show (or recreate) the main window. */
  openWindow: () => void
  /**
   * Reopen the first-run screen.
   *
   * <p>It used to be reachable exactly once, on a launch that decided it was due — which made
   * everything settled there permanent by accident: the database, the sign-in, and now how this
   * machine pays. A screen that answers "which of these two do you want" has to be reachable the
   * day somebody changes their mind.
   */
  openSetup: () => void
  /** What the backend says about its own runner agent — the tray's one line about the server. */
  runnerStatus: () => Promise<RunnerSelf>
  /** The server's interface, in a window with a session of its own. */
  openServer: () => void
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
  // The right-click that opens the menu also refreshes what it says, so the next open is current
  // even between polls. Windows and macOS only — Linux trays emit no clicks — which is what the
  // poll is for.
  tray.on('right-click', () => void refreshRunner(actions))
  runnerPoll = setInterval(() => {
    if (loadSettings().runner?.url) void refreshRunner(actions)
  }, RUNNER_POLL_MS)
  rebuildMenu(actions)
}

/** Rebuilt after every toggle, so the checkboxes always show the stored state. */
function rebuildMenu(actions: TrayActions): void {
  setMenu(actions)
  void refreshRunner(actions)
}

function setMenu(actions: TrayActions): void {
  if (!tray) return
  const settings = loadSettings()
  const items: Electron.MenuItemConstructorOptions[] = [
    // Short labels, and no sublabels: `sublabel` renders on macOS only, so on the platforms this
    // ships to it was invisible text making the menu template longer than the menu. The menu
    // hangs off the Concentus tray icon, so repeating the name in every item earns nothing.
    { label: 'Open', click: actions.openWindow },
    { label: 'Setup…', click: actions.openSetup },
    { type: 'separator' },
    // A line, not an action: it is read, and the way to change it is the setup screen above.
    { label: runnerLine(runner), enabled: false },
  ]
  // Only when there is a server to open: an item that opens nothing is a question nobody asked.
  if (settings.runner?.url) items.push({ label: 'Open server', click: actions.openServer })
  items.push(
    { type: 'separator' },
    {
      label: 'Run in background',
      type: 'checkbox',
      checked: !!settings.runInBackground,
      click: (item) => {
        saveSettings({ ...loadSettings(), runInBackground: item.checked })
        log.info(`Run in background: ${item.checked}`)
        rebuildMenu(actions)
      },
    },
    {
      label: 'Start at login',
      type: 'checkbox',
      checked: !!settings.startWithSystem,
      click: (item) => {
        saveSettings({ ...loadSettings(), startWithSystem: item.checked })
        applyStartWithSystem(item.checked)
        rebuildMenu(actions)
      },
    },
    { type: 'separator' },
    { label: 'Quit', click: actions.quit },
  )
  tray.setContextMenu(Menu.buildFromTemplate(items))
}

/** Asks the backend, and re-sets the menu only when the line it would show has changed. */
async function refreshRunner(actions: TrayActions): Promise<void> {
  const before = runnerLine(runner)
  try {
    runner = await actions.runnerStatus()
  } catch (err) {
    const url = loadSettings().runner?.url ?? null
    runner = {
      configured: !!url, connected: false, hubUrl: url, name: null,
      error: err instanceof Error ? err.message : String(err),
    }
  }
  if (runnerLine(runner) !== before) setMenu(actions)
}

/** The menu's sentence about the server, from what the backend last said. */
export function runnerLine(status: RunnerSelf): string {
  if (!status.configured) return 'Runner: not set up'
  if (status.connected) return `Runner: connected to ${hostOf(status.hubUrl)}`
  return `Runner: not connected${status.error ? ` — ${status.error}` : ''}`
}

/** The host alone: a menu line has room for "hub.example.com", not for a URL with a scheme. */
function hostOf(url: string | null): string {
  if (!url) return 'the server'
  try {
    return new URL(url).host
  } catch {
    return url
  }
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
  if (runnerPoll) clearInterval(runnerPoll)
  runnerPoll = null
  tray?.destroy()
  tray = null
}
