import { contextBridge, ipcRenderer } from 'electron'

/**
 * Preload for the startup-failure window only.
 *
 * The main window deliberately has no preload: it loads the app's own UI over HTTP and has no
 * business reaching anything in the main process. The failure page is ours, is static, and needs
 * exactly three actions — so it gets exactly three.
 */
contextBridge.exposeInMainWorld('concentus', {
  retry: () => ipcRenderer.send('failure:retry'),
  openLogs: () => ipcRenderer.send('failure:open-logs'),
  quit: () => ipcRenderer.send('failure:quit'),
})
