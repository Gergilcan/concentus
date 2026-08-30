import { spawn, type ChildProcess } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, flowCard, goTo, openApp, test } from './fixtures'
import { apiWrite, minimalFlow, type SavedFlow } from './flows-helpers'

/**
 * Runners, end to end, against the real jar twice over: the per-worker backend is the hub, and
 * the same jar started here in runner mode — `java -jar … runner` — is the runner, with a fake
 * `claude` that speaks just enough stream-json for a run to complete.
 *
 * <p>Register one in the UI and read the token off the one screen it is ever on; start the jar
 * with it; watch the roster turn it online; set a flow to it and run; read the fake's answer in
 * the console and the runner's name on the run; revoke it and watch the process give up with the
 * exit code the docs promise for a refused token. Nothing here touches a real model.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const jar = path.join(here, '..', '..', 'backend', 'target', 'concentus-backend.jar')
const RUNNER = 'e2e box'
const FLOW = 'E2E remote flow'
const ANSWER = 'Hello from the runner'

/** A claude that answers --version and otherwise says ANSWER in stream-json. */
function fakeClaude(dir: string): string {
  const init = '{"type":"system","subtype":"init","model":"fake","tools":[]}'
  const message = `{"type":"assistant","message":{"content":[{"type":"text","text":"${ANSWER}"}],"usage":{"input_tokens":3,"output_tokens":5}}}`
  const result = `{"type":"result","subtype":"success","is_error":false,"result":"${ANSWER}","usage":{"input_tokens":3,"output_tokens":5}}`
  if (process.platform === 'win32') {
    const script = path.join(dir, 'fake-claude.cmd')
    fs.writeFileSync(
      script,
      ['@echo off', 'if "%~1"=="--version" (', '  echo fake-claude 1.0.0', '  exit /b 0', ')', `echo ${init}`, `echo ${message}`, `echo ${result}`, ''].join('\r\n'),
    )
    return script
  }
  const script = path.join(dir, 'fake-claude.sh')
  fs.writeFileSync(
    script,
    ['#!/bin/sh', "if [ \"$1\" = \"--version\" ]; then echo 'fake-claude 1.0.0'; exit 0; fi", `printf '%s\\n' '${init}'`, `printf '%s\\n' '${message}'`, `printf '%s\\n' '${result}'`, ''].join('\n'),
    { mode: 0o755 },
  )
  return script
}

function javaBinary(): string {
  return process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java'
}

