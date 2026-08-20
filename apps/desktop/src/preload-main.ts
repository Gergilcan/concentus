import { contextBridge, ipcRenderer } from 'electron'

/**
 * The main window's bridge to the shell: application updates, and a second window.
 *
 * The window loads the application's UI over HTTP and is otherwise a plain browsing context on
 * purpose — a compromised page in it should be worth no more than a tab. This bridge is the
 * narrowest exception that lets the UI show an Updates section: fixed actions, none of which take
 * an argument, none of which touch the filesystem or return anything but state. Anything more
 * general than this belongs to the backend's HTTP API, not here.
 *
 * `accounts.openWindow` is here rather than in the API for a reason the API could not satisfy: a
 * second signed-in account needs a second cookie jar, and only the shell can make one.
 *
 * The UI detects the shell by this global's presence — in a plain browser it is absent and the
 * Updates section simply does not render.
 */
contextBridge.exposeInMainWorld('concentusShell', {
  updates: {
    status: () => ipcRenderer.invoke('updates:status'),
    check: () => ipcRenderer.invoke('updates:check'),
    install: () => ipcRenderer.invoke('updates:install'),
  },
  accounts: {
    openWindow: () => ipcRenderer.invoke('accounts:open-window'),
  },
})
