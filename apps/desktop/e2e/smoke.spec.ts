import { _electron as electron, expect, test, type ElectronApplication, type Page } from '@playwright/test'
import * as fs from 'node:fs'
import * as http from 'node:http'
import type { AddressInfo } from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'

/**
 * The shell, end to end, without a JVM.
 *
 * The seam is the dev-loop adoption rule in backend.ts: a development run that finds something
 * answering /actuator/health/readiness on its preferred port uses it instead of starting the jar.
 * So this test IS that something — a few lines of http on an ephemeral port — and tells the shell
 * about it through a settings file in a scratch data directory (CONCENTUS_USER_DATA, main.ts).
 * The scratch directory also keys the single-instance lock, so an installed Concentus running on
 * the same machine neither blocks this launch nor gets its window stolen by it.
 */

const UI_TEXT = 'Concentus smoke backend'

function stubBackend(): Promise<http.Server> {
  const server = http.createServer((req, res) => {
    if (req.url === '/actuator/health/readiness') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"status":"UP"}')
      return
    }
    if (req.url === '/api/runs') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('[]')
      return
    }
    // The "UI". Held back a moment so the splash is on screen long enough to be looked at.
    setTimeout(() => {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(`<!doctype html><html><head><title>Concentus</title></head><body><h1>${UI_TEXT}</h1></body></html>`)
    }, 1_500)
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)))
}

/** The window whose page came from `origin`, once the shell has opened it. */
async function windowFrom(app: ElectronApplication, origin: string, timeoutMs: number): Promise<Page> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const found = app.windows().find((w) => w.url().startsWith(origin))
    if (found) return found
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(`No window loaded from ${origin} within ${timeoutMs}ms; windows: ${app.windows().map((w) => w.url()).join(', ')}`)
}

test('the shell boots, shows the splash, adopts the running backend and opens its UI', async () => {
  const server = await stubBackend()
  const port = (server.address() as AddressInfo).port
  const origin = `http://127.0.0.1:${port}`

  const userData = fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-smoke-'))
  // Port: where the stub is. The two flags: the first-run wizard has been through, and the
  // Claude Code check is off — so the launch goes straight to the main window.
  fs.writeFileSync(
    path.join(userData, 'desktop-settings.json'),
    JSON.stringify({ port, wizardCompleted: true, skipClaudeCheck: true }, null, 2),
  )

  // A terminal hosted by an Electron app (VS Code's, Claude Code's) exports ELECTRON_RUN_AS_NODE,
  // which turns the Electron binary into a bare node that rejects every Chromium switch Playwright
  // passes — "bad option: --remote-debugging-port". The shell must be launched as an app.
  const { ELECTRON_RUN_AS_NODE: _asNode, ...inherited } = process.env
  const app = await electron.launch({
    args: [path.resolve(__dirname, '..')],
    env: {
      ...inherited,
      CONCENTUS_USER_DATA: userData,
      // A dev run prefers a Vite dev server when one answers; point it at a port nothing listens
      // on so the window loads the (stub) backend's UI whatever else is running on this machine.
      CONCENTUS_DEV_UI: 'http://127.0.0.1:9',
    },
  })

  try {
    // The splash is the first window, and it says what it is while the backend comes up.
    const splash = await app.firstWindow()
    await expect(splash.locator('h1')).toHaveText('Concentus')
    await expect(splash.locator('.version')).toHaveText('Development build')

    // Then the main window, pointed at the adopted backend.
    const main = await windowFrom(app, origin, 30_000)
    await expect(main.locator('h1')).toHaveText(UI_TEXT, { timeout: 15_000 })
    await expect(main).toHaveTitle('Concentus')

    // And the splash is gone once the real window is up.
    await expect.poll(() => splash.isClosed(), { timeout: 10_000 }).toBe(true)

    // The shell wrote its log where it was told to, and says it adopted rather than started.
    const log = fs.readFileSync(path.join(userData, 'logs', 'desktop.log'), 'utf8')
    expect(log).toContain(`Adopting the backend already running on 127.0.0.1:${port}`)
  } finally {
    await app.close()
    await new Promise<void>((resolve) => server.close(() => resolve()))
    fs.rmSync(userData, { recursive: true, force: true })
  }
})
