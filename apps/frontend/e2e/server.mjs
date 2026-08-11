#!/usr/bin/env node
/**
 * The backend the UI tests run against: the real jar, a real embedded PostgreSQL, a scratch data
 * directory. Started by Playwright's webServer block, which polls the readiness URL; this script's
 * own job is to spawn the process, refuse early when the jar is missing or built without the UI,
 * and stop the backend cleanly when Playwright tears it down.
 *
 * Against the jar rather than the Vite dev server, deliberately: the jar is what users run. It
 * exercises the static serving, the same-origin /api and /ws, the schema migrations and the
 * library seeding — the dev server would exercise a proxy that exists only in development.
 */
import { spawn, spawnSync } from 'node:child_process'
import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.join(here, '..', '..', '..')
const jar = path.join(repoRoot, 'apps', 'backend', 'target', 'concentus-backend.jar')
const port = Number(process.env.E2E_PORT ?? 8799)
const base = `http://127.0.0.1:${port}`

if (!fs.existsSync(jar)) {
  console.error(
    `\nBackend jar not found at ${jar}.\n` +
      `Build it first (frontend first — it is baked into the jar):\n` +
      `  pnpm --filter frontend build\n` +
      `  cd apps/backend && mvn -B clean package -DskipTests\n`,
  )
  process.exit(1)
}

const javaBin = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java'

// Heal before starting: a crashed or hard-killed previous run can leave its postmaster behind
// (postgres re-parents when its supervisor dies), and those add up silently. Ours are the only
// postgres processes whose command line names a concentus-e2e- scratch directory.
function sweepLeftoverPostgres() {
  try {
    if (process.platform === 'win32') {
      spawnSync('powershell', [
        '-NoProfile', '-Command',
        "Get-CimInstance Win32_Process -Filter \"Name='postgres.exe'\" | " +
          "Where-Object { $_.CommandLine -match 'concentus-e2e-' } | " +
          'ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }',
      ])
    } else {
      spawnSync('pkill', ['-f', 'concentus-e2e-.*postgres'])
    }
  } catch {
    /* best effort — a sweep that cannot run just means yesterday's leak survives another day */
  }
}
sweepLeftoverPostgres()

// Scratch and disposable: every run starts from an empty database, so tests can rely on the
// library seeding and on their own creations without inheriting a previous run's state.
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-e2e-'))

const child = spawn(
  javaBin,
  [
    '--enable-native-access=ALL-UNNAMED',
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
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  },
)

child.on('exit', (code) => {
  // If the backend dies, the webServer must die with it — a silent zombie script would leave
  // Playwright waiting the full timeout on a URL nothing will ever answer.
  process.exit(code ?? 1)
})

/** Ask the backend to stop, then stop waiting politely. */
async function shutdown() {
  try {
    await fetch(`${base}/actuator/shutdown`, { method: 'POST' })
  } catch {
    /* not up yet, or already gone — the kill below settles it */
  }
  const deadline = Date.now() + 8_000
  while (child.exitCode === null && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 200))
  }
  if (child.exitCode === null) child.kill('SIGKILL')
  process.exit(0)
}
process.on('SIGTERM', () => void shutdown())
process.on('SIGINT', () => void shutdown())

// Guard against the classic footgun: a jar packaged before the frontend was built carries no UI,
// serves 404 on /, and every test fails with messages pointing everywhere but the cause.
const deadline = Date.now() + 150_000
const poll = setInterval(async () => {
  if (Date.now() > deadline) {
    clearInterval(poll)
    console.error('The backend never became ready; giving up.')
    void shutdown()
    return
  }
  let ready = false
  try {
    ready = (await fetch(`${base}/actuator/health/readiness`)).status === 200
  } catch {
    return // still starting
  }
  if (!ready) return
  clearInterval(poll)
  const index = await fetch(base)
  const html = index.ok ? await index.text() : ''
  if (!html.includes('id="root"')) {
    console.error(
      '\nThe backend is up but serves no UI — the jar was packaged before the frontend was ' +
        'built.\nRun: pnpm --filter frontend build && cd apps/backend && mvn -B clean package -DskipTests\n',
    )
    void shutdown()
  } else {
    console.log(`E2E backend ready on ${base} (data: ${dataDir})`)
  }
}, 300)
