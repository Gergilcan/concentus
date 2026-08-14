import { app, BrowserWindow, Notification, dialog, ipcMain, Menu, shell } from 'electron'
import * as path from 'node:path'
import { RunningBackend, backendLogTail, startBackend, stopBackend } from './backend'
import { StorageDraft, backendApi } from './backend-api'
import { resolveClaudeCli } from './claude-cli'
import { installClaude, installCommand, openLoginTerminal } from './claude-install'
import { failurePage } from './failure-page'
import { OnboardingState, StorageState, onboardingPage } from './onboarding-page'
import { backendLogFile, isPackaged, shellLogFile } from './paths'
import { installRuntime, isRuntimeId, runtimeInstallPlan } from './runtime-install'
import { Splash, noSplash, showSplash } from './splash'
import { resetRunNotifications, startRunNotifications } from './run-notifications'
import { loadSettings, saveSettings } from './settings'
import { applyStartWithSystem, createTray } from './tray'
import { checkForUpdatesNow, installUpdateNow, startAutoUpdates, updateStatus } from './updater'
import { log } from './log'

/**
 * Application lifecycle.
 *
 * The shape is: start the backend, then show its UI. Nothing is displayed until the backend is
 * actually ready, because a window pointing at a port with nothing behind it is a blank page the
 * user has to interpret.
 */

/**
 * Set before anything reads a path. Electron derives `userData` from the application name, which
 * otherwise comes from this package's `name` field — giving "desktop", an anonymous folder in
 * AppData that means nothing to the user and collides with any other app that made the same
 * mistake. It has to happen at module scope: the first call to app.getPath('userData') fixes the
 * answer, and the logger makes that call as soon as anything is logged.
 */
app.setName('Concentus')
// Windows identifies a taskbar button by its AppUserModelID, not by the window. Without one set
// here, Electron derives it from the running executable — electron.exe when unpackaged — so the
// taskbar shows Electron's icon and name however the window is decorated. It must match the
// appId in electron-builder.yml, or a packaged build gets a second, unpinnable identity.
if (process.platform === 'win32') app.setAppUserModelId('com.concentus.desktop')

let backend: RunningBackend | null = null
let mainWindow: BrowserWindow | null = null
let failureWindow: BrowserWindow | null = null
let onboardingWindow: BrowserWindow | null = null
/** The splash for the launch in progress; noSplash outside one, so callers never null-check. */
let splash: Splash = noSplash
/** Set once quitting has begun, so the backend is stopped exactly once. */
let quitting = false
/**
 * The CLI path the running backend was started with.
 *
 * Kept because the backend reads `local.claude-command` once, at startup. A login appearing later
 * is picked up on its own — the backend checks the filesystem each time it is asked — but a CLI
 * that was not found when it spawned is baked in as "not configured", and only a restart fixes it.
 */
let backendClaudeCommand: string | null = null
/**
 * Launched by the login item, which passes --hidden: the backend comes up and the tray appears,
 * but no window opens over whatever the user signed in to do.
 */
const startHidden = process.argv.includes('--hidden')

/**
 * Two copies of this app would fight over the port, the data directory and the run state in it.
 * The second one hands focus to the first and leaves.
 */
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    // With background mode the first instance may be a tray icon with no window at all, and
    // launching the app again is the most natural way to ask for one back.
    openMainWindow()
  })
  void main()
}

async function main(): Promise<void> {
  await app.whenReady()
  registerIpc()
  // No application menu. The UI has its own navigation, and a File/View/Edit bar above it is a
  // second, emptier navigation that belongs to a different application — the window should look
  // like Concentus, not like a browser someone put Concentus inside. Removing it costs nothing
  // functionally: Chromium handles clipboard and text-editing shortcuts in web content itself,
  // and the one menu item that did something specific — opening the logs folder — is still on the
  // failure window, which is when it is actually needed.
  Menu.setApplicationMenu(null)
  createTray({ openWindow: openMainWindow, quit: () => { quitting = true; app.quit() } })
  // Re-assert on every start: an update can move the installed binary, and a login item pointing
  // at last version's path launches nothing.
  if (loadSettings().startWithSystem) applyStartWithSystem(true)
  startAutoUpdates()
  await launch()
  startRunNotifications({
    port: () => backend?.port ?? null,
    onClick: openMainWindow,
    isWindowFocused: () => !!mainWindow && !mainWindow.isDestroyed() && mainWindow.isFocused(),
  })

  app.on('window-all-closed', () => {
    // In background mode the tray is the app now; everywhere else, no window means no app —
    // leaving a backend running with no way to see it would be a service nobody asked for.
    if (!loadSettings().runInBackground || quitting) app.quit()
  })

  // Stop the backend before the process goes away, and hold up the quit until it has.
  app.on('before-quit', (event) => {
    if (quitting) return
    quitting = true
    event.preventDefault()
    void stopBackend(backend).finally(() => {
      backend = null
      app.quit()
    })
  })
}