test('a runner is registered, connects, executes a flow and is revoked', async ({ page, baseURL }) => {
  test.setTimeout(180_000)
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-e2e-runner-'))
  let runner: ChildProcess | null = null
  const exited = new Promise<number | null>((resolve) => {
    // Resolved by the process's own exit, whenever that is — including at the end, where the
    // token is revoked and the exit code is the assertion.
    const check = () => {
      if (runner) runner.once('exit', (code) => resolve(code))
      else setTimeout(check, 50)
    }
    check()
  })

  try {
    await openApp(page)
    await goTo(page, 'Resources')
    await page.getByRole('button', { name: 'Runners' }).click()
    await expect(page.getByRole('heading', { name: 'Runners' })).toBeVisible()
    await expect(page.getByText('No runners yet.')).toBeVisible()

    // Register: the token is on this one screen and nowhere else.
    await page.getByRole('button', { name: '+ New' }).click()
    await page.getByLabel('Name').fill(RUNNER)
    await page.getByRole('button', { name: 'Create' }).click()
    const shown = page.getByRole('status')
    await expect(shown).toContainText(`Token for "${RUNNER}"`)
    const token = (await shown.locator('code').first().textContent())?.trim() ?? ''
    expect(token).toMatch(/^crn_[A-Za-z0-9]{40}$/)
    // The start commands carry the address this browser reached the hub through, and the token.
    await expect(shown.locator('pre').nth(1)).toContainText(`runner --url ${baseURL}`)
    await expect(shown.locator('pre').nth(1)).toContainText(token)
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(shown).toHaveCount(0)
    const row = page.locator('li').filter({ hasText: RUNNER }).first()
    await expect(row).toBeVisible()
    await expect(row.getByLabel('offline')).toBeVisible()

    // Start the jar in runner mode with the fake CLI, and watch it come online.
    runner = spawn(
      javaBinary(),
      ['-jar', jar, 'runner', '--url', baseURL!, '--token', token, '--name', RUNNER, '--data-dir',
        path.join(scratch, 'data'), '--claude', fakeClaude(scratch), '--max-processes', '1'],
      { cwd: scratch, env: { ...process.env, ANTHROPIC_API_KEY: '' }, stdio: ['ignore', 'inherit', 'inherit'] },
    )
    await expect(row.getByLabel('online')).toBeVisible({ timeout: 45_000 })
    await expect(row).toContainText('Organization')

    // A flow set to that runner, run, and told what to do.
    const listing = (await page.request.get('/api/runners').then((r) => r.json())) as {
      runners: { id: string; name: string; online: boolean; authKind: string | null; claudeVersion: string | null }[]
    }
    const registered = listing.runners.find((r) => r.name === RUNNER)
    expect(registered?.online).toBe(true)
    expect(registered?.claudeVersion).toBe('fake-claude 1.0.0')
    const saved = (await apiWrite(page, 'POST', '/api/flows',
      minimalFlow(FLOW, { runner: registered!.id }))) as unknown as SavedFlow
    const started = (await apiWrite(page, 'POST', `/api/flows/${saved.id}/run`)) as { id: string; runnerName?: string }
    expect(started.runnerName).toBe(RUNNER)
    await apiWrite(page, 'POST', `/api/runs/${started.id}/commands`, { text: 'say hello' })

    // The run in the Studio: the console names the runner and carries the fake's answer, and the
    // executions list wears the runner's name.
    await goTo(page, 'Flows')
    await flowCard(page, FLOW).getByRole('button', { name: 'Open' }).click()
    await page.getByRole('button', { name: 'Show the executions panel' }).click()
    const runRow = page.getByRole('button', { name: new RegExp(FLOW) })
    await expect(runRow).toBeVisible({ timeout: 15_000 })
    await expect(runRow).toContainText('COMPLETED', { timeout: 60_000 })
    await expect(runRow.getByTitle(`Ran on runner ${RUNNER}`)).toHaveText(RUNNER)
    // The console fills from the run's buffered events once the row is selected; under a busy
    // suite that takes longer than the default expectation allows.
    await runRow.click()
    await expect(page.getByText(ANSWER).first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(`Runner '${RUNNER}' — running on its Claude login`).first()).toBeVisible({ timeout: 15_000 })

    // Revoke: the row says so, and the runner, refused at its next handshake, gives up with 3.
    await goTo(page, 'Resources')
    await page.getByRole('button', { name: 'Runners' }).click()
    page.once('dialog', (d) => void d.accept())
    await page.locator('li').filter({ hasText: RUNNER }).first().getByRole('button', { name: 'Revoke' }).click()
    await expect(page.locator('li').filter({ hasText: RUNNER }).first()).toContainText('revoked')
    expect(await Promise.race([exited, new Promise<string>((r) => setTimeout(() => r('still running'), 60_000))])).toBe(3)
    runner = null
  } finally {
    if (runner && runner.exitCode === null) runner.kill()
    // Best effort: on Windows the runner's data directory can stay locked for a moment after the
    // process exits, and a scratch folder left in the temp directory is not a failed test.
    for (let attempt = 0; attempt < 5; attempt++) {
      try {
        fs.rmSync(scratch, { recursive: true, force: true })
        break
      } catch {
        await new Promise((r) => setTimeout(r, 500))
      }
    }
  }
})
