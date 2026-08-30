import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, type APIResponse, type Browser, type Page } from '@playwright/test'
import { startBackend, type Backend } from './backend'
import { openApp, goTo } from './fixtures'

/**
 * What the 16-* specs share: a licensed backend of their own, a second signed-in browser context,
 * writes through page.request with the CSRF header, and the Resources tab strip.
 *
 * The per-worker backend (fixtures.ts) is unlicensed by construction, and most administration
 * panels only DO something under a license. The license spec explains why the shared backend
 * cannot become licensed (11-license.spec.ts); these specs start one the same way, on the 8960+
 * range for Enterprise and 8970+ for Team so nothing collides with 11 (8900+) and 13 (8950+).
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const licenseFixtures = path.join(here, '..', '..', 'backend', 'src', 'test', 'resources', 'license')
export const TEST_KEYS_PATH = path.join(licenseFixtures, 'test-keys.json')

/** The settings catalog the backend serves — read here so a key added there and not rendered fails a test. */
export const SETTINGS_CATALOG_PATH = path.join(
  here, '..', '..', 'backend', 'src', 'main', 'java', 'com', 'concentus', 'config', 'SettingsCatalog.java',
)

export type Tier = 'enterprise' | 'team'

export function licenseToken(tier: Tier): string {
  return fs.readFileSync(path.join(licenseFixtures, `${tier}-test.license`), 'utf8').trim()
}

/** A licensed backend on its own port; the caller stops it. */
export function startLicensed(tier: Tier, port: number): Promise<Backend> {
  return startBackend(port, {
    CONCENTUS_LICENSE_TEST_KEYS: TEST_KEYS_PATH,
    CONCENTUS_LICENSE: licenseToken(tier),
  })
}

/** Ports for the licensed backends, per worker — see the file comment. */
export const ENTERPRISE_PORT = 8960
export const TEAM_PORT = 8970

/** Every account these specs add signs in with this. */
export const PASSWORD = 'another-e2e-password-long-enough'

export interface Session {
  page: Page
  baseURL: string
  browser: Browser
  errors: string[]
}

/** A page in its own context on `baseURL`, with the same uncaught-exception guard fixtures.ts gives `page`. */
export async function newPage(browser: Browser, baseURL: string, errors: string[]): Promise<Page> {
  const context = await browser.newContext({ baseURL })
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(String(error)))
  return page
}

/**
 * Runs `body` against a licensed backend of its own, as the e2e admin, in a context bound to it.
 * Boot, sign-in, teardown and the page-error assertion are the same in every spec, so they live here.
 */
export async function onLicensed(
  browser: Browser,
  tier: Tier,
  port: number,
  body: (s: Session) => Promise<void>,
): Promise<void> {
  const backend = await startLicensed(tier, port)
  const errors: string[] = []
  try {
    const page = await newPage(browser, backend.baseURL, errors)
    try {
      await openApp(page)
      await body({ page, baseURL: backend.baseURL, browser, errors })
    } finally {
      await page.context().close()
    }
    expect(errors, 'uncaught page errors during the test').toEqual([])
  } finally {
    await backend.stop()
  }
}

/** A fresh context signed in as somebody other than the e2e admin. The caller closes its context. */
export async function signInAs(s: Session, email: string, password = PASSWORD): Promise<Page> {
  const page = await newPage(s.browser, s.baseURL, s.errors)
  await page.goto('/')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Flows', exact: true })).toBeVisible({ timeout: 30_000 })
  return page
}

/**
 * A write as the page's own session. page.request shares the context's cookies, so the session
 * is there; what a bare request lacks is the CSRF header the backend wants echoed from the
 * XSRF-TOKEN cookie — the same dance fixtures.ts does for a cookie-less API client.
 */
export async function apiCall(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  apiPath: string,
  body?: unknown,
): Promise<APIResponse> {
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value
  return page.request.fetch(apiPath, {
    method,
    headers: {
      ...(token ? { 'X-XSRF-TOKEN': token } : {}),
      'Content-Type': 'application/json',
    },
    ...(body === undefined ? {} : { data: JSON.stringify(body) }),
  })
}

/** A JSON read as the page's session. */
export async function apiJson<T>(page: Page, apiPath: string): Promise<T> {
  const response = await page.request.get(apiPath)
  expect(response.ok(), `${apiPath} answered ${response.status()}`).toBe(true)
  return (await response.json()) as T
}