/** Start the backend, then show the first-run page, the app, or the failure page. */
async function launch(): Promise<void> {
  // The splash goes up before any work starts — its whole job is to make the seconds before the
  // first real window feel accounted for. A --hidden launch shows nothing on purpose: the user
  // asked for a tray icon, not a screen announcing one.
  if (!startHidden) {
    splash = showSplash(isPackaged() ? `Version ${app.getVersion()}` : 'Development build')
  }
  try {
    backend = await startBackend((percent, label) => splash.advance(percent, label))
    backendClaudeCommand = (await claudeState()).command
    if (startHidden) {
      // An autostarted session: the tray is the whole UI until the user asks for more. The wizard,
      // if due, waits for a launch the user actually initiated.
      log.info('Started hidden into the tray.')
    } else if (await shouldOnboard()) {
      splash.advance(92, 'Opening setup…')
      showOnboardingWindow()
    } else {
      await showMainWindow(backend.port)
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    log.error('Backend failed to start', err)
    showFailureWindow(message)
  }
}

/** Current sign-in state, as the first-run page needs it. */
async function claudeState(): Promise<OnboardingState> {
  const settings = loadSettings()
  const cli = await resolveClaudeCli(settings.claudeCommand)
  return {
    command: cli.command,
    loggedIn: cli.loggedIn,
    // An API key means flows run in Anthropic's hosted sandbox, where a local CLI is irrelevant.
    cloudConfigured: !!(process.env.ANTHROPIC_API_KEY ?? '').trim(),
  }
}

/**
 * Whether to show the first-run wizard.
 *
 * <p>Always on a genuine first run, because the database question has to be asked once and there
 * is no other moment to ask it — a choice that only surfaces after something breaks is not a
 * choice. Afterwards it returns only when flows could not actually run: no usable local sign-in
 * and no API key to fall back on. Anything else would be nagging someone whose setup is fine.
 */
async function shouldOnboard(): Promise<boolean> {
  const settings = loadSettings()
  if (!settings.wizardCompleted) return true
  if (settings.skipClaudeCheck) return false
  const state = await claudeState()
  if (state.cloudConfigured) return false
  return !state.command || !state.loggedIn
}

/** Bring the app to the front, whatever state it is in — hidden, minimised, or not yet created. */
function openMainWindow(): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
    return
  }
  const win = failureWindow ?? onboardingWindow
  if (win) {
    win.show()
    win.focus()
    return
  }
  if (backend) void showMainWindow(backend.port)
}

/**
 * Where the main window's UI comes from.
 *
 * Packaged, there is one answer: the backend, which serves the UI baked into its jar. In the repo
 * there are two, and which one you get should match what you are doing: if the Vite dev server is
 * running, the window loads IT — frontend edits then hot-reload inside the real desktop app,
 * because Vite proxies /api and /ws to the very backend this shell started. Without a dev server
 * the window falls back to the jar's baked UI, exactly as before — which is also the honest state:
 * that UI is as old as the last build.
 *
 * The probe is a live check rather than a flag so `electron .` needs no ceremony: start Vite and
 * relaunch to get hot reload; don't, and the app still opens.
 */
async function uiSource(port: number): Promise<string> {
  const baked = `http://127.0.0.1:${port}`
  if (isPackaged()) return baked
  const dev = process.env.CONCENTUS_DEV_UI ?? 'http://localhost:5173'
  try {
    const probe = await fetch(dev, { signal: AbortSignal.timeout(500) })
    if (probe.ok) {
      log.info(`Loading the UI from the Vite dev server at ${dev} — frontend edits hot-reload. ` +
        `Its proxy must point at this backend (port ${port}; vite.config.ts targets 8734).`)
      return dev
    }
  } catch { /* no dev server — the jar's baked UI it is */ }
  return baked
}

