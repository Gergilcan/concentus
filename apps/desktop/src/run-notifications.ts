import { Notification } from 'electron'
import { backendApi } from './backend-api'
import { log } from './log'

/**
 * Desktop notifications when an execution finishes or fails.
 *
 * <p>Polling from the main process rather than listening in the page, for the reason this feature
 * exists at all: with background mode the window may be closed, and a notification that only works
 * while you are already looking at the app tells you nothing you did not know. The backend's run
 * list is a cheap endpoint the dashboard already polls at the same order of frequency.
 *
 * <p>The first poll seeds silently. Announcing every already-finished run on startup would greet
 * the user with a stack of stale toasts about work that ended yesterday.
 */

const POLL_MS = 15_000
/** Statuses worth interrupting someone for. IDLE is a run waiting for input, not an ending. */
const FINAL = new Set(['TERMINATED', 'ERROR'])

let timer: NodeJS.Timeout | null = null
let known: Map<string, string> | null = null

export interface NotifierOptions {
  port: () => number | null
  /** Focus the app — a notification someone clicks is a request to go look. */
  onClick: () => void
  /** Suppress while the user is already looking at the app; the console shows the same thing. */
  isWindowFocused: () => boolean
}

export function startRunNotifications(options: NotifierOptions): void {
  stopRunNotifications()
  known = null
  timer = setInterval(() => void poll(options), POLL_MS)
}

export function stopRunNotifications(): void {
  if (timer) clearInterval(timer)
  timer = null
}

/** The backend restarted (new DB, new CLI): re-seed rather than announce the old list as news. */
export function resetRunNotifications(): void {
  known = null
}

async function poll(options: NotifierOptions): Promise<void> {
  const port = options.port()
  if (port == null) return

  let runs
  try {
    runs = await backendApi.listRuns(port)
  } catch {
    // A backend mid-restart is not an event worth logging every 15 seconds.
    return
  }

  if (known == null) {
    known = new Map(runs.map((r) => [r.id, r.status]))
    return
  }

  for (const run of runs) {
    const before = known.get(run.id)
    known.set(run.id, run.status)
    if (!FINAL.has(run.status) || before === run.status) continue
    // A run first seen already-final was missed while the poller was down; still worth a toast in
    // background mode, which is exactly when it happens.
    if (options.isWindowFocused()) continue

    try {
      const failed = run.status === 'ERROR'
      const notification = new Notification({
        title: failed ? 'Execution failed' : 'Execution finished',
        body: `${run.flowName ?? 'Flow'}${failed && run.error ? ` — ${run.error}` : ''}`.slice(0, 200),
        urgency: failed ? 'critical' : 'normal',
      })
      notification.on('click', options.onClick)
      notification.show()
    } catch (err) {
      log.warn(`Could not show a notification: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  // Runs evicted from the registry would otherwise pin the map forever.
  const live = new Set(runs.map((r) => r.id))
  for (const id of known.keys()) if (!live.has(id)) known.delete(id)
}
