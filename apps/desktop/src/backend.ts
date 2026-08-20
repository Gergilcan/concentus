import { app } from 'electron'
import { ChildProcess, spawn } from 'node:child_process'
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as net from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'
import { backendJar, backendLogFile, dataDir, isPackaged, javaBinary } from './paths'
import { resolveClaudeCli } from './claude-cli'
import { killOrphans } from './orphans'
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
// 150 rather than 400: the poll is a loopback GET that costs microseconds, and its interval is
// pure added latency on every launch — readiness is only ever noticed up to one interval late.
const HEALTH_POLL_INTERVAL_MS = 150
/** Grace period for a clean shutdown before the process is killed outright. */
const SHUTDOWN_TIMEOUT_MS = 8_000

export interface RunningBackend {
  port: number
  /** Null when the backend was ADOPTED — found already running (a dev `mvn spring-boot:run`
   *  with devtools), used but never owned: quitting the shell must not kill it. */
  process: ChildProcess | null
}

/**
 * Startup progress for the splash screen: a percentage and a line saying what is happening.
 *
 * The percentages are real rather than animated — each one is reported when a named startup
 * milestone is actually observed in the backend's output, so the bar moves when the work moves.
 */
export type StartupProgress = (percent: number, label: string) => void

/**
 * The milestones, matched against the backend's stdout as it streams.
 *
 * Log lines are the one progress signal the backend already emits; matching them costs nothing at
 * runtime and needs no channel the backend has to maintain. The fragile part is owned here: if a
 * message changes and a milestone stops matching, the bar simply jumps at the next one that does —
 * degraded, never wrong, because percentages only move forward.
 */
const STARTUP_MILESTONES: ReadonlyArray<{ pattern: RegExp; percent: number; label: string }> = [
  { pattern: /Starting ConcentusApplication/, percent: 12, label: 'Starting the backend…' },
  { pattern: /initialization completed in/, percent: 20, label: 'Preparing services…' },
  { pattern: /Preparing the embedded database for first use/, percent: 24, label: 'First run: preparing the database…' },
  { pattern: /Extracting Postgres/, percent: 28, label: 'Unpacking the database…' },
  { pattern: /postmaster started/, percent: 36, label: 'Starting the database…' },
  { pattern: /Embedded PostgreSQL ready|Using an external PostgreSQL/, percent: 46, label: 'Database ready' },
  { pattern: /Schema .* up to date|Successfully applied|Schema is up to date/, percent: 52, label: 'Checking the schema…' },
  { pattern: /Run persistence ready/, percent: 58, label: 'Opening the stores…' },
  { pattern: /Tomcat started on port/, percent: 68, label: 'Starting the API…' },
  { pattern: /Started ConcentusApplication/, percent: 76, label: 'Restoring runs and triggers…' },
]

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

/**
 * The port to bind, preferring the fixed one and falling back only while it is genuinely taken.
 *
 * The remembered port is a fallback, NOT a new preference — that distinction is the whole bug it
 * fixes. Remembering it as the preference meant one transient collision (a leftover backend the
 * sweep missed, another app holding 8734 for a moment) moved the app to a random high port
 * permanently: every later launch preferred the remembered one, 8734 was never tried again, and
 * every MCP OAuth redirect from then on pointed at an ephemeral port that looks exactly like the
 * "wrong port" it is. Trying DEFAULT_PORT first means the move lasts only as long as the collision.
 */
