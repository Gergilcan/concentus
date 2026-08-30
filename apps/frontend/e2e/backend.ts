import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import * as net from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * One E2E backend: the real jar, a real embedded PostgreSQL, a scratch data directory.
 *
 * Started per Playwright WORKER rather than once for the whole suite — that is what makes the
 * suite parallel without being racy. Two workers counting cards or deleting flows against one
 * shared database would read each other's state; two workers with a database each cannot. The
 * price is one JVM + initdb per worker, a few seconds that buy state isolation outright.
 */

export interface Backend {
  baseURL: string
  stop(): Promise<void>
}

// import.meta, not __dirname: the package is type=module, so Playwright loads these files as ESM.
const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.join(here, '..', '..', '..')
const jar = path.join(repoRoot, 'apps', 'backend', 'target', 'concentus-backend.jar')

/**
 * Where the per-worker backends listen: this base plus the worker's slot. Overridable because two
 * checkouts of this repository running their suites on one machine would otherwise claim the same
 * ports — and a worker finding a backend already on its port politely shuts it down, which is the
 * other suite's backend dying mid-test. The scratch directories carry the base too, so the sweep
 * of leftover postmasters below only ever reaches this suite's own.
 */
export const PORT_BASE = Number(process.env.CONCENTUS_E2E_PORT_BASE ?? 8800)
const SCRATCH_PREFIX = `concentus-e2e_${PORT_BASE}-`

const BUILD_HINT =
  'Build it first (frontend first — it is baked into the jar):\n' +
  '  pnpm --filter frontend build\n' +
  '  cd apps/backend && mvn -B clean package -DskipTests\n'

export async function startBackend(port: number, extraEnv: Record<string, string> = {}): Promise<Backend> {
  if (!fs.existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}.\n${BUILD_HINT}`)
  }
  const base = `http://127.0.0.1:${port}`

  // A dead worker's teardown never ran (a crash is exactly the case retries exist for), and its
  // replacement inherits the same parallelIndex — so the same port. Ask whatever holds it to shut
  // down before binding; anything answering here is a leftover of this suite by construction.
  if (await isUp(base)) {
    await stopViaActuator(base)
  }
  // Readiness goes away before the socket does: a JVM told to shut down stops answering the
  // health probe first and releases the port last, and a replacement worker spawned in between
  // died with "Port 8800 was already in use" — which turned one failed test into a cascade of
  // connection-refused failures for every test after it on that worker.
  await waitForPortFree(port)

  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), SCRATCH_PREFIX))
  const javaBin = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java'

  // A developer's machine has the claude CLI signed in; a CI runner has neither that nor an API
  // key, so it cannot execute an agent at all. That is a real difference in what the backend
  // answers, and a suite only ever run on the first kind of machine is exactly how the second kind
  // breaks. CONCENTUS_E2E_NO_CLI=1 points the backend's user.home at an empty directory — which is
  // where it looks for the CLI's login — making the CI condition reproducible locally.
  //
  // -Duser.home rather than an environment variable on purpose: on Windows the JVM reads the home
  // directory from the OS and ignores HOME/USERPROFILE entirely.
  const noCli = process.env.CONCENTUS_E2E_NO_CLI === '1'
  const emptyHome = noCli ? fs.mkdtempSync(path.join(os.tmpdir(), `${SCRATCH_PREFIX}home-`)) : null

  const child = spawn(
    javaBin,
    [
      '--enable-native-access=ALL-UNNAMED',
      ...(emptyHome ? [`-Duser.home=${emptyHome}`] : []),
      '-jar', jar,
      '--spring.profiles.active=desktop',
      `--server.port=${port}`,
    ],
    {
      cwd: dataDir,
      env: {
        ...process.env,
        APP_DATA_DIR: dataDir,
        CONCENTUS_SECRET_KEY: crypto.randomBytes(32).toString('base64'),
        // Cleared alongside the empty home: an API key would give the backend a way to execute
        // agents again, which is the very thing the CI condition is missing.
        ...(noCli ? { ANTHROPIC_API_KEY: '', ANTHROPIC_AUTH_TOKEN: '', CLAUDE_COMMAND: '' } : {}),
        // A spec that needs a licensed backend (or any other env-driven variant) passes it here —
        // CONCENTUS_LICENSE / CONCENTUS_LICENSE_TEST_KEYS for 11-license.spec.ts, so far. Spread
        // last: a caller asking for a specific value is never quietly overridden by the defaults
        // above.
        ...extraEnv,
      },
      stdio: ['ignore', 'inherit', 'inherit'],
    },
  )

  const deadline = Date.now() + 150_000
  while (!(await isUp(base))) {
    if (child.exitCode !== null) {
      throw new Error(`The E2E backend exited during startup (code ${child.exitCode}).`)
    }
    if (Date.now() > deadline) {
      await kill(child)
      throw new Error('The E2E backend never became ready.')
    }
    await sleep(250)
  }

  // Guard against the classic footgun: a jar packaged before the frontend was built carries no
  // UI, serves 404 on /, and every test fails with messages pointing everywhere but the cause.
  const index = await fetch(base)
  const html = index.ok ? await index.text() : ''
  if (!html.includes('id="root"')) {
    await stopViaActuator(base)
    await kill(child)
    throw new Error(`The backend is up but serves no UI — the jar was packaged before the frontend was built.\n${BUILD_HINT}`)
  }

  return {
    baseURL: base,
    async stop() {
      // Politely, so PostgreSQL is stopped by the backend's own shutdown hooks. A hard kill here
      // would orphan the postmaster — postgres re-parents when its supervisor dies — which is how
      // an early version of this suite leaked one per run.
      await stopViaActuator(base)
      const gone = Date.now() + 10_000
      while (child.exitCode === null && Date.now() < gone) await sleep(200)
      if (child.exitCode === null) await kill(child)
    },
  }
}

