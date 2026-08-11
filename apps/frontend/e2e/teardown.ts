/**
 * Stops the E2E backend the polite way, BEFORE Playwright kills the webServer process tree.
 *
 * The reason this exists is Windows-shaped: Playwright tears the webServer down with a tree kill
 * (there is no SIGTERM to send), the server script's signal handlers therefore never run, and
 * PostgreSQL — whose processes re-parent when pg_ctl dies — survived every run. A suite that
 * leaked a postmaster per invocation was how this file earned its place. Asking the backend to
 * shut down over HTTP runs its hooks, which stop the database properly; the tree kill that
 * follows then finds nothing left to kill.
 */
const base = `http://127.0.0.1:${Number(process.env.E2E_PORT ?? 8799)}`

export default async function globalTeardown(): Promise<void> {
  try {
    await fetch(`${base}/actuator/shutdown`, { method: 'POST' })
  } catch {
    return // never started, or already gone — nothing to stop
  }
  // Wait for the port to actually close, so the backend's own exit (~1s) finishes before
  // Playwright starts killing processes.
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    try {
      await fetch(`${base}/actuator/health/readiness`)
    } catch {
      return // connection refused: it is down
    }
    await new Promise((r) => setTimeout(r, 250))
  }
}