async function choosePort(): Promise<number> {
  const settings = loadSettings()

  if (await isPortFree(DEFAULT_PORT)) {
    // Drop a stale remembered port, so the next launch does not reconsider a collision that is over.
    if (settings.port !== undefined) {
      log.info(`Port ${DEFAULT_PORT} is free again; forgetting the remembered port ${settings.port}.`)
      const { port: _forgotten, ...rest } = settings
      saveSettings(rest)
    }
    return DEFAULT_PORT
  }

  // Still taken: reuse the port we moved to last time if it is free, so the app at least stays put
  // between launches rather than landing somewhere new on each one.
  const remembered = settings.port
  if (remembered !== undefined && remembered !== DEFAULT_PORT && await isPortFree(remembered)) {
    log.warn(`Port ${DEFAULT_PORT} is in use; staying on the remembered port ${remembered}.`)
    return remembered
  }

  const fallback = await ephemeralPort()
  // Existing MCP authorizations are NOT affected, despite the OAuth redirect carrying the port:
  // grants are stored per MCP url and renewed with a refresh_token grant, which by RFC 6749 §6
  // carries no redirect_uri. A fresh sign-in re-registers the client with the current port. The
  // earlier warning here claimed sign-ins would need redoing, which was never true.
  log.warn(`Port ${DEFAULT_PORT} is in use; moving to ${fallback} and remembering it.`)
  // Persisted so repeated launches under the same collision land on the same port rather than a new
  // one each time. It is only ever a fallback — the block at the top of this function returns to
  // DEFAULT_PORT and forgets this the moment the collision clears.
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

/**
 * In the repo, run a COPY of the jar — never Maven's own artifact.
 *
 * A JVM keeps its jar open for its whole lifetime (classes are read from the zip on demand), and
 * on Windows that open handle blocks `mvn clean` and lets `spring-boot:repackage` half-write the
 * artifact a running app still holds — which is exactly how `target/concentus-backend.jar` kept
 * ending up as "no main manifest attribute" whenever it was rebuilt with the app open. Staging a
 * copy means the Maven artifact is always free to rebuild; the NEXT launch picks the build up.
 *
 * The copy keeps the same file name, because the orphan sweep identifies leftover backends by
 * `concentus-backend.jar` appearing in a java command line — a renamed copy would escape it.
 * Skipped when the source is unchanged (size+mtime), so a normal launch costs one stat, not an
 * 80MB copy. Packaged builds run the installed jar directly: nothing ever rebuilds it in place.
 */
function stageJarForRun(jar: string): string {
  if (isPackaged()) return jar
  const staged = path.join(dataDir(), 'backend-run', 'concentus-backend.jar')
  try {
    const src = fs.statSync(jar)
    const dst = fs.existsSync(staged) ? fs.statSync(staged) : null
    if (!dst || dst.size !== src.size || dst.mtimeMs !== src.mtimeMs) {
      fs.mkdirSync(path.dirname(staged), { recursive: true })
      fs.copyFileSync(jar, staged)
      // Mirror the source's mtime so the skip check above compares like with like next launch.
      fs.utimesSync(staged, src.atime, src.mtime)
      log.info(`Staged a fresh backend jar copy at ${staged}.`)
    }
    return staged
  } catch (err) {
    // Degrade to the old behaviour rather than refusing to start: running the artifact directly
    // worked for months — its only cost is that rebuilding while the app runs corrupts the build.
    log.warn(`Could not stage the backend jar (${err instanceof Error ? err.message : String(err)}); running ${jar} directly.`)
    return jar
  }
}

export async function startBackend(onProgress?: StartupProgress): Promise<RunningBackend> {
  let reported = 0
  // Monotonic by construction: a late-matching early milestone must never pull the bar backwards.
  const advance = (percent: number, label: string) => {
    if (percent <= reported) return
    reported = percent
    try { onProgress?.(percent, label) } catch { /* the splash is cosmetic; never fail a start over it */ }
  }

  // The live backend loop: a dev backend already running on the preferred port (started with
  // `pnpm dev:backend`, where Spring devtools hot-restarts it on every recompile) is adopted
  // rather than duplicated — Java edits then reload INSIDE the real app, with no jar rebuild.
  // Dev only: packaged installs own their backend outright, and something squatting the port
  // there is exactly what the orphan sweep below exists to remove.
  if (!isPackaged()) {
    const preferred = loadSettings().port ?? DEFAULT_PORT
    if (await probeReady(preferred)) {
      log.info(`Adopting the backend already running on 127.0.0.1:${preferred} (devtools dev loop).`)
      advance(85, 'Using the running dev backend')
      return { port: preferred, process: null }
    }
  }

  const artifact = backendJar()
  if (!fs.existsSync(artifact)) {
    throw new Error(`Backend jar not found at ${artifact}. Build it with: pnpm backend:build`)
  }

  // Before choosing a port, not after: a leftover backend from a hard-killed previous run holds
  // the preferred port, and sweeping it first is what lets this launch keep 8734 instead of
  // drifting to an ephemeral port. It also removes the orphaned embedded postgres that would
  // otherwise fail this start with "already running" on our own data directory.
  advance(2, 'Checking for leftover processes…')
  await killOrphans()

  // After the sweep, deliberately: a leftover backend may still hold the previous staged copy,
  // and copying over a locked file fails on Windows exactly like the problem this solves.
  const jar = stageJarForRun(artifact)

  const java = javaBinary()
  const port = await choosePort()
  const data = dataDir()
  const settings = loadSettings()
  advance(4, 'Locating the claude CLI…')
  const claude = await resolveClaudeCli(settings.claudeCommand)
  advance(6, 'Starting the Java runtime…')

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    // The login shell's PATH, so the backend can reach the same tools the user can — claude, git,
    // node — none of which a desktop launcher's environment necessarily contains.
    PATH: claude.path,
    APP_DATA_DIR: data,
    CONCENTUS_SECRET_KEY: masterSecret(),
    // MCP_OAUTH_REDIRECT_BASE is deliberately NOT set: the backend derives the OAuth callback
    // from each request's own Host, which is right both here (the window loads the UI from the
    // backend's port) and in desktop:dev (the browser sits on Vite's port, which proxies /api).
    // Blank is meaningful and safe: the backend then falls back to its own PATH resolution.
    CLAUDE_COMMAND: claude.command ?? '',
    // What the header's version chip shows. Packaged only: a dev run's package.json version is
    // a placeholder, and showing it would label every dev build as some unrelated release.
    CONCENTUS_APP_VERSION: isPackaged() ? app.getVersion() : '',
  }

  // Native access for onnxruntime's System.load is granted by the jar's own manifest
  // (`Enable-Native-Access: ALL-UNNAMED`, set in apps/backend/pom.xml), NOT by a command-line flag.
  // Both grant the same thing, but the flag also sets the jdk.module.enable.native.access system
  // property, which the runtime's baked-in CDS archive was dumped without — so every launch printed
  // "Mismatched values for property jdk.module.enable.native.access" and "Disabling optimized
  // module handling". The manifest route grants access with no property to disagree about.
  const args: string[] = []

  // A packaged build runs the CDS-extracted layout the payload script staged: a thin
  // `concentus-backend.jar` plus `lib/`, launched relatively from its own directory because that is
  // the class path the archive records.
  const packagedRun = isPackaged()
  const backendDir = path.dirname(jar)
  const archive = cdsArchive()
  if (packagedRun) {
    if (fs.existsSync(archive)) args.push(`-XX:SharedArchiveFile=${archive}`)
    args.push('-jar', path.basename(jar))
  } else {
    args.push('-jar', jar)
  }
  args.push('--spring.profiles.active=desktop', `--server.port=${port}`)

  log.info(`Starting backend: ${java} ${args.join(' ')}`)
  const child = spawn(java, args, {
    env,
    // In the repo: anchored at the data directory rather than wherever the launcher happened to
    // start us, so the backend's optional `.env` import cannot pick up a stray file from an
    // unrelated folder. Packaged: the backend payload directory, because the CDS archive's
    // relative class path resolves against it — and it contains no `.env` to stray into.
    cwd: packagedRun ? backendDir : data,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })

  // The backend logs to its own file via the desktop profile; this catches what escapes Logback —
  // JVM crashes, missing-class errors, anything that happens before logging is configured.
  // The same stream drives the splash screen's progress bar: startup milestones are recognised in
  // the log lines as they happen, which is what makes the bar honest.
  child.stdout?.on('data', (chunk: Buffer) => {
    const text = chunk.toString()
    log.info(`[backend] ${text.trimEnd()}`)
    for (const milestone of STARTUP_MILESTONES) {
      if (milestone.pattern.test(text)) advance(milestone.percent, milestone.label)
    }
  })
  child.stderr?.on('data', (chunk: Buffer) => log.warn(`[backend] ${chunk.toString().trimEnd()}`))

  await waitForReady(port, child)
  advance(85, 'Backend ready')
  log.info(`Backend ready on 127.0.0.1:${port}`)
  // Once the app is up and the user is being served, not before: training is a whole second
  // application start of its own, and it must never sit between the click and the window.
  if (packagedRun && !fs.existsSync(archive)) trainCdsArchive(java, backendDir, archive, env)
  return { port, process: child }
}