/**
 * Kills postmasters left by CRASHED runs — the polite stop above never ran for those. Called once
 * from global setup, never while workers are alive: the pattern matches every worker's database —
 * of this suite; another checkout's suite, on another port base, keeps its own.
 */
export function sweepLeftoverPostgres(): void {
  try {
    if (process.platform === 'win32') {
      spawnSync('powershell', [
        '-NoProfile', '-Command',
        "Get-CimInstance Win32_Process -Filter \"Name='postgres.exe'\" | " +
          `Where-Object { $_.CommandLine -match '${SCRATCH_PREFIX}' } | ` +
          'ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }',
      ])
    } else {
      spawnSync('pkill', ['-f', `${SCRATCH_PREFIX}.*postgres`])
    }
  } catch {
    /* best effort — a sweep that cannot run just means yesterday's leak survives another day */
  }
}

async function stopViaActuator(base: string): Promise<void> {
  try {
    await fetch(`${base}/actuator/shutdown`, { method: 'POST' })
  } catch {
    return
  }
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline && (await isUp(base))) await sleep(250)
}

/**
 * Waits until nothing accepts connections on the port — up to a bound, so a port held by
 * something that is not ours still ends in the JVM's own "already in use" error, with the name
 * of the port in it, rather than in a silent hang here.
 */
async function waitForPortFree(port: number): Promise<void> {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline && (await accepts(port))) await sleep(250)
}

function accepts(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.connect({ host: '127.0.0.1', port })
    const done = (open: boolean) => {
      socket.destroy()
      resolve(open)
    }
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
    socket.setTimeout(1000, () => done(false))
  })
}

async function isUp(base: string): Promise<boolean> {
  try {
    return (await fetch(`${base}/actuator/health/readiness`)).status === 200
  } catch {
    return false
  }
}

async function kill(child: ChildProcess): Promise<void> {
  child.kill('SIGKILL')
  await sleep(300)
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
