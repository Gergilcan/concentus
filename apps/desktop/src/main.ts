import { app, BrowserWindow, dialog, ipcMain, Menu, shell } from 'electron'
import * as path from 'node:path'
import { RunningBackend, backendLogTail, startBackend, stopBackend } from './backend'
import { StorageDraft, backendApi } from './backend-api'
import { resolveClaudeCli } from './claude-cli'
import { installClaude, installCommand } from './claude-install'
import { failurePage } from './failure-page'
import { OnboardingState, StorageState, onboardingPage } from './onboarding-page'
import { backendLogFile, shellLogFile } from './paths'
import { loadSettings, saveSettings } from './settings'
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

let backend: RunningBackend | null = null
let mainWindow: BrowserWindow | null = null
let failureWindow: BrowserWindow | null = null
let onboardingWindow: BrowserWindow | null = null
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
 * Two copies of this app would fight over the port, the data directory and the run state in it.
 * The second one hands focus to the first and leaves.
 */
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    const win = mainWindow ?? failureWindow
    if (win) {
      if (win.isMinimized()) win.restore()
      win.focus()
    }
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
  await launch()

  app.on('window-all-closed', () => {
    // No macOS exception here on purpose: this app owns a backend process, and leaving it running
    // with no window would be a background service the user never asked for and cannot see.
    app.quit()
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
  try {
    backend = await startBackend()
    backendClaudeCommand = (await claudeState()).command
    if (await shouldOnboard()) {
      showOnboardingWindow()
    } else {
      showMainWindow(backend.port)
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

function showMainWindow(port: number): void {
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
      // No preload and no node access: this window loads the application's own UI over HTTP, and
      // it has no reason to reach the main process. Keeping it a plain browsing context means a
      // compromised page in it is worth no more than a tab.
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  // Ready-to-show rather than showing immediately, to avoid a flash of empty window.
  mainWindow.once('ready-to-show', () => mainWindow?.show())
  mainWindow.on('closed', () => {
    mainWindow = null
  })

  openExternalLinksInBrowser(mainWindow, port)
  void mainWindow.loadURL(`http://127.0.0.1:${port}`)
}

/**
 * Anything not served by our own backend opens in the real browser.
 *
 * This matters most for MCP OAuth: the authorization page belongs in a browser the user recognises
 * and where their existing session already is, not in an app window with no address bar. The
 * provider then redirects to http://127.0.0.1:<port>/api/mcp/oauth/callback, which the backend
 * handles directly — so the flow completes even though it happened outside this window.
 */
function openExternalLinksInBrowser(win: BrowserWindow, port: number): void {
  const origin = `http://127.0.0.1:${port}`

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
    if (!mainWindow && !quitting && backend) showMainWindow(backend.port)
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
    void onboardingWindow?.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
  })()
}

function showFailureWindow(message: string): void {
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
    return { ...result, state }
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
    // The port is stable by design, but re-point the window rather than assume it.
    if (mainWindow) void mainWindow.loadURL(`http://127.0.0.1:${backend.port}`)
  } catch (err) {
    log.error('The backend failed to restart', err)
    showFailureWindow(err instanceof Error ? err.message : String(err))
    throw err
  }
}

/**
 * The application icon.
 *
 * Windows takes it from the executable, which electron-builder stamps from build/icon.ico, so
 * this is really for Linux — where the window and taskbar entry get their icon from the running
 * process, not from the .desktop file, and default to a blank one without it.
 */
function appIcon(): string {
  return path.join(__dirname, '..', 'build', 'icon.png')
}