// --------------------------------------------------------------------- class-data sharing

/**
 * Where this build's class archive lives.
 *
 * In the data directory rather than beside the jar, for two reasons. A machine-wide install puts
 * the payload under Program Files, which a normal user cannot write to. And the file is derived
 * data — losing it costs a slower start and nothing else, so it belongs with the data the user can
 * safely delete.
 *
 * Keyed by version because an archive is only valid for the exact jar it was trained against: an
 * update changes the jar, and a name that changes with it retires the old archive automatically
 * instead of leaving a silently-rejected one behind.
 */
function cdsArchive(): string {
  return path.join(dataDir(), 'cds', `backend-${app.getVersion()}.jsa`)
}

/**
 * Dumps the archive the NEXT launch will map in, in the background, once per installed version.
 *
 * <p>Why here and not in the installer, which is where it used to be: CDS validates every class
 * path entry against the timestamp recorded at dump time, and packaging rewrites the jar's
 * timestamp after the build-time training run. The shipped archive was therefore rejected on every
 * installed launch — "This file is not the one used while building the shared archive file:
 * 'concentus-backend.jar', timestamp has changed" — so the installer carried 60 MB that could never
 * be used, and printed two warnings on every start to say so. Training on the machine that will use
 * it is the only place the jar's timestamp is the final one.
 *
 * <p>The run must mirror the real launch exactly — same JVM, same working directory, same relative
 * `-jar` — because those are what the archive records. It deliberately does NOT use the desktop
 * profile: that starts embedded PostgreSQL against the data directory, which the backend now
 * serving the user already holds. The default profile tolerates an absent database by design, so a
 * scratch data directory and a fast connection timeout are enough.
 *
 * <p>Best effort throughout. Every failure path leaves the app exactly as it is today — starting
 * without an archive — so nothing here can turn a slow start into a broken one.
 */
