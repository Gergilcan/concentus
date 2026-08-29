import { app, BrowserWindow, Notification, dialog, ipcMain, Menu, shell } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { RunningBackend, backendLogTail, startBackend, stopBackend } from './backend'
import { StorageDraft, backendApi } from './backend-api'
import { resolveClaudeCli } from './claude-cli'
import { installClaude, installCommand, openLoginTerminal } from './claude-install'
import { hasApiKey, saveApiKey } from './api-key'
import { dataKeyState } from './secret'
import { ensureOnPath } from './path-setup'
import { failurePage } from './failure-page'
import { licensePage } from './license-page'
import { OnboardingState, StorageState, onboardingPage } from './onboarding-page'
import { backendLogFile, dataDir, isPackaged, shellLogFile } from './paths'
import { Splash, noSplash, showSplash } from './splash'
import { resetRunNotifications, startRunNotifications } from './run-notifications'
import { loadSettings, saveSettings } from './settings'
import { applyStartWithSystem, createTray } from './tray'
import {
  checkForUpdatesNow,
  installUpdateNow,
  onBeforeInstall,
  startAutoUpdates,
  updateStatus,
} from './updater'
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
// A different data directory for a run that must not touch the real one. The shell's smoke test
// (e2e/smoke.spec.ts) points this at a scratch folder so its settings, logs and — since Electron
// keys it by this directory — its single-instance lock stay clear of an installed Concentus that
// may be running on the same machine. Same moment as setName, for the same reason.
if (process.env.CONCENTUS_USER_DATA) app.setPath('userData', process.env.CONCENTUS_USER_DATA)
// Windows identifies a taskbar button by its AppUserModelID, not by the window. Without one set
// here, Electron derives it from the running executable — electron.exe when unpackaged — so the
// taskbar shows Electron's icon and name however the window is decorated. It must match the
// appId in electron-builder.yml, or a packaged build gets a second, unpinnable identity.
if (process.platform === 'win32') app.setAppUserModelId('com.concentus.desktop')

let backend: RunningBackend | null = null
let mainWindow: BrowserWindow | null = null
/**
 * Windows signed in as somebody else.
 *
 * Each gets its own session partition, which means its own cookie jar, which means its own signed
 * -in account — the session is a cookie, so two accounts in one jar is not a thing that can exist.
 * Kept in a list only to number the next partition and to close them on quit.
 */
const altWindows: BrowserWindow[] = []
let failureWindow: BrowserWindow | null = null
let licenseWindow: BrowserWindow | null = null
let onboardingWindow: BrowserWindow | null = null
/** The splash for the launch in progress; noSplash outside one, so callers never null-check. */
let splash: Splash = noSplash
/** Set once quitting has begun, so the backend is stopped exactly once. */
let quitting = false
/**
 * Set while an update install is being prepared, so nothing quits the app out from under it.
 *
 * <p>Without this, closeForInstall's window destruction fired window-all-closed, which saw
 * {@link quitting} and called app.quit() BEFORE installUpdateNow reached quitAndInstall — and a
 * quit that electron-updater merely observes takes its autoInstallOnAppQuit path: silent install,
 * no relaunch flag. The update applied and the app never came back. The quit belongs to
 * quitAndInstall, which is the call that registers the relaunch.
 */
