import { expect, test as base, type APIRequestContext, type Page } from '@playwright/test'
import { PORT_BASE, startBackend, type Backend } from './backend'

/**
 * The shared test, carrying two things.
 *
 * A backend PER WORKER: each worker starts its own jar with its own embedded PostgreSQL on its
 * own port (8800 + slot), which is what lets the suite run parallel without racing — a worker
 * counting cards can never see another worker's creations, because they are different databases.
 * The fixture stops its backend politely when the worker retires.
 *
 * And error tracking: every page records uncaught exceptions, and the test fails if any were
 * thrown — a blank panel after a TypeError otherwise passes any assertion that never looks at it.
 * Deliberately `pageerror` only, not console.error: the backend legitimately answers 4xx on some
 * probes (an unconfigured Ollama, say) and the UI logging that is not a bug.
 */
export const test = base.extend<{ page: Page }, { backend: Backend }>({
  backend: [
    async ({}, use, workerInfo) => {
      const backend = await startBackend(PORT_BASE + workerInfo.parallelIndex)
      await use(backend)
      await backend.stop()
    },
    // The timeout covers a cold JVM plus initdb on a busy CI runner.
    { scope: 'worker', timeout: 180_000 },
  ],
  baseURL: async ({ backend }, use) => use(backend.baseURL),
  page: async ({ page }, use) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(String(error)))
    await use(page)
    expect(errors, 'uncaught page errors during the test').toEqual([])
  },
})

export { expect }

/**
 * The account these tests work as.
 *
 * <p>Every worker has its own backend and its own empty database, so the first page to arrive
 * finds an installation nobody has claimed and claims it — which also means the setup screen is
 * exercised on every run rather than being the one path nothing covers. Later contexts in the same
 * worker have no cookies of their own and sign in with the same credentials.
 */
export const E2E_EMAIL = 'e2e@concentus.test'
export const E2E_PASSWORD = 'an-e2e-password-long-enough'

/**
 * The CSRF token this context has been given, which every write has to echo back in a header.
 *
 * <p>It arrives as a readable cookie on the first GET. A cookie alone would not prove intent — a
 * third-party page can cause a browser to send cookies but cannot read them — which is the whole
 * point of the header, and is why an API client has to do the same dance a browser does.
 */
