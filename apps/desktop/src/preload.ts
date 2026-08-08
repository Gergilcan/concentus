import { contextBridge, ipcRenderer } from 'electron'

/**
 * Preload for the shell's own two pages — the startup-failure window and the first-run window.
 *
 * The main window deliberately has no preload: it loads the application's UI over HTTP and has no
 * business reaching the main process. These pages are ours, static, and need a fixed handful of
 * actions, so they get exactly those and nothing generic.
 */
contextBridge.exposeInMainWorld('concentus', {
  // Startup failure
  retry: () => ipcRenderer.send('failure:retry'),
  openLogs: () => ipcRenderer.send('failure:open-logs'),
  quit: () => ipcRenderer.send('failure:quit'),

  // First run. The first two answer with fresh state so the page re-renders from the same shape
  // it was given initially, rather than guessing at what changed.
  recheckClaude: () => ipcRenderer.invoke('onboarding:recheck'),
  locateClaude: () => ipcRenderer.invoke('onboarding:locate'),
  // Runs Anthropic's official installer. The command is exposed separately so the page can show
  // exactly what it is about to run — this pipes a remote script to a shell, and asking someone to
  // approve that without naming it would not be a real choice.
  claudeInstallCommand: () => ipcRenderer.invoke('onboarding:install-command'),
  installClaude: () => ipcRenderer.invoke('onboarding:install'),
  onInstallOutput: (handler: (line: string) => void) =>
    ipcRenderer.on('onboarding:install-output', (_event, line: string) => handler(line)),
  finishOnboarding: (dontAskAgain: boolean) => ipcRenderer.send('onboarding:finish', dontAskAgain),

  // The database step. testStorage is what gates moving on: an external database that cannot be
  // reached must not be accepted, because the next launch would open against nothing.
  getStorage: () => ipcRenderer.invoke('onboarding:storage-get'),
  testStorage: (draft: unknown) => ipcRenderer.invoke('onboarding:storage-test', draft),
  saveStorage: (draft: unknown) => ipcRenderer.invoke('onboarding:storage-save', draft),
})
