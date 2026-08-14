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
  /**
   * Installing a runtime an MCP server needs (Node, pnpm, Python, pipx, uv).
   *
   * The one place this bridge takes an argument, and it is deliberately not a command: the
   * renderer sends a runtime IDENTIFIER, the main process looks it up in a fixed table of six
   * hardcoded commands (runtime-install.ts) and rejects anything else. So the worst a compromised
   * page can do here is install one of six official developer tools — bad, but not arbitrary
   * execution, which is what passing a command line would have meant.
   */
  runtimes: {
    plan: (runtime: string) => ipcRenderer.invoke('runtimes:plan', runtime),
    install: (runtime: string) => ipcRenderer.invoke('runtimes:install', runtime),
    onOutput: (handler: (line: string) => void) => {
      const listener = (_event: unknown, line: string) => handler(line)
      ipcRenderer.on('runtimes:install-output', listener)
      // Returns its own removal: the panel that subscribes unmounts, and a listener left behind
      // would keep writing into a dead component's state on the next install.
      return () => ipcRenderer.removeListener('runtimes:install-output', listener)
    },
  },
})
