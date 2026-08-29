#!/usr/bin/env node
/**
 * Runs a Concentus flow from a terminal, with no desktop app.
 *
 *   node scripts/concentus-run.mjs <flow.json> [--input "first message"] [options]
 *
 * What it does: boots the backend jar on a free port (or attaches to one you point it at),
 * imports the flow, starts a run, prints its output as it arrives, and exits with a code that
 * says what happened. That makes a flow usable from CI, cron, or a shell pipeline — the same
 * flow the app runs, without the app.
 *
 * Two deliberate choices worth knowing about:
 *
 * - It POLLS `/api/runs/{id}` rather than opening the WebSocket the UI uses. The stream buys
 *   nothing here (nobody is watching a terminal for sub-second latency), and polling has no
 *   client to keep alive, no reconnect logic, and works on any Node without a dependency.
 *
 * - It NEVER answers for you. A run that stops to ask for approval, or asks a question, or is
 *   waiting for its first instruction, exits 2 — "a human is needed" — instead of guessing.
 *   Headless does not mean unattended-at-any-cost.
 */

import { spawn } from 'node:child_process'
import * as fs from 'node:fs'
import * as net from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.join(here, '..')
const DEFAULT_JAR = path.join(repoRoot, 'apps', 'backend', 'target', 'concentus-backend.jar')

/**
 * Exit codes, and why these.
 *
 * A shell needs to tell "it worked" from "it broke" from "it needs you" — three different next
 * actions. Lumping the last two together is what makes a CI job either ignore real failures or
 * page someone because an agent asked a question.
 */
export const EXIT = {
  completed: 0,
  failed: 1,
  /** Stopped for a human: approval, a question, or waiting for its first instruction. */
  needsHuman: 2,
  timedOut: 3,
  /** Bad arguments, a missing file, a backend that never came up. */
  usage: 4,
}

/** The exit code a finished (or stuck) run deserves. */
export function exitCodeFor(status) {
  switch (status) {
    case 'COMPLETED':
      return EXIT.completed
    case 'ERROR':
    case 'TERMINATED':
      return EXIT.failed
    case 'AWAITING_APPROVAL':
    case 'AWAITING_ANSWER':
    case 'IDLE':
      return EXIT.needsHuman
    default:
      // STARTING/RUNNING never reach here — the caller only asks once a run has settled.
      return EXIT.failed
  }
}

/** Statuses that mean the run is not going to move on its own. */
export function isSettled(status) {
  return status !== 'STARTING' && status !== 'RUNNING'
}

export function parseArgs(argv) {
  const args = {
    flowPath: null,
    input: null,
    url: null,
    jar: DEFAULT_JAR,
    timeoutSeconds: 1800,
    quiet: false,
    // Credentials default to the environment, because a CI job that must pass a password on the
    // command line is a CI job with a password in its logs.
    email: process.env.CONCENTUS_EMAIL ?? null,
    password: process.env.CONCENTUS_PASSWORD ?? null,
    // A service account token (Resources → Service accounts) is the right credential for a
    // machine: its own name and role rather than a person's account, revocable on its own, and
    // no session to sign into. Environment only, on purpose — there is no --token flag to leave
    // it in the process list.
    token: process.env.CONCENTUS_TOKEN ?? null,
  }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const next = () => argv[++i]
    if (arg === '--input') args.input = next()
    else if (arg === '--url') args.url = (next() ?? '').replace(/\/$/, '')
    else if (arg === '--jar') args.jar = next()
    else if (arg === '--timeout') args.timeoutSeconds = Number(next())
    else if (arg === '--email') args.email = next()
    else if (arg === '--password') args.password = next()
    else if (arg === '--quiet') args.quiet = true
    else if (arg === '--help' || arg === '-h') args.help = true
    else if (arg.startsWith('--')) throw new Error(`Unknown option ${arg}`)
    else if (args.flowPath === null) args.flowPath = arg
    else throw new Error(`Unexpected argument ${arg}`)
  }
  if (!args.help && !args.flowPath) throw new Error('A flow JSON file is required.')
  if (!Number.isFinite(args.timeoutSeconds) || args.timeoutSeconds <= 0) {
    throw new Error('--timeout takes a number of seconds.')
  }
  return args
}

