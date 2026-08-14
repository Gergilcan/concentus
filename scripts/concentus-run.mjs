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
  const args = { flowPath: null, input: null, url: null, jar: DEFAULT_JAR, timeoutSeconds: 1800, quiet: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const next = () => argv[++i]
    if (arg === '--input') args.input = next()
    else if (arg === '--url') args.url = (next() ?? '').replace(/\/$/, '')
    else if (arg === '--jar') args.jar = next()
    else if (arg === '--timeout') args.timeoutSeconds = Number(next())
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
        // Credential storage refuses to start without one. A fresh key per run is right for a
        // throwaway data directory: nothing encrypted here is meant to outlive the process.
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

async function api(base, path_, init) {
  const res = await fetch(`${base}/api${path_}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  const text = await res.text()
  if (!res.ok) {
    let message = text
    try {
      message = JSON.parse(text).error ?? text
    } catch {
      /* not JSON — the body is the message */
    }
    throw new Error(`${init?.method ?? 'GET'} ${path_} failed (${res.status}): ${message}`)
  }
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
