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
/**
 * Not final — the run is stopped mid-flight waiting for a person, which is the case most worth
 * interrupting for: it will wait indefinitely, and nothing else is going to move it.
 *
 * Two shapes of waiting, and they are told apart in the toast because the answer they need is
 * different: an approval is yes/no on a plan, a question is a reply to type.
 */
const WAITING = new Set(['AWAITING_APPROVAL', 'AWAITING_ANSWER'])

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

/** One run's status, as a toast. Kept apart from the poll so the wording is readable in one place. */
export function toastFor(run: { status: string; flowName?: string | null; error?: string | null }) {
  const flow = run.flowName ?? 'A flow'
  switch (run.status) {
    case 'AWAITING_ANSWER':
      // Deliberately not "Approval needed": this one wants words back, not a yes or a no, and a
      // toast that names the wrong action sends people to look for a button that isn't there.
      return {
        title: 'The agent asked you something',
        body: `${flow} is waiting for your reply.`,
        urgency: 'critical' as const,
      }
    case 'AWAITING_APPROVAL':
      return {
        title: 'Approval needed',
        body: `${flow} has a plan waiting for you. Nothing has changed yet.`,
        urgency: 'critical' as const,
      }
    case 'ERROR':
      return {
        title: 'Execution failed',
        body: `${run.flowName ?? 'Flow'}${run.error ? ` — ${run.error}` : ''}`.slice(0, 200),
        urgency: 'critical' as const,
      }
    default:
      return {
        title: 'Execution finished',
        body: `${run.flowName ?? 'Flow'}`.slice(0, 200),
        urgency: 'normal' as const,
      }
  }
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
    const waiting = WAITING.has(run.status)
    if (!FINAL.has(run.status) && !waiting) continue
    // Only on the transition INTO the status, which is also what stops a run that sits waiting
    // from re-announcing the same question every fifteen seconds. A second question in the same
    // run passes through RUNNING first, so it is a transition again — and it IS a new question.
    if (before === run.status) continue
    // A run first seen already-final was missed while the poller was down; still worth a toast in
    // background mode, which is exactly when it happens.
    //
    // Waiting is the exception to the focused-window rule: a run that stops to ask waits
    // indefinitely, and the whole feature fails quietly if the ask is only visible to someone
    // already looking at the right tab.
    if (options.isWindowFocused() && !waiting) continue

    try {
      const notification = new Notification(toastFor(run))
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