const USAGE = `Usage: node scripts/concentus-run.mjs <flow.json> [options]

  --input "text"   First message for the run. Without it, a flow whose trigger is manual has
                   nothing to do and exits ${EXIT.needsHuman} (needs a human).
  --url URL        Use a backend that is already running instead of booting the jar.
  --jar PATH       Backend jar to boot (default apps/backend/target/concentus-backend.jar).
  --timeout SECS   Give up waiting and exit ${EXIT.timedOut} (default 1800).
  --quiet          Only print the final answer and the outcome line.
  --email ADDRESS  Account to sign in as. Defaults to CONCENTUS_EMAIL.
  --password PASS  Its password. Defaults to CONCENTUS_PASSWORD; prefer the variable, since an
                   argument ends up in the job's log and in the process list.

Or, for a machine: CONCENTUS_TOKEN=csa_… — a service account token minted under Resources →
Service accounts. It replaces the sign-in entirely and is only ever read from the environment.

Exit codes: ${EXIT.completed} completed · ${EXIT.failed} failed · ${EXIT.needsHuman} needs a human ·
            ${EXIT.timedOut} timed out · ${EXIT.usage} usage/setup problem
`

// ---------------------------------------------------------------- backend

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

/** A free port, bound to loopback — never 0.0.0.0, which trips the Windows firewall prompt. */
function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close(() => resolve(port))
    })
  })
}

async function isUp(base) {
  try {
    return (await fetch(`${base}/actuator/health/readiness`)).status === 200
  } catch {
    return false
  }
}

/**
 * Boots the jar on a free port with a scratch data directory.
 *
 * A throwaway data dir on purpose: a headless run must not adopt (or corrupt) the desktop app's
 * database, and CI wants a clean slate every time. The flow travels in the request, so nothing
 * of value lives there.
 */
async function bootBackend(jar, log) {
  if (!fs.existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}.\nBuild it first: pnpm desktop:build`)
  }
  const port = await freePort()
  const base = `http://127.0.0.1:${port}`
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-cli-'))
  const javaBin = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java'

  log(`Starting the backend on ${base} …`)
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
        // Optional for the backend, but a fresh key per run costs nothing and means anything
        // stored in a throwaway data directory is sealed rather than in the clear: nothing
        // encrypted here is meant to outlive the process.
        CONCENTUS_SECRET_KEY:
          process.env.CONCENTUS_SECRET_KEY ?? (await import('node:crypto')).randomBytes(32).toString('base64'),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  )

  // The backend's log is noise in a pipeline while things go well, and the only useful thing in
  // the world when they do not — so it is kept, not printed, and shown if startup fails.
  let tail = ''
  const keep = (chunk) => {
    tail = (tail + chunk).slice(-2000)
  }
  child.stdout?.on('data', keep)
  child.stderr?.on('data', keep)

  const deadline = Date.now() + 180_000
  while (!(await isUp(base))) {
    if (child.exitCode !== null) {
      throw new Error(`The backend exited during startup (code ${child.exitCode}).\n${tail.trim()}`)
    }
    if (Date.now() > deadline) {
      child.kill('SIGKILL')
      throw new Error(`The backend never became ready.\n${tail.trim()}`)
    }
    await sleep(300)
  }

  return {
    base,
    async stop() {
      // Politely: the backend stops its embedded PostgreSQL in its own shutdown hooks, and a hard
      // kill would leave a re-parented postmaster behind.
      try {
        await fetch(`${base}/actuator/shutdown`, { method: 'POST' })
      } catch {
        /* already gone */
      }
      const gone = Date.now() + 10_000
      while (child.exitCode === null && Date.now() < gone) await sleep(200)
      if (child.exitCode === null) child.kill('SIGKILL')
    },
  }
}