async function showMainWindow(port: number): Promise<void> {
  failureWindow?.close()
  failureWindow = null

  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    show: false,
    title: 'Concentus',
    icon: appIcon(),
    // Belt and braces alongside setApplicationMenu(null): this also stops the bar reappearing when
    // Alt is pressed, which is the Windows default for a hidden menu.
    autoHideMenuBar: true,
    backgroundColor: '#0b0e14',
    webPreferences: {
      // No node access, and the narrowest possible preload: this window loads the application's
      // own UI over HTTP and should stay worth no more than a tab if a page in it is ever
      // compromised. The one exception is the updates bridge — three fixed, argument-less
      // actions so the UI can show and drive the auto-updater (see preload-main.ts).
      preload: path.join(__dirname, 'preload-main.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  // Ready-to-show rather than showing immediately, to avoid a flash of empty window.
  mainWindow.once('ready-to-show', () => {
    splash.advance(100, 'Ready')
    mainWindow?.show()
    // After the window is up, not before, so no frame passes with nothing on screen.
    splash.close()
    splash = noSplash
  })
  // Close-to-tray. Read at close time, not window-creation time, so flipping the tray checkbox
  // applies to the window that is already open.
  mainWindow.on('close', (event) => {
    if (quitting || !loadSettings().runInBackground) return
    event.preventDefault()
    mainWindow?.hide()
    const settings = loadSettings()
    if (!settings.trayTipShown) {
      // Said exactly once: an app that visibly closed but is still running is the surprise this
      // explains — repeating it every close would be nagging.
      saveSettings({ ...settings, trayTipShown: true })
      try {
        new Notification({
          title: 'Concentus is still running',
          body: 'Triggers keep firing. Quit from the tray icon.',
        }).show()
      } catch { /* no notification support — the tray icon still tells the story */ }
    }
  })
  mainWindow.on('closed', () => {
    mainWindow = null
  })

  const url = await uiSource(port)
  openExternalLinksInBrowser(mainWindow, new URL(url).origin)
  // The last stretch of the splash's progress: the interface loading into this hidden window.
  splash.advance(88, 'Loading the interface…')
  mainWindow.webContents.once('dom-ready', () => splash.advance(95, 'Rendering…'))
  void mainWindow.loadURL(url)
}

/**
 * Anything not served by our own UI origin opens in the real browser.
 *
 * This matters most for MCP OAuth: the authorization page belongs in a browser the user recognises
 * and where their existing session already is, not in an app window with no address bar. The
 * provider then redirects to http://127.0.0.1:<port>/api/mcp/oauth/callback, which the backend
 * handles directly — so the flow completes even though it happened outside this window.
 */
function openExternalLinksInBrowser(win: BrowserWindow, origin: string): void {

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(origin)) {
      void shell.openExternal(url)
      return { action: 'deny' }
    }
    return { action: 'allow' }
  })

  win.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith(origin)) {
      event.preventDefault()
      void shell.openExternal(url)
    }
  })
}

