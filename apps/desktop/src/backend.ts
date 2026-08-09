import { ChildProcess, spawn } from 'node:child_process'
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as net from 'node:net'
import { backendJar, backendLogFile, dataDir, javaBinary } from './paths'
import { resolveClaudeCli } from './claude-cli'
import { masterSecret } from './secret'
import { loadSettings, saveSettings } from './settings'
import { log } from './log'

/**
 * Starting, watching and stopping the Spring Boot backend.
 *
 * The backend is a child process of the shell, bound to loopback, owned entirely by this app. It
 * is not a service, it is not shared, and it does not outlive the window.
 */

/**
 * Preferred port. Fixed rather than always-random so MCP OAuth redirect URIs registered with an
 * authorization server keep working across launches; see Settings.port.
 */
const DEFAULT_PORT = 8734
/** How long to wait for the backend to report itself ready before giving up. */
const STARTUP_TIMEOUT_MS = 120_000
const HEALTH_POLL_INTERVAL_MS = 400
/** Grace period for a clean shutdown before the process is killed outright. */
const SHUTDOWN_TIMEOUT_MS = 8_000

export interface RunningBackend {
  port: number
  process: ChildProcess
}

/** True if nothing is listening on the port — i.e. we may take it. */
function isPortFree(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const server = net.createServer()
    server.once('error', () => resolve(false))
    server.once('listening', () => server.close(() => resolve(true)))
    // Bind the same address the backend will. A port free on 127.0.0.1 can be taken on 0.0.0.0
    // and vice versa, so testing any other interface would answer a different question.
    server.listen(port, '127.0.0.1')
  })
}

/** Ask the OS for an unused port, for when the preferred one is taken. */
function ephemeralPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (address && typeof address === 'object') {
        const { port } = address
        server.close(() => resolve(port))
      } else {
        server.close(() => reject(new Error('Could not obtain a port from the OS')))
      }
    })
  })
}

async function choosePort(): Promise<number> {
  const settings = loadSettings()
  const preferred = settings.port ?? DEFAULT_PORT

  if (await isPortFree(preferred)) return preferred

  const fallback = await ephemeralPort()
  // Existing MCP authorizations are NOT affected, despite the OAuth redirect carrying the port:
  // grants are stored per MCP url and renewed with a refresh_token grant, which by RFC 6749 §6
  // carries no redirect_uri. A fresh sign-in re-registers the client with the current port. The
  // earlier warning here claimed sign-ins would need redoing, which was never true.
  log.warn(`Port ${preferred} is in use; moving to ${fallback} and remembering it.`)
  // Persisted so the move happens once rather than every launch — the whole point of a stable port.
  saveSettings({ ...settings, port: fallback })
  return fallback
}

/** GET the readiness probe; true only on a 200. */
function probeReady(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    // readiness, not the aggregate /actuator/health: the aggregate includes a `db` indicator that
    // reports DOWN while persistence is disabled, which would mean the app never appeared to start.
    const req = http.get(
      { host: '127.0.0.1', port, path: '/actuator/health/readiness', timeout: 2_000 },
      (res) => {
        res.resume()
        resolve(res.statusCode === 200)
      },
    )
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
  })
}

/** Poll until ready, or until the process dies or the timeout expires. */
async function waitForReady(port: number, child: ChildProcess): Promise<void> {
  const deadline = Date.now() + STARTUP_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(`The backend exited during startup (code ${child.exitCode ?? child.signalCode}).`)
    }
    if (await probeReady(port)) return
    await new Promise((r) => setTimeout(r, HEALTH_POLL_INTERVAL_MS))
  }
  throw new Error(`The backend did not become ready within ${STARTUP_TIMEOUT_MS / 1000}s.`)
}

export async function startBackend(): Promise<RunningBackend> {
  const jar = backendJar()
  if (!fs.existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it with: pnpm backend:build`)
  }

  const java = javaBinary()
  const port = await choosePort()
  const data = dataDir()
  const settings = loadSettings()
  const claude = await resolveClaudeCli(settings.claudeCommand)

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    // The login shell's PATH, so the backend can reach the same tools the user can — claude, git,
    // node — none of which a desktop launcher's environment necessarily contains.
    PATH: claude.path,
    APP_DATA_DIR: data,
    CONCENTUS_SECRET_KEY: masterSecret(),
    // Must be the address the browser (this window) actually uses, because it is registered with
    // each MCP authorization server.
    MCP_OAUTH_REDIRECT_BASE: `http://127.0.0.1:${port}`,
    // Blank is meaningful and safe: the backend then falls back to its own PATH resolution.
    CLAUDE_COMMAND: claude.command ?? '',
  }

  const args = [
    '-jar',
    jar,
    '--spring.profiles.active=desktop',
    `--server.port=${port}`,
  ]

  log.info(`Starting backend: ${java} ${args.join(' ')}`)
  const child = spawn(java, args, {
    env,
    // Anchored at the data directory rather than wherever the launcher happened to start us, so
    // the backend's optional `.env` import cannot pick up a stray file from an unrelated folder.
    cwd: data,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })

  // The backend logs to its own file via the desktop profile; this catches what escapes Logback —
  // JVM crashes, missing-class errors, anything that happens before logging is configured.
  child.stdout?.on('data', (chunk: Buffer) => log.info(`[backend] ${chunk.toString().trimEnd()}`))
  child.stderr?.on('data', (chunk: Buffer) => log.warn(`[backend] ${chunk.toString().trimEnd()}`))

  await waitForReady(port, child)
  log.info(`Backend ready on 127.0.0.1:${port}`)
  return { port, process: child }
}

/** POST /actuator/shutdown and resolve once it has been accepted. */
function requestShutdown(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: '/actuator/shutdown', method: 'POST', timeout: 3_000 },
      (res) => {
        res.resume()
        resolve(res.statusCode === 200)
      },
    )
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.end()
  })
}

/**
 * Stop the backend, giving it the chance to finish writing first.
 *
 * The actuator endpoint rather than a signal, because Windows has no SIGTERM: `taskkill` would be
 * the equivalent, and it is a kill, not a request to stop. Asking over HTTP is the one mechanism
 * that means the same thing on every platform — and it lets Spring run its shutdown hooks, which
 * is what flushes in-flight run state to disk instead of losing it.
 */
export async function stopBackend(backend: RunningBackend | null): Promise<void> {
  if (!backend) return
  const { port, process: child } = backend
  if (child.exitCode !== null) return

  log.info('Asking the backend to shut down.')
  const accepted = await requestShutdown(port)
  if (!accepted) log.warn('Shutdown endpoint did not answer; waiting for exit before killing.')

  const exited = await new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => resolve(false), SHUTDOWN_TIMEOUT_MS)
    child.once('exit', () => {
      clearTimeout(timer)
      resolve(true)
    })
  })

  if (!exited) {
    log.warn('Backend did not exit in time; terminating it.')
    child.kill('SIGKILL')
  } else {
    log.info('Backend stopped.')
  }
}

/** Tail of the backend log, for the failure window. */
export function backendLogTail(maxChars = 6_000): string {
  try {
    const file = backendLogFile()
    if (!fs.existsSync(file)) return '(the backend produced no log file)'
    const text = fs.readFileSync(file, 'utf8')
    return text.length > maxChars ? text.slice(-maxChars) : text
  } catch (err) {
    return `(could not read the backend log: ${err instanceof Error ? err.message : String(err)})`
  }
}