function trainCdsArchive(java: string, backendDir: string, archive: string, env: NodeJS.ProcessEnv): void {
  let scratch: string
  try {
    fs.mkdirSync(path.dirname(archive), { recursive: true })
    retireOldArchives(archive)
    scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-cds-'))
  } catch (err) {
    log.warn(`Could not prepare the class archive directory: ${message(err)}`)
    return
  }

  // Dumped to a temporary name and renamed on success: a training run killed by the user quitting
  // must not leave a half-written archive that the next launch would try to map.
  const partial = `${archive}.partial`
  log.info('Training the class-data-sharing archive in the background (once for this version).')
  const child = spawn(java, [
    `-XX:ArchiveClassesAtExit=${partial}`,
    '-Dspring.context.exit=onRefresh',
    '-jar', 'concentus-backend.jar',
    '--server.port=0',
    '--spring.main.banner-mode=off',
    // No database here and none wanted: the stores probe one at startup and would otherwise spend
    // their full timeout each. Training-only — the desktop profile supplies its own DataSource.
    '--spring.datasource.hikari.connection-timeout=250',
  ], {
    cwd: backendDir,
    env: { ...env, APP_DATA_DIR: scratch },
    stdio: 'ignore',
    windowsHide: true,
  })

  child.on('error', (err) => {
    log.warn(`Class archive training could not start: ${message(err)}`)
    cleanUp(scratch, partial)
  })

  child.on('exit', () => {
    // The exit code is deliberately not consulted: `spring.context.exit=onRefresh` stops the
    // application by design and does not promise a zero. Whether an archive exists is the only
    // fact that matters.
    try {
      if (fs.existsSync(partial)) {
        fs.renameSync(partial, archive)
        log.info(`Class archive ready; the next launch will start faster (${archive}).`)
      } else {
        log.warn('Class archive training produced nothing; starts stay as they are.')
      }
    } catch (err) {
      log.warn(`Could not store the class archive: ${message(err)}`)
    } finally {
      cleanUp(scratch, partial)
    }
  })
}

/** Archives from other versions of the app, which can never validate against this jar. */
function retireOldArchives(current: string): void {
  const dir = path.dirname(current)
  if (!fs.existsSync(dir)) return
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name)
    if (file === current) continue
    try {
      fs.rmSync(file, { force: true })
    } catch { /* a leftover archive wastes disk; it cannot break a start */ }
  }
}

function cleanUp(scratch: string, partial: string): void {
  try { fs.rmSync(scratch, { recursive: true, force: true }) } catch { /* temp dir */ }
  try { fs.rmSync(partial, { force: true }) } catch { /* already renamed */ }
}

function message(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
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
  if (child === null) {
    // Adopted, not owned: the developer's `mvn spring-boot:run` keeps running for the next launch.
    log.info('Leaving the adopted dev backend running.')
    return
  }
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