// ---------------------------------------------------------------- api

/**
 * The cookies this process has been given: the session, and the CSRF token.
 *
 * <p>A jar of exactly two, kept here rather than threaded through every call — the backend
 * requires an account for every request, and node's fetch keeps no cookies on its own.
 */
const jar = new Map()

/** Folds a response's Set-Cookie headers into the jar, replacing by name. */
function remember(res) {
  for (const raw of res.headers.getSetCookie?.() ?? []) {
    const [pair] = raw.split(';')
    const at = pair.indexOf('=')
    if (at > 0) jar.set(pair.slice(0, at).trim(), pair.slice(at + 1))
  }
}

function cookieHeader() {
  return [...jar].map(([name, value]) => `${name}=${value}`).join('; ')
}

/**
 * The service account token this process runs as, when it has one instead of a sign-in.
 *
 * <p>Sent as a bearer on every request. The backend turns it into a principal — the account's
 * organization and role — before the session's own authentication runs, and exempts the request
 * from CSRF: the header is the proof of intent a CSRF token exists to supply, since no page in a
 * browser can attach one cross-site. So there is no cookie jar to fill and nothing to sign into.
 */
let bearerToken = null

/**
 * Signs in, so the rest of this script can do anything at all.
 *
 * <p>Every endpoint below needs an account — there is no unauthenticated mode any more, and there
 * was no honest way to keep one: a backend a script can drive without credentials is a backend
 * anybody who can reach it can drive. The CSRF token comes back as a readable cookie and goes back
 * out as a header, exactly as the browser does it.
 *
 * <p>With a service account token there is nothing to sign into: the token is checked once here,
 * so a revoked one fails as a setup problem before any run starts rather than as a failed run.
 */
async function signIn(base, args) {
  if (args.token) {
    bearerToken = args.token
    const res = await fetch(`${base}/api/account/session`, { headers: authHeaders() })
    const text = await res.text()
    let session = null
    try {
      session = JSON.parse(text)
    } catch {
      /* not JSON — reported below */
    }
    if (!res.ok || !session?.signedIn) {
      throw new Error(
        `CONCENTUS_TOKEN was refused (${res.status}): ${errorMessage(text) || 'not a working service account token'}`,
      )
    }
    return session
  }
  if (!args.email || !args.password) {
    throw new Error(
      'This backend requires an account. Set CONCENTUS_TOKEN (a service account token from '
        + 'Resources → Service accounts), or CONCENTUS_EMAIL and CONCENTUS_PASSWORD (or pass '
        + '--email and --password).',
    )
  }
  // One GET first, to be given the CSRF cookie the sign-in has to echo back. A cookie alone
  // would not prove intent — a third-party page can cause a browser to send cookies but cannot
  // read them — so the token travels as a header, and this is where it comes from.
  remember(await fetch(`${base}/api/account/session`))
  const res = await fetch(`${base}/api/account/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Cookie: cookieHeader(),
      ...(csrfToken() ? { 'X-XSRF-TOKEN': csrfToken() } : {}),
    },
    body: JSON.stringify({ email: args.email, password: args.password }),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`Could not sign in as ${args.email} (${res.status}): ${errorMessage(text)}`)
  remember(res)
  return JSON.parse(text)
}

/** The CSRF token out of the jar, which Spring expects echoed in a header on every write. */
function csrfToken() {
  const value = jar.get('XSRF-TOKEN')
  return value ? decodeURIComponent(value) : null
}

/** The bearer header when this process runs as a service account; nothing otherwise. */
function authHeaders() {
  return bearerToken ? { Authorization: `Bearer ${bearerToken}` } : {}
}

/** The backend's own words for a refusal — its {"error": …} — or the body as it came, when it is not JSON. */
function errorMessage(text) {
  try {
    return JSON.parse(text).error ?? text
  } catch {
    return text
  }
}

async function api(base, path_, init) {
  const token = csrfToken()
  const res = await fetch(`${base}/api${path_}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...(jar.size ? { Cookie: cookieHeader() } : {}),
      ...(token && init?.method && init.method !== 'GET' ? { 'X-XSRF-TOKEN': token } : {}),
      ...(init?.headers ?? {}),
    },
  })
  remember(res)
  const text = await res.text()
  if (!res.ok) throw new Error(`${init?.method ?? 'GET'} ${path_} failed (${res.status}): ${errorMessage(text)}`)
  return text ? JSON.parse(text) : null
}