function showOnboardingWindow(): void {
  onboardingWindow = new BrowserWindow({
    width: 720,
    height: 840,
    minWidth: 520,
    minHeight: 420,
    // Resizable on purpose. The content is a fixed amount of prose and it very nearly fits, but
    // "very nearly" depends on the platform's font and the user's display scaling — and a window
    // that cannot grow turns a few pixels of overflow into a button nobody can reach.
    title: 'Welcome to Concentus',
    icon: appIcon(),
    autoHideMenuBar: true,
    backgroundColor: '#0b0e14',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  // Closing this window with the X means the same as "continue" — the app is usable either way,
  // and quitting instead would make a prompt behave like a gate.
  onboardingWindow.on('closed', () => {
    onboardingWindow = null
    if (!mainWindow && !quitting && backend) void showMainWindow(backend.port)
  })

  void (async () => {
    const claude = await claudeState()
    // The storage half comes from the backend, which is already running by the time this window
    // opens. If it cannot be asked, the page still opens on the embedded default rather than not
    // at all — the wizard is how someone recovers from a bad setting, so it must survive one.
    let storage: StorageState = {
      mode: 'embedded', url: '', username: '', hasPassword: false, activeMode: 'embedded',
    }
    try {
      if (backend) storage = await backendApi.getStorage(backend.port)
    } catch (err) {
      log.warn(`Could not read the storage settings: ${err instanceof Error ? err.message : String(err)}`)
    }
    const html = onboardingPage(claude, storage)
    try {
      await onboardingWindow?.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
    } finally {
      // Only now: the wizard window opens instantly but paints its content after this async block,
      // and dropping the splash on creation would leave a blank window as the handover.
      splash.close()
      splash = noSplash
    }
  })()
}

function showFailureWindow(message: string): void {
  // A progress bar over a failure would be a lie; the failure window is the whole screen now.
  splash.close()
  splash = noSplash
  failureWindow = new BrowserWindow({
    width: 900,
    height: 760,
    title: 'Concentus — startup failed',
    icon: appIcon(),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  failureWindow.on('closed', () => {
    failureWindow = null
  })

  const html = failurePage(message, backendLogTail(), backendLogFile())
  void failureWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
}

function registerIpc(): void {
  // The main window's updates bridge (preload-main.ts). Status is a poll target — the panel asks
  // while a check or download is in flight — so it must stay cheap and side-effect free.
  ipcMain.handle('updates:status', () => updateStatus())
  ipcMain.handle('updates:check', () => checkForUpdatesNow())
  ipcMain.handle('updates:install', () => installUpdateNow())

  // Runtime installs for MCP servers. The id is validated here rather than trusted: the renderer
  // is a page loaded over HTTP, and the whole safety of this path is that only the six hardcoded
  // commands in runtime-install.ts can ever run.
  ipcMain.handle('runtimes:plan', (_event, runtime: unknown) => {
    if (!isRuntimeId(runtime)) return { runtime, command: null, reason: 'Unknown runtime.' }
    return runtimeInstallPlan(runtime)
  })

  ipcMain.handle('runtimes:install', async (event, runtime: unknown) => {
    if (!isRuntimeId(runtime)) {
      log.warn(`Refused a runtime install for an unknown id: ${String(runtime)}`)
      return { ok: false, detail: 'Unknown runtime.' }
    }
    return installRuntime(runtime, (line) => {
      // Streamed to the window that asked, so a long install shows progress rather than freezing.
      if (!event.sender.isDestroyed()) event.sender.send('runtimes:install-output', line)
    })
  })

  ipcMain.on('failure:retry', () => {
    failureWindow?.close()
    failureWindow = null
    void launch()
  })
  ipcMain.on('failure:open-logs', () => {
    // showItemInFolder rather than openPath on the directory: it highlights the log itself, which
    // is the file the user was told to look at.
    shell.showItemInFolder(shellLogFile())
  })
  ipcMain.on('failure:quit', () => app.quit())

  ipcMain.handle('onboarding:recheck', async () => {
    const state = await claudeState()
    await adoptClaudeCommand(state.command)
    return state
  })

  ipcMain.handle('onboarding:install-command', () => installCommand())

  ipcMain.handle('onboarding:install', async (event) => {
    const result = await installClaude((line) => {
      // Streamed to the window that asked, so a long install shows progress rather than freezing.
      if (!event.sender.isDestroyed()) event.sender.send('onboarding:install-output', line)
    })
    if (!result.ok) return { ...result, state: await claudeState() }

    // The installer puts the binary where discovery already looks, so the same re-check that
    // follows a manual install applies here — including restarting the backend, which reads the
    // CLI path once at startup.
    const state = await claudeState()
    await adoptClaudeCommand(state.command)
    // Fresh install, no login yet: open the sign-in right away, in a terminal already running
    // the just-installed binary. The wizard polls until the login lands.
    let loginOpened = false
    if (state.command && !state.loggedIn) {
      loginOpened = (await openLoginTerminal(state.command)).ok
    }
    return { ...result, state, loginOpened }
  })

  ipcMain.handle('onboarding:open-login', async () => {
    const state = await claudeState()
    if (!state.command) return { ok: false, detail: 'The claude CLI was not found.', state }
    const opened = await openLoginTerminal(state.command)
    return { ...opened, state }
  })

  ipcMain.handle('onboarding:locate', async () => {
    if (!onboardingWindow) return null
    // The escape hatch for the machine where discovery fails — a CLI installed somewhere no
    // convention covers, or a version manager the login shell exposes and nothing else does.
    const { canceled, filePaths } = await dialog.showOpenDialog(onboardingWindow, {
      title: 'Locate the claude CLI',
      properties: ['openFile'],
      filters: process.platform === 'win32'
        ? [{ name: 'Executables', extensions: ['exe', 'cmd', 'bat'] }]
        : [],
    })
    if (canceled || filePaths.length === 0) return null

    const chosen = filePaths[0]
    saveSettings({ ...loadSettings(), claudeCommand: chosen })
    log.info(`claude CLI set by hand: ${chosen}`)

    const state = await claudeState()
    await adoptClaudeCommand(state.command)
    return state
  })

  ipcMain.handle('onboarding:storage-get', async () => {
    if (!backend) throw new Error('The backend is not running.')
    return backendApi.getStorage(backend.port)
  })

  ipcMain.handle('onboarding:storage-test', async (_event, draft: StorageDraft) => {
    if (!backend) throw new Error('The backend is not running.')
    return backendApi.testStorage(backend.port, draft)
  })

  ipcMain.handle('onboarding:storage-save', async (_event, draft: StorageDraft) => {
    if (!backend) throw new Error('The backend is not running.')
    const saved = await backendApi.saveStorage(backend.port, draft)
    // Applied immediately rather than at the next launch, which is the difference between a wizard
    // and a form: the rest of this first run — and the app the user is about to open — should be on
    // the database they just chose, not on the one that happened to start first.
    if (saved.restartRequired) await restartBackend()
    return saved
  })

  ipcMain.on('onboarding:finish', (_event, dontAskAgain: boolean) => {
    // Recorded on the way out, not on the way in: a wizard closed halfway through has not asked
    // the database question, and should ask it again next time.
    saveSettings({
      ...loadSettings(),
      wizardCompleted: true,
      ...(dontAskAgain ? { skipClaudeCheck: true } : {}),
    })
    if (dontAskAgain) log.info('Further Claude checks disabled at the user\'s request.')
    // The 'closed' handler opens the main window, so both routes out of this page agree.
    onboardingWindow?.close()
  })
}

/**
 * Restart the backend if the CLI path it was started with is no longer the right one.
 *
 * Needed because `local.claude-command` is read once at startup. Signing in is picked up without
 * this — the backend re-checks the filesystem whenever it is asked — but a CLI that did not exist
 * when the backend spawned stays unconfigured until it is told again. Without this, "Locate
 * claude…" would appear to work and then fail at the first Run.
 */
async function adoptClaudeCommand(command: string | null): Promise<void> {
  if (command === backendClaudeCommand) return
  if (!backend) return

  log.info(`claude CLI changed (${backendClaudeCommand ?? 'none'} -> ${command ?? 'none'}); restarting the backend.`)
  await restartBackend()
  backendClaudeCommand = command
}

/**
 * Stops and starts the backend, keeping the window pointed at it.
 *
 * <p>Needed by two settings that are read once at startup and so cannot change under a running
 * process: the path to the claude CLI, and which database to use.
 */
async function restartBackend(): Promise<void> {
  if (!backend) return
  await stopBackend(backend)
  backend = null
  try {
    backend = await startBackend()
    // A fresh backend has a fresh run registry; announcing its list as news would be wrong.
    resetRunNotifications()
    // The port is stable by design, but re-point the window rather than assume it.
    if (mainWindow) {
      const url = await uiSource(backend.port)
      void mainWindow.loadURL(url)
    }
  } catch (err) {
    log.error('The backend failed to restart', err)
    showFailureWindow(err instanceof Error ? err.message : String(err))
    throw err
  }
}

/**
 * The application icon.
 *
 * A packaged Windows build takes it from the executable, which electron-builder stamps from
 * icon.ico — but an unpackaged run has no such executable of its own, so the window needs it
 * explicitly. The .ico is preferred there because it carries several sizes: Windows picks 16px
 * for the title bar and 32px for the taskbar, and downscaling one 256px PNG to 16px on the fly
 * gives the muddy result that reads as "the icon is wrong".
 *
 * On Linux the window and taskbar entry take their icon from the running process rather than from
 * the .desktop file, and default to a blank one without this.
 */
function appIcon(): string {
  const file = process.platform === 'win32' ? 'icon.ico' : 'icon.png'
  return path.join(__dirname, '..', 'build', file)
}
