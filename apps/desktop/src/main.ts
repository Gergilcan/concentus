import { app, BrowserWindow, ipcMain, Menu, shell } from 'electron'
import * as path from 'node:path'
import { RunningBackend, backendLogTail, startBackend, stopBackend } from './backend'
import { failurePage } from './failure-page'
import { backendLogFile, shellLogFile } from './paths'
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
/** Set once quitting has begun, so the backend is stopped exactly once. */
let quitting = false

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

/** Start the backend and show either the app or the failure page. */
async function launch(): Promise<void> {
  try {
    backend = await startBackend()
    showMainWindow(backend.port)
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    log.error('Backend failed to start', err)
    showFailureWindow(message)
  }
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