// ---------------------------------------------------------------- main

async function main(argv) {
  let args
  try {
    args = parseArgs(argv)
  } catch (e) {
    process.stderr.write(`${e.message}\n\n${USAGE}`)
    return EXIT.usage
  }
  if (args.help) {
    process.stdout.write(USAGE)
    return EXIT.completed
  }

  const log = args.quiet ? () => {} : (m) => process.stderr.write(`${m}\n`)

  let flow
  try {
    flow = JSON.parse(fs.readFileSync(args.flowPath, 'utf8'))
  } catch (e) {
    process.stderr.write(`Could not read ${args.flowPath}: ${e.message}\n`)
    return EXIT.usage
  }

  let backend = null
  try {
    if (!args.url) backend = await bootBackend(args.jar, log)
  } catch (e) {
    process.stderr.write(`${e.message}\n`)
    return EXIT.usage
  }
  const base = args.url ?? backend.base

  try {
    await signIn(base, args)
  } catch (e) {
    // A setup problem rather than a failed run: nothing was started, and what has to change is the
    // invocation.
    process.stderr.write(`${e.message}\n`)
    if (backend) await backend.stop()
    return EXIT.usage
  }

  try {
    // The flow travels in the request rather than being saved first: a headless run should not
    // leave a flow behind in whatever database it happened to use.
    const run = await api(base, '/runs', { method: 'POST', body: JSON.stringify(flow) })
    log(`Run ${run.id} started (${flow.name ?? 'flow'}).`)
    if (args.input) {
      await api(base, `/runs/${run.id}/commands`, {
        method: 'POST',
        body: JSON.stringify({ text: args.input }),
      })
    }
    return await follow(base, run.id, args, log)
  } catch (e) {
    process.stderr.write(`${e.message}\n`)
    return EXIT.failed
  } finally {
    if (backend) await backend.stop()
  }
}

/** Prints the run's events as they arrive and returns the exit code its outcome deserves. */
async function follow(base, runId, args, log) {
  const deadline = Date.now() + args.timeoutSeconds * 1000
  let printed = 0
  let last = null

  for (;;) {
    const detail = await api(base, `/runs/${runId}`)
    for (const event of detail.events.slice(printed)) {
      // stdout is the run's output; everything else (progress, errors) goes to stderr, so a
      // pipeline can take just the answer.
      const line = `${event.agent ? `[${event.agent}] ` : ''}${event.text ?? ''}`
      if (event.type === 'agent_message') process.stdout.write(`${line}\n`)
      else log(line)
    }
    printed = detail.events.length
    last = detail.run

    if (isSettled(last.status)) break
    if (Date.now() > deadline) {
      log(`Timed out after ${args.timeoutSeconds}s — the run is still ${last.status}.`)
      return EXIT.timedOut
    }
    await sleep(1000)
  }

  const code = exitCodeFor(last.status)
  const summary = code === EXIT.needsHuman
    ? `${last.status} — this run needs a human (approve it, answer it, or give it an input).`
    : `${last.status}${last.error ? `: ${last.error}` : ''}`
  log(`Run ${runId} finished: ${summary}`)
  return code
}

// Only when run as a program: the exports above are imported by the tests.
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (e) => {
      process.stderr.write(`${e?.stack ?? e}\n`)
      process.exit(EXIT.failed)
    },
  )
}