let installing = false
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
  // macOS takes the Dock icon from the app BUNDLE, not from any window — so `icon` on a
  // BrowserWindow, which is what dresses the window and the taskbar entry everywhere else, does
  // nothing here. Unpackaged, the bundle is Electron's own, and Concentus sits in the Dock
  // wearing Electron's logo. This is the only way to say otherwise from inside the process.
  //
  // Unconditional rather than dev-only: a packaged macOS build would carry its own icon and this
  // would merely re-assert it, and there is no packaged macOS build today (see the note at the
  // foot of electron-builder.yml) — so the case this fixes is the only case there is.
  if (process.platform === 'darwin') app.dock?.setIcon(appIcon())
  registerIpc()
  // No application menu. The UI has its own navigation, and a File/View/Edit bar above it is a
  // second, emptier navigation that belongs to a different application — the window should look
  // like Concentus, not like a browser someone put Concentus inside. Removing it costs nothing
  // functionally: Chromium handles clipboard and text-editing shortcuts in web content itself,
  // and the one menu item that did something specific — opening the logs folder — is still on the
  // failure window, which is when it is actually needed.
  Menu.setApplicationMenu(null)
  createTray({
    openWindow: openMainWindow,
    openSetup: showOnboardingWindow,
    quit: () => { quitting = true; app.quit() },
  })
  // Re-assert on every start: an update can move the installed binary, and a login item pointing
  // at last version's path launches nothing.
  if (loadSettings().startWithSystem) applyStartWithSystem(true)
  startAutoUpdates()
  // What the installer needs to be true before it can replace the files under it.
  onBeforeInstall(closeForInstall)
  await launch()
  startRunNotifications({
    port: () => backend?.port ?? null,
    onClick: openMainWindow,
    isWindowFocused: () => !!mainWindow && !mainWindow.isDestroyed() && mainWindow.isFocused(),
  })

  app.on('window-all-closed', () => {
    // During an update install the windows are destroyed on purpose and the quit is
    // quitAndInstall's to make — see the note on `installing`.
    if (installing) return
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

/**
 * Everything closed, for an installer that is about to overwrite it.
 *
 * <p>Quitting a window is not the same as ending what is under it. The bundled Java runtime and
 * the backend jar live inside the installation directory, so while the backend process is alive
 * the installer cannot replace them — it stops and says the application is still open, behind a
 * window that has already disappeared.
 *
 * <p>So this does the ending itself and waits for it, rather than trusting the quit that follows:
 * {@link stopBackend} asks politely, then takes the whole process tree, then waits for it to
 * actually be gone. It also sets {@link quitting} first, so no tray setting turns the quit into a
 * hide, and closes the windows so nothing can veto what comes next.
 */
async function closeForInstall(): Promise<void> {
  log.info('Closing everything before the installer runs.')
  quitting = true
  installing = true
  await stopBackend(backend)
  backend = null
  for (const window of BrowserWindow.getAllWindows()) window.destroy()
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
    // The license gate is a refusal, not a failure: it gets its own window, with the paste box,
    // instead of a stack trace. The marker is the gate's own message text (LicenseCheck, backend)
    // — it only ever appears in the log for exactly this refusal.
    const tail = backendLogTail()
    const gateLine = tail.match(/[^\n]*is an enterprise feature[^\n]*/)?.[0]
    if (gateLine) {
      showLicenseWindow(gateLine.replace(/^.*?(?=The )/, '').trim())
    } else {
      showFailureWindow(message)
    }
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
  const win = failureWindow ?? licenseWindow ?? onboardingWindow
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

/**
 * Another window, signed out, with a session of its own.
 *
 * For running two accounts at once — an admin and a viewer, side by side — which is the only
 * honest way to see what a role actually permits: the interface hides what you may not do, so the
 * way to check is to be that person. A second window starts at the sign-in screen because a fresh
 * cookie jar has nobody in it, and the two windows stay independent from there.
 *
 * Deliberately not "switch account": switching would sign the first one out, and the comparison
 * that makes this useful is having both on screen.
 */
async function openAccountWindow(): Promise<{ ok: boolean; error?: string }> {
  if (!backend) return { ok: false, error: 'The backend is not running yet.' }
  try {
    const partition = `persist:concentus-account-${altWindows.length + 2}`
    const win = new BrowserWindow({
      width: 1280,
      height: 860,
      minWidth: 960,
      minHeight: 600,
      title: 'Concentus — another account',
      icon: appIcon(),
      autoHideMenuBar: true,
      backgroundColor: '#0b0e14',
      webPreferences: {
        preload: path.join(__dirname, 'preload-main.js'),
        contextIsolation: true,
        nodeIntegration: false,
        partition,
      },
    })
    altWindows.push(win)
    win.on('closed', () => {
      const at = altWindows.indexOf(win)
      if (at >= 0) altWindows.splice(at, 1)
    })
    const url = await uiSource(backend.port)
    openExternalLinksInBrowser(win, new URL(url).origin)
    await win.loadURL(url)
    return { ok: true }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) }
  }
}

async function showMainWindow(port: number): Promise<void> {
  failureWindow?.close()
  failureWindow = null
  licenseWindow?.close()
  licenseWindow = null

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
    dropSplash()
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
  /**
   * Whether the window is currently showing our own interface.
   *
   * <p>This is the whole distinction. A link to a website, clicked from our UI, belongs in the
   * browser. A navigation that happens while the window is already somewhere else belongs to
   * whatever took it there — and the only thing that does is an authorization flow we started.
   */
  const atHome = () => win.webContents.getURL().startsWith(origin)

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith(origin) || !atHome()) return { action: 'allow' }
    void shell.openExternal(url)
    return { action: 'deny' }
  })

  win.webContents.on('will-navigate', (event, url) => {
    if (url.startsWith(origin)) return
    // Signing in with a directory is the case this used to break, and it broke it completely.
    // Microsoft's login page submits the password by POSTing to /login — which is a navigation off
    // our origin, so this handler cancelled it and re-opened that URL in the system browser as a
    // GET. The endpoint only accepts POST, so what the person saw was AADSTS900561 in a browser
    // window they had not asked for, every time, with nothing wrong on either side.
    //
    // Once the window has left our origin it is inside a flow we sent it into, and every step of
    // that flow — form posts, consent, a redirect back — has to be allowed to happen where it
    // started. The flow ends by returning to our own origin, which is how the window comes home.
    if (!atHome()) return
    event.preventDefault()
    void shell.openExternal(url)
  })

  /**
   * A way back when a provider's page goes wrong.
   *
   * <p>This window has no address bar and no back button, so an error page from a directory is a
   * dead end: nothing on it leads anywhere, and the person is left with a Microsoft error inside
   * an application that appears to have stopped. Escape returns to the interface, which is what
   * every other full-screen dead end in this app already does.
   */
  win.webContents.on('before-input-event', (_event, input) => {
    if (input.type !== 'keyDown' || input.key !== 'Escape' || atHome()) return
    void win.loadURL(origin)
  })
}

