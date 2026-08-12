/**
 * The desktop shell's bridge, when the UI runs inside it.
 *
 * The Electron main window exposes exactly one thing (see apps/desktop/src/preload-main.ts):
 * the auto-updater — status, a manual check, and restart-and-install. In a plain browser the
 * global is absent and `shellBridge()` returns null, which is how update UI knows not to render.
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
}

export function shellBridge(): ShellBridge | null {
  return (window as { concentusShell?: ShellBridge }).concentusShell ?? null
}
