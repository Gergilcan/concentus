/**
 * The desktop shell's bridge, when the UI runs inside it.
 *
 * The Electron main window exposes a deliberately short list (see apps/desktop/src/
 * preload-main.ts): the auto-updater — status, a manual check, restart-and-install — and
 * installing one of six fixed developer runtimes an MCP server may need. In a plain browser the
 * global is absent and `shellBridge()` returns null, which is how update UI knows not to render.
 *
 * A bridge from an older shell may not have every section, so each optional one is checked at the
 * call site rather than assumed: the UI is served by the backend and can be newer than the shell
 * around it (a browser tab, or an app that has not restarted into its update yet).
 */

export type UpdatePhase = 'idle' | 'checking' | 'up-to-date' | 'downloading' | 'downloaded' | 'error'

export interface ShellUpdateState {
  /** False when this run cannot update itself; `reason` says why. */
  supported: boolean
  reason?: string
  /** The running app's version. */
  version: string
  phase: UpdatePhase
  /** The version found upstream, once one is. */
  available?: string
  /** 0-100 while downloading. */
  progressPercent?: number
  error?: string
  /** When the last check finished, either way (epoch ms). */
  checkedAt?: number
}

/** What the shell would run to install a runtime here. `command` is null when there is no safe one. */
export interface RuntimeInstallPlan {
  runtime: string
  command: string | null
  reason?: string
}

export interface ShellBridge {
  updates: {
    status: () => Promise<ShellUpdateState>
    check: () => Promise<ShellUpdateState>
    install: () => Promise<{ ok: boolean; error?: string }>
  }
  /**
   * Installing a runtime an MCP server needs. Present only inside the shell — in a browser there
   * is no process to install anything with, and the UI offers the official instructions instead.
   *
   * `install` takes a runtime IDENTIFIER, never a command: the shell owns the fixed table of
   * commands and rejects anything not in it. `onOutput` returns its own unsubscribe.
   */
  runtimes?: {
    plan: (runtime: string) => Promise<RuntimeInstallPlan>
    install: (runtime: string) => Promise<{ ok: boolean; detail: string }>
    onOutput: (handler: (line: string) => void) => () => void
  }
}

export function shellBridge(): ShellBridge | null {
  return (window as { concentusShell?: ShellBridge }).concentusShell ?? null
}
