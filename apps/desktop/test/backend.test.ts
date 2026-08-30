import { EventEmitter } from 'node:events'
import * as fs from 'node:fs'
import * as http from 'node:http'
import type { AddressInfo } from 'node:net'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * backend.ts: which port the backend gets, and when an already-running one is adopted instead of
 * started. Nothing here spawns Java — the jar is deliberately absent, the child process module is
 * faked, and the "backend" that gets adopted is a tiny http server on an ephemeral loopback port.
 */

const mocks = vi.hoisted(() => ({
  settings: {} as Record<string, unknown>,
  saveSettings: vi.fn(),
  packaged: false,
  dataDir: '',
  jar: '',
  killOrphans: vi.fn(async () => {}),
  spawn: vi.fn(),
  execFile: vi.fn(),
  runnerToken: null as string | null,
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({ app: { isPackaged: false, getVersion: () => '1.0.0' } }))
vi.mock('node:child_process', () => ({ spawn: mocks.spawn, execFile: mocks.execFile, ChildProcess: class {} }))
vi.mock('../src/paths', async () => {
  const { join } = await import('node:path')
  return {
    isPackaged: () => mocks.packaged,
    dataDir: () => mocks.dataDir,
    backendJar: () => mocks.jar,
    backendLogFile: () => join(mocks.dataDir, 'logs', 'backend.log'),
    javaBinary: () => 'java',
  }
})
vi.mock('../src/settings', () => ({
  loadSettings: () => ({ ...mocks.settings }),
  saveSettings: mocks.saveSettings,
}))
vi.mock('../src/orphans', () => ({ killOrphans: mocks.killOrphans }))
vi.mock('../src/claude-cli', () => ({
  resolveClaudeCli: vi.fn(async () => ({ command: null, path: '', loggedIn: false })),
}))
vi.mock('../src/secret', () => ({ dataKey: () => ({ key: null, reason: 'unreadable', detail: 'not under test' }) }))
vi.mock('../src/api-key', () => ({ loadApiKey: () => null }))
vi.mock('../src/runner-token', () => ({ loadRunnerToken: () => mocks.runnerToken }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import {
  DEFAULT_PORT,
  PortProbes,
  RunningBackend,
  backendLogTail,
  choosePort,
  startBackend,
  stopBackend,
} from '../src/backend'

/** Probes that answer from a table: which ports are free, and what the OS hands out. */
function probes(free: Record<number, boolean>, ephemeral = 54321): PortProbes {
  return {
    isPortFree: vi.fn(async (port: number) => free[port] ?? false),
    ephemeralPort: vi.fn(async () => ephemeral),
  }
}

let servers: http.Server[] = []

/** A stand-in backend: answers the readiness probe with `status`, and records a shutdown request. */
async function backendAnswering(status: number, onShutdown?: () => void): Promise<number> {
  const server = http.createServer((req, res) => {
    if (req.url === '/actuator/health/readiness') {
      res.statusCode = status
      res.end('{}')
    } else if (req.url === '/actuator/shutdown' && req.method === 'POST') {
      res.statusCode = 200
      res.end('{}')
      onShutdown?.()
    } else {
      res.statusCode = 404
      res.end()
    }
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  servers.push(server)
  return (server.address() as AddressInfo).port
}

/** A port nothing is listening on right now — for a spawned "backend" to take. */
async function freePort(): Promise<number> {
  const server = http.createServer()
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address() as AddressInfo
  await new Promise<void>((resolve) => server.close(() => resolve()))
  return port
}

/**
 * A spawn that starts no Java: it reads the port off the backend's own arguments, answers the
 * readiness probe there, and hands back a child with the streams startBackend listens to. What
 * the test then has is the exact env and argv the real backend would have received.
 */
function spawnAnsweringReady(): void {
  mocks.spawn.mockImplementation((_cmd: string, args: string[]) => {
    const port = Number(args.find((a) => a.startsWith('--server.port='))?.slice('--server.port='.length))
    const server = http.createServer((req, res) => {
      res.statusCode = req.url === '/actuator/health/readiness' ? 200 : 404
      res.end('{}')
    })
    server.listen(port, '127.0.0.1')
    servers.push(server)
    return Object.assign(new EventEmitter(), {
      pid: 4242, exitCode: null, signalCode: null, kill: vi.fn(),
      stdout: new EventEmitter(), stderr: new EventEmitter(),
    })
  })
}

/** The environment the last spawn was given. */
function spawnedEnv(): NodeJS.ProcessEnv {
  return (mocks.spawn.mock.calls[0][2] as { env: NodeJS.ProcessEnv }).env
}

beforeEach(() => {
  mocks.settings = {}
  mocks.packaged = false
  mocks.runnerToken = null
  mocks.dataDir = scratchDir('backend')
  mocks.jar = path.join(mocks.dataDir, 'no-such-concentus-backend.jar')
})

afterEach(async () => {
  for (const server of servers) await new Promise<void>((resolve) => server.close(() => resolve()))
  servers = []
  removeDir(mocks.dataDir)
})

describe('choosePort — 8734 preferred, a remembered port only ever a fallback', () => {
  it('prefers 8734 and saves nothing when nothing was remembered', async () => {
    expect(DEFAULT_PORT).toBe(8734)
    const p = probes({ 8734: true })

    expect(await choosePort(p)).toBe(8734)

    expect(mocks.saveSettings).not.toHaveBeenCalled()
    expect(p.ephemeralPort).not.toHaveBeenCalled()
  })

  it('forgets a remembered port the moment 8734 is free again, keeping the other settings', async () => {
    mocks.settings = { port: 51000, skipClaudeCheck: true, wizardCompleted: true }

    expect(await choosePort(probes({ 8734: true, 51000: true }))).toBe(8734)

    expect(mocks.saveSettings).toHaveBeenCalledWith({ skipClaudeCheck: true, wizardCompleted: true })
    expect(mocks.log.info).toHaveBeenCalledWith('Port 8734 is free again; forgetting the remembered port 51000.')
  })

  it('stays on the remembered port while 8734 is still taken, so the app at least stays put', async () => {
    mocks.settings = { port: 51000 }
    const p = probes({ 8734: false, 51000: true })

    expect(await choosePort(p)).toBe(51000)

    expect(mocks.saveSettings).not.toHaveBeenCalled()
    expect(p.ephemeralPort).not.toHaveBeenCalled()
    expect(mocks.log.warn).toHaveBeenCalledWith('Port 8734 is in use; staying on the remembered port 51000.')
  })

  it('takes an ephemeral port and remembers it when 8734 is taken and nothing usable was remembered', async () => {
    mocks.settings = { wizardCompleted: true }

    expect(await choosePort(probes({ 8734: false }, 60123))).toBe(60123)

    expect(mocks.saveSettings).toHaveBeenCalledWith({ wizardCompleted: true, port: 60123 })
    expect(mocks.log.warn).toHaveBeenCalledWith('Port 8734 is in use; moving to 60123 and remembering it.')
  })

  it('replaces a remembered port that is itself taken now', async () => {
    mocks.settings = { port: 51000 }

    expect(await choosePort(probes({ 8734: false, 51000: false }, 60123))).toBe(60123)

    expect(mocks.saveSettings).toHaveBeenCalledWith({ port: 60123 })
  })

  it('a remembered 8734 is not a fallback — it is the port that just failed', async () => {
    mocks.settings = { port: 8734 }
    const p = probes({ 8734: false }, 60123)

    expect(await choosePort(p)).toBe(60123)

    // Probed once, for the preference; never again as a "remembered" port.
    expect(p.isPortFree).toHaveBeenCalledTimes(1)
  })
})

describe('startBackend adopting a backend that is already running', () => {
  it('in a dev run, uses a backend answering ready on the preferred port and owns nothing', async () => {
    const port = await backendAnswering(200)
    mocks.settings = { port }
    const progress = vi.fn()

    const backend = await startBackend(progress)

    expect(backend).toEqual({ port, process: null, shellToken: null })
    expect(mocks.killOrphans).not.toHaveBeenCalled()
    expect(mocks.spawn).not.toHaveBeenCalled()
    expect(progress).toHaveBeenCalledWith(85, 'Using the running dev backend')
    expect(mocks.log.info).toHaveBeenCalledWith(`Adopting the backend already running on 127.0.0.1:${port} (devtools dev loop).`)
  })

  it('does not adopt something that answers but is not ready — it goes on to start its own', async () => {
    const port = await backendAnswering(503)
    mocks.settings = { port }

    await expect(startBackend()).rejects.toThrow(`Backend jar not found at ${mocks.jar}. Build it with: pnpm backend:build`)

    // The jar is checked before the sweep, so a missing build never kills anything.
    expect(mocks.killOrphans).not.toHaveBeenCalled()
    expect(mocks.spawn).not.toHaveBeenCalled()
  })

  it('a packaged app never adopts: what squats its port is exactly what the orphan sweep removes', async () => {
    const port = await backendAnswering(200)
    mocks.settings = { port }
    mocks.packaged = true

    await expect(startBackend()).rejects.toThrow('Backend jar not found')

    expect(mocks.spawn).not.toHaveBeenCalled()
  })
})

describe('startBackend handing the runner to the backend', () => {
  const TOKEN = 'crn_' + 'a1b2c3d4e5'.repeat(4)

  beforeEach(async () => {
    // A jar that exists, so the start gets as far as spawning; its contents are never read.
    mocks.jar = path.join(mocks.dataDir, 'concentus-backend.jar')
    fs.writeFileSync(mocks.jar, 'not a jar')
    // A port of our own as the remembered one, so nothing already running on 8734 gets adopted.
    mocks.settings = { port: await freePort() }
    spawnAnsweringReady()
  })

  it('passes the URL, the token and the name when all three are configured', async () => {
    mocks.settings.runner = { url: 'https://hub.example.com', name: 'office-pc' }
    mocks.runnerToken = TOKEN

    const backend = await startBackend()

    expect(backend.process).not.toBeNull()
    const env = spawnedEnv()
    expect(env.CONCENTUS_RUNNER_URL).toBe('https://hub.example.com')
    expect(env.CONCENTUS_RUNNER_TOKEN).toBe(TOKEN)
    expect(env.CONCENTUS_RUNNER_NAME).toBe('office-pc')
    // The rest of the environment is what it always was.
    expect(env.APP_DATA_DIR).toBe(mocks.dataDir)
    expect(env.CONCENTUS_SHELL_TOKEN).toBe(backend.shellToken)
  })

  it('leaves the name out when none was given', async () => {
    mocks.settings.runner = { url: 'https://hub.example.com' }
    mocks.runnerToken = TOKEN

    await startBackend()

    const env = spawnedEnv()
    expect(env.CONCENTUS_RUNNER_URL).toBe('https://hub.example.com')
    expect(env.CONCENTUS_RUNNER_TOKEN).toBe(TOKEN)
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_NAME')
  })

  it('passes nothing at all when no server is configured', async () => {
    mocks.runnerToken = TOKEN

    await startBackend()

    const env = spawnedEnv()
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_URL')
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_TOKEN')
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_NAME')
  })

  it('passes nothing — not even the URL — when the token cannot be read, and says so', async () => {
    mocks.settings.runner = { url: 'https://hub.example.com', name: 'office-pc' }

    await startBackend()

    const env = spawnedEnv()
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_URL')
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_TOKEN')
    expect(env).not.toHaveProperty('CONCENTUS_RUNNER_NAME')
    expect(mocks.log.warn).toHaveBeenCalledWith(
      'A runner URL is set (https://hub.example.com) but no token could be read; starting without the runner.',
    )
  })
})

describe('stopBackend', () => {
  it('does nothing for nothing', async () => {
    await expect(stopBackend(null)).resolves.toBeUndefined()
  })

  it('leaves an adopted backend running — the developer\'s mvn spring-boot:run is theirs', async () => {
    let shutdownRequested = false
    const port = await backendAnswering(200, () => { shutdownRequested = true })

    await stopBackend({ port, process: null, shellToken: null })

    expect(shutdownRequested).toBe(false)
    expect(mocks.log.info).toHaveBeenCalledWith('Leaving the adopted dev backend running.')
  })

  it('asks its own backend to stop over HTTP — Windows has no SIGTERM — and waits for the exit', async () => {
    const child = Object.assign(new EventEmitter(), { pid: 4242, exitCode: null as number | null, signalCode: null, kill: vi.fn() })
    let asked = false
    const port = await backendAnswering(200, () => { asked = true })
    // A real process exits shortly after accepting the request; this one does so as soon as the
    // shell starts waiting for it — and only if it was asked, which is the assertion.
    const once = child.once.bind(child)
    child.once = ((event: string, listener: (...args: unknown[]) => void) => {
      if (event === 'exit' && asked) setImmediate(() => listener(0, null))
      else once(event, listener)
      return child
    }) as typeof child.once

    await stopBackend({ port, process: child as unknown as RunningBackend['process'], shellToken: 'token' })

    expect(mocks.log.info).toHaveBeenCalledWith('Asking the backend to shut down.')
    expect(mocks.log.info).toHaveBeenCalledWith('Backend stopped.')
    expect(child.kill).not.toHaveBeenCalled()
    expect(mocks.execFile).not.toHaveBeenCalled()
  })

  it('does not ask a backend that has already exited', async () => {
    let shutdownRequested = false
    const port = await backendAnswering(200, () => { shutdownRequested = true })
    const child = Object.assign(new EventEmitter(), { pid: 4242, exitCode: 1, signalCode: null, kill: vi.fn() })

    await stopBackend({ port, process: child as unknown as RunningBackend['process'], shellToken: 'token' })

    expect(shutdownRequested).toBe(false)
  })
})

describe('backendLogTail — what the failure window shows', () => {
  it('says so when the backend never wrote a log', () => {
    expect(backendLogTail()).toBe('(the backend produced no log file)')
  })

  it('returns the end of the log, bounded', () => {
    const file = path.join(mocks.dataDir, 'logs', 'backend.log')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, 'a'.repeat(100) + 'THE END')

    expect(backendLogTail(10)).toBe('aaaTHE END')
    expect(backendLogTail()).toHaveLength(107)
  })
})