/** The launch has a real window now (or a failure); the splash is done and stays gone. */
function dropSplash(): void {
  splash.close()
  splash = noSplash
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
      if (backend) storage = await backendApi.getStorage(backend.port, backend.shellToken)
    } catch (err) {
      log.warn(`Could not read the storage settings: ${err instanceof Error ? err.message : String(err)}`)
    }
    const html = onboardingPage(claude, storage, hasApiKey(), dataKeyState())
    try {
      await onboardingWindow?.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
    } finally {
      // Only now: the wizard window opens instantly but paints its content after this async block,
      // and dropping the splash on creation would leave a blank window as the handover.
      dropSplash()
    }
  })()
}

function showFailureWindow(message: string): void {
  // A progress bar over a failure would be a lie; the failure window is the whole screen now.
  dropSplash()
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

/**
 * The license wall. A fixed-size modal shape on purpose — this is a decision screen, not a
 * workspace: paste a license, go request one, or close the app. See license-page.ts.
 */
function showLicenseWindow(message: string): void {
  dropSplash()
  licenseWindow = new BrowserWindow({
    width: 640,
    height: 640,
    resizable: false,
    title: 'Concentus — license required',
    icon: appIcon(),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  licenseWindow.on('closed', () => {
    licenseWindow = null
  })
  void licenseWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(licensePage(message))}`)
}

function registerIpc(): void {
  // The main window's updates bridge (preload-main.ts). Status is a poll target — the panel asks
  // while a check or download is in flight — so it must stay cheap and side-effect free.
  ipcMain.handle('updates:status', () => updateStatus())
  ipcMain.handle('updates:check', () => checkForUpdatesNow())
  ipcMain.handle('updates:install', () => installUpdateNow())

  // A window for another account. See openAccountWindow: a second cookie jar is the only place
  // a second signed-in account can exist, and only the shell can make one.
  ipcMain.handle('accounts:open-window', () => openAccountWindow())

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

  // The license wall's three exits. Apply does only what the shell can judge — the shape check
  // and the file write; whether the license is VALID is the backend's verdict, delivered by the
  // relaunch: accepted means the app opens, refused means this window returns with the reason.
  ipcMain.handle('license:apply', (_event, token: unknown) => {
    const text = String(token ?? '').trim()
    if (!text.startsWith('CONCENTUS.')) {
      return { ok: false, error: 'That does not look like a Concentus license — it is one line starting with "CONCENTUS.".' }
    }
    try {
      fs.writeFileSync(path.join(dataDir(), 'license.key'), text + '\n')
    } catch (err) {
      return { ok: false, error: `Could not write the license file: ${err instanceof Error ? err.message : String(err)}` }
    }
    licenseWindow?.close()
    licenseWindow = null
    void launch()
    return { ok: true }
  })
  ipcMain.on('license:request', () => {
    void shell.openExternal('https://www.concentus-ai.com/#license')
  })
  ipcMain.on('license:quit', () => app.quit())

  ipcMain.handle('onboarding:recheck', async () => {
    const state = await claudeState()
    await adoptClaudeCommand(state.command)
    return state
  })

  ipcMain.handle('onboarding:install-command', () => installCommand())

  /**
   * Stores (or clears) the Anthropic API key, and restarts the backend so it takes effect.
   *
   * <p>The restart is the point. The key is read when the backend process starts, so saving it
   * without one would leave the wizard reporting success over a backend that goes on refusing to
   * run anything — the exact shape of failure this wizard exists to prevent.
   */
  ipcMain.handle('onboarding:save-api-key', async (_event, key: string | null) => {
    const result = saveApiKey(key)
    if (!result.ok) return { ...result, hasKey: hasApiKey() }
    await restartBackend()
    return { ...result, hasKey: hasApiKey() }
  })

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

    // And onto the PATH, which is the difference between "Concentus can use it" and "you have
    // the command". An app that installs a command line tool and does not give the person the
    // command has done half a job — and the half it skipped is the half they will notice.
    let pathDetail = ''
    if (state.command) {
      const onPath = await ensureOnPath(path.dirname(state.command))
      // Only worth saying when it failed: on the happy path this is plumbing, and a wizard that
      // narrates its plumbing buries the one line that matters.
      pathDetail = onPath.ok ? '' : onPath.detail
    }

    // Fresh install, no login yet: open the sign-in right away, in a terminal already running
    // the just-installed binary. The wizard polls until the login lands.
    let loginOpened = false
    if (state.command && !state.loggedIn) {
      loginOpened = (await openLoginTerminal(state.command)).ok
    }
    return { ...result, state, loginOpened, pathDetail }
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

  ipcMain.handle('onboarding:storage-test', async (_event, draft: StorageDraft) => {
    const { port, token } = storageCall()
    return backendApi.testStorage(port, token, draft)
  })

  ipcMain.handle('onboarding:storage-save', async (_event, draft: StorageDraft) => {
    const { port, token } = storageCall()
    const saved = await backendApi.saveStorage(port, token, draft)
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
 * Where the wizard's storage calls go, and what authenticates them.
 *
 * <p>The token is what makes these calls possible at all: they happen before the first
 * account exists, so there is no session to present. An adopted dev backend never received one —
 * the shell did not start it — and saying so is better than the backend's own answer, which is a
 * flat "You do not have permission to do that" with nothing to act on.
 */
function storageCall(): { port: number; token: string } {
  if (!backend) throw new Error('The backend is not running.')
  if (!backend.shellToken) {
    throw new Error('This wizard is using a backend it did not start (the development one on '
      + `127.0.0.1:${backend.port}), so it cannot authenticate to it. Stop that backend and `
      + 'relaunch to choose a database here, or change it from Settings once signed in.')
  }
  return { port: backend.port, token: backend.shellToken }
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
