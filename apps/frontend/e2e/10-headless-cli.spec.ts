import { spawn } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { E2E_EMAIL, E2E_PASSWORD, expect, signedInRequest, test } from './fixtures'

/**
 * The headless CLI against a real backend: `scripts/concentus-run.mjs` imports a flow, starts a
 * run, prints what it says, and exits with a code that means something.
 *
 * It runs against the worker's own backend (`--url`) rather than booting a second jar — the boot
 * path is the same code the fixture already exercises, and paying for another JVM per test would
 * buy nothing.
 *
 * What is NOT asserted: a completed run. Finishing one needs a model call, which this suite never
 * makes. The deterministic and genuinely useful case is the other one — a flow with nothing to do
 * settles as "needs a human", which is exactly the answer a CI job must be able to tell apart
 * from success.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const script = path.join(here, '..', '..', '..', 'scripts', 'concentus-run.mjs')

/** The smallest flow that compiles: a manual trigger wired to one coordinator. */
const FLOW = {
  name: 'E2E headless flow',
  mode: 'local',
  nodes: [
    { id: 'in-1', type: 'input', data: { mode: 'manual', prompt: '' } },
    {
      id: 'a-1',
      type: 'agent',
      role: 'coordinator',
      data: { name: 'Coordinator', model: 'claude-opus-4-8', systemPrompt: 'Say hello.' },
    },
  ],
  edges: [{ id: 'e1', source: 'in-1', target: 'a-1' }],
}

function runCli(args: string[]): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [script, ...args], { stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (c) => (stdout += c))
    child.stderr.on('data', (c) => (stderr += c))
    child.on('close', (code) => resolve({ code: code ?? -1, stdout, stderr }))
  })
}

test('runs a flow against a live backend and exits with what really happened', async ({ baseURL, request }) => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-cli-spec-')), 'flow.json')
  fs.writeFileSync(file, JSON.stringify(FLOW))

  // Which outcome is correct depends on the machine, so the machine is asked rather than assumed.
  // A developer's laptop has the claude CLI signed in and can start runs; a CI runner has neither
  // that nor an API key, and the backend refuses to start one at all. Both are real, and the point
  // of this test is that the CLI reports each one honestly instead of exiting 0 regardless.
  const status = await signedInRequest(request, baseURL!, '/api/auth/status')
  // The CLI signs in like anything else does: the backend requires an account for every request,
  // and a script that could skip that would be a hole rather than a convenience.
  const result = await runCli([
    file,
    '--url', baseURL!,
    '--timeout', '60',
    '--email', E2E_EMAIL,
    '--password', E2E_PASSWORD,
  ])

  if (status.authenticated) {
    // 2 is "stopped for a human", which is what a manual flow given no input is: it started, it
    // has no instruction, and nothing here will invent one.
    expect(result.code, result.stderr).toBe(2)
    expect(result.stderr).toContain('Run ')
    expect(result.stderr).toMatch(/needs a human/)
  } else {
    // Nothing on this machine can execute an agent, so the run never starts. That is a failure of
    // the invocation, and it exits 1 carrying the backend's own words rather than a bare code —
    // which is the difference between a CI job someone can fix and one that just says "1".
    expect(result.code, result.stderr).toBe(1)
    expect(result.stderr).toContain('Not signed in')
  }
})

test('refuses a flow file that is not there, without booting anything', async () => {
  const result = await runCli(['definitely-not-a-flow.json', '--url', 'http://127.0.0.1:1'])

  expect(result.code).toBe(4)
  expect(result.stderr).toContain('Could not read')
})

test('explains itself with --help', async () => {
  const result = await runCli(['--help'])

  expect(result.code).toBe(0)
  expect(result.stdout).toContain('--input')
  expect(result.stdout).toContain('needs a human')
})
