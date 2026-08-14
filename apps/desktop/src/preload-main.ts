import { contextBridge, ipcRenderer } from 'electron'

/**
 * The main window's ONE bridge to the shell: application updates.
 *
 * The window loads the application's UI over HTTP and is otherwise a plain browsing context on
 * purpose — a compromised page in it should be worth no more than a tab. This bridge is the
 * narrowest exception that lets the UI show an Updates section: three fixed actions, none of
 * which take an argument, none of which touch the filesystem or return anything but update
 * state. Anything more general than this belongs to the backend's HTTP API, not here.
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
})
