/**
 * The desktop shell's bridge, when the UI runs inside it.
 *
 * The Electron main window exposes exactly one thing (see apps/desktop/src/preload-main.ts): the
 * auto-updater — status, a manual check, and restart-and-install. In a plain browser the global is
 * absent and `shellBridge()` returns null, which is how update UI knows not to render.
 *
 * Anything else the UI needs from the machine goes through the backend's HTTP API instead, which
 * is why installing an MCP runtime is not here: the UI is served by the backend and can be newer
 * than the shell around it, and a capability that only exists in a shell that has not restarted
 * into its update yet is a capability that silently is not there.
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

export interface ShellBridge {
  updates: {
    status: () => Promise<ShellUpdateState>
    check: () => Promise<ShellUpdateState>
    install: () => Promise<{ ok: boolean; error?: string }>
  }
  /**
   * Absent in a shell older than this feature — the UI is served by the backend and can be newer
   * than the window around it, so the menu asks before it offers.
   */
  accounts?: {
    /** Opens a second window with its own session, signed out, for another account. */
    openWindow: () => Promise<{ ok: boolean; error?: string }>
  }
}

export function shellBridge(): ShellBridge | null {
  return (window as { concentusShell?: ShellBridge }).concentusShell ?? null
}