/** Resources, then one of its tabs. `exact`: "Settings" is also a heading, and "Members" a sub-tab. */
export async function openTab(page: Page, tab: string): Promise<void> {
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: tab, exact: true }).click()
}

/** Adds a member through the Members tab, with a role. The form's Role select, not a row's. */
export async function addMember(page: Page, email: string, role = 'Viewer'): Promise<void> {
  await page.getByRole('button', { name: 'Add member' }).click()
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Temporary password').fill(PASSWORD)
  await page.getByRole('combobox', { name: 'Role', exact: true }).selectOption({ label: role })
  const added = page.waitForResponse(
    (r) => r.url().includes('/api/account/members') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Add member' }).click()
  expect((await added).ok()).toBe(true)
  await expect(page.getByText(email)).toBeVisible()
}

/** After a reload the session is still there, and the workspace opens without the gate. */
export async function reloadWorkspace(page: Page): Promise<void> {
  await page.reload()
  await expect(page.getByRole('button', { name: 'Flows', exact: true })).toBeVisible({ timeout: 30_000 })
}

/** One setting's control on the Settings tab. Keys carry dots, so an attribute selector, not #id. */
export function settingControl(page: Page, key: string) {
  return page.locator(`[id="setting-${key}"]`)
}

/** The text column of one setting's row: its label, help, refusal and the source/restart line. */
export function settingText(page: Page, key: string) {
  // Every ancestor div matches; the last is the innermost, which is the row's text column.
  return page.locator('div', { has: page.locator(`label[for="setting-${key}"]`) }).last()
}

/** The "set here · needs a restart" line under one setting — the only span in the row's text column. */
export function settingMeta(page: Page, key: string) {
  return settingText(page, key).locator('span').last()
}

/** The Save button of the Settings roster, whatever count it currently announces. */
export function settingsSave(page: Page) {
  return page.getByRole('button', { name: /^Save( \d+ changes?)?$/ })
}

/** A group's override row for one key, opened from Groups → Open → Settings. */
export function groupSettingText(page: Page, key: string) {
  return page.locator('div', { has: page.locator(`label[for="group-setting-${key}"]`) }).last()
}

/**
 * Mints a service account through the panel and returns the token — the one moment it is on
 * screen. "Done" hides it again, which is what the panel promises.
 */
export async function mintServiceAccount(page: Page, name: string, role = 'Operator'): Promise<string> {
  await page.getByRole('button', { name: '+ New' }).click()
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByRole('combobox', { name: 'Role', exact: true }).selectOption({ label: role })
  const created = page.waitForResponse(
    (r) => r.url().includes('/api/service-accounts') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  expect((await created).ok()).toBe(true)
  const shown = page.getByRole('status')
  await expect(shown).toContainText(`Token for "${name}"`)
  await expect(shown).toContainText('Shown once.')
  const token = (await shown.locator('code').textContent())?.trim() ?? ''
  await shown.getByRole('button', { name: 'Done' }).click()
  await expect(shown).toHaveCount(0)
  return token
}

/** A flow whose worker reaches MCP through a fan-out — the shape an organization's facade policy applies to. */
export function fanoutFlowWithMcp(name: string, publishToken: string) {
  return {
    name,
    enabled: true,
    tags: ['e2e'],
    nodes: [
      {
        id: 'in-1', type: 'input', role: null,
        data: { mode: 'prompt', prompt: 'Say hello.', published: true, publishToken, _pos: { x: 40, y: 200 } },
      },
      {
        id: 'lead', type: 'agent', role: 'coordinator',
        data: { name: 'Lead', model: 'claude-sonnet-5', effort: 'low', maxTokens: 4000, execution: 'fanout',
          systemPrompt: 'Delegate to the worker.', permissionMode: 'plan', _pos: { x: 320, y: 200 } },
      },
      {
        id: 'worker', type: 'agent', role: 'subagent',
        data: { name: 'Worker', model: 'claude-sonnet-5', effort: 'low', maxTokens: 4000,
          description: 'Looks things up.', systemPrompt: 'Look things up.', _pos: { x: 620, y: 200 } },
      },
      {
        id: 'mcp-1', type: 'mcp', role: null,
        data: { name: 'e2e-tracker', url: 'https://mcp.example.test/mcp', credentialId: '', authHeader: '', _pos: { x: 620, y: 420 } },
      },
    ],
    edges: [
      { id: 'e-in', source: 'in-1', target: 'lead' },
      { id: 'e-w', source: 'lead', target: 'worker' },
      { id: 'e-mcp', source: 'mcp-1', target: 'worker' },
    ],
  }
}