async function csrfHeader(
  request: APIRequestContext,
  baseURL: string,
): Promise<Record<string, string>> {
  await request.get(`${baseURL}/api/account/session`)
  const state = await request.storageState()
  const token = state.cookies.find((c) => c.name === 'XSRF-TOKEN')?.value
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

/**
 * Reads an endpoint as the test account, from a context that has no cookies of its own.
 *
 * <p>Playwright's `request` fixture is a bare API client — no session, and since sign-in became
 * mandatory that means a 401 with an empty body, which parses as nothing rather than reading as
 * "unauthorized". So it claims the installation if nobody has yet, signs in either way, and only
 * then asks — which is the same sequence the app itself goes through.
 */
export async function signedInRequest(
  request: APIRequestContext,
  baseURL: string,
  path: string,
): Promise<Record<string, unknown>> {
  const headers = await csrfHeader(request, baseURL)
  const session = await (await request.get(`${baseURL}/api/account/session`)).json()
  if (session.setupRequired) {
    // This spec drives no page, so on a worker whose backend nothing has touched yet, it is the
    // one claiming the installation.
    const created = await request.post(`${baseURL}/api/account/setup`, {
      headers,
      data: { email: E2E_EMAIL, password: E2E_PASSWORD },
    })
    if (!created.ok()) {
      throw new Error(`Could not create the test account: ${created.status()} ${await created.text()}`)
    }
  } else {
    const login = await request.post(`${baseURL}/api/account/login`, {
      headers,
      data: { email: E2E_EMAIL, password: E2E_PASSWORD },
    })
    if (!login.ok()) {
      throw new Error(`Could not sign in for a direct API read: ${login.status()} ${await login.text()}`)
    }
  }
  return (await request.get(`${baseURL}${path}`)).json() as Promise<Record<string, unknown>>
}

/**
 * Gets this browser context past the gate: setup on a fresh installation, sign-in on a claimed one.
 *
 * <p>Two states rather than one because a worker's backend outlives its pages. Which one is on
 * screen is asked of the page instead of tracked here — tracking it would be a second source of
 * truth about something the DOM already answers, and it would be wrong the first time a test
 * retried.
 */
async function passTheGate(page: Page): Promise<void> {
  await page.goto('/')
  const setup = page.getByRole('button', { name: 'Create account and continue' })
  const signIn = page.getByRole('button', { name: 'Sign in', exact: true })
  await expect(setup.or(signIn).first()).toBeVisible({ timeout: 30_000 })

  if (await setup.isVisible()) {
    await page.getByLabel('Email').fill(E2E_EMAIL)
    await page.getByLabel('Password', { exact: true }).fill(E2E_PASSWORD)
    await page.getByLabel('Password again').fill(E2E_PASSWORD)
    await setup.click()
  } else {
    await page.getByLabel('Email').fill(E2E_EMAIL)
    await page.getByLabel('Password').fill(E2E_PASSWORD)
    await signIn.click()
  }
  // The gate is gone when the header is there, which is the first thing the workspace renders.
  await expect(page.getByRole('button', { name: 'Flows', exact: true })).toBeVisible({
    timeout: 30_000,
  })
}

/**
 * Loads the app and waits until the Flows dashboard shows its TRUE state.
 *
 * The subtlety this hides: while /api/flows is in flight the page renders the same "No flows yet"
 * card a genuinely empty install shows — loading and empty are indistinguishable on screen. So
 * the answer itself is captured (the listener is armed before goto, or a fast response slips
 * past), and what the wait asserts depends on what the backend actually said: cards when flows
 * exist, the empty card when none do. Only then are counts and clicks deterministic.
 */
export async function openApp(page: Page): Promise<void> {
  await passTheGate(page)
  // Asked directly rather than by intercepting the page's own request. Since sign-in became
  // mandatory the dashboard is reached through a gate, and a listener armed around that dance
  // latched onto a response whose body the navigation had already discarded — "No resource with
  // given identifier found", intermittently, in whichever spec ran first. page.request shares the
  // context's cookies, so this is the same answer the app itself got.
  const flows = (await page.request.get('/api/flows').then((r) => r.json())) as { folder?: string }[]
  if (flows.length === 0) {
    await expect(page.getByText('No flows yet')).toBeVisible()
  } else {
    // Cards either way. When every flow lives under one folder — a fresh install, where that
    // folder is Samples — the dashboard now opens inside it rather than showing the cabinet the
    // samples are filed in, so the settled state is the flows themselves in both cases.
    // .first() on the CARDS alone: an install can legitimately have both cards and folder tiles,
    // and asserting on a locator that matches both trips strict mode the moment it does.
    await expect(page.getByRole('article').first()).toBeVisible()
  }
}

/** The header navigation. `exact` matters: the Studio toolbar has a "← Flows" button too. */
export async function goTo(page: Page, view: 'Flows' | 'Studio' | 'Marketplace' | 'Resources' | 'Usage'): Promise<void> {
  await page.getByRole('button', { name: view, exact: true }).click()
}

/** A flow card on the Flows page, found by its exact name. */
export function flowCard(page: Page, name: string) {
  return page.getByRole('article').filter({ has: page.getByRole('heading', { name, exact: true }) })
}

/**
 * Runs one of a flow card's menu actions — Delete, Duplicate, Settings, and the rest.
 *
 * <p>They used to be a row of icon buttons on the card itself, reachable by title. Nine controls
 * did not fit a card at four columns, so all but Open and Run moved behind a ⋯ menu where each one
 * has a name. Tests say the name now, which is also what a person reads.
 */
export async function cardAction(
  page: Page,
  flowName: string,
  // 'Duplicate' is also a prefix of 'Duplicate as sandbox', so a caller sometimes has to say
  // exact. Taking the options object rather than only a name keeps that possible without a
  // second helper.
  action: string | RegExp | { name: string; exact: boolean },
): Promise<void> {
  await flowCard(page, flowName).getByRole('button', { name: /More actions/ }).click()
  // The menu is rendered into the body, not the card — a card clips its own overflow — so it is
  // found on the page rather than inside the card.
  const item = typeof action === 'object' && 'name' in action
    ? page.getByRole('menuitem', action)
    : page.getByRole('menuitem', { name: action })
  await item.click()
}

