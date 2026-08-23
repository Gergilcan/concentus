import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, goTo, openApp, test } from './fixtures'
import { startBackend } from './backend'

/**
 * The license, on both sides of it.
 *
 * The first three tests run against the ordinary per-worker backend (see fixtures.ts) — it is
 * unlicensed by construction, nothing here installs one on it, so it stays that way for whatever
 * other spec runs on the same worker next.
 *
 * The fourth needs a backend that IS licensed, and the shared one cannot become that: the app
 * embeds the real public keys, and a license that verifies against them would have to be signed
 * with the real enterprise private key — committing one of those (or a license it made) to a
 * public repository would hand everyone a valid enterprise license. So it gets its own backend,
 * started here with CONCENTUS_LICENSE_TEST_KEYS pointed at the committed test-keys fixture, which
 * swaps the trust root wholesale for a fixture-signed token to verify against
 * (LicenseVerifier.forProduction — see apps/backend/.../license/LicenseVerifier.java). Own port,
 * own data directory, same worker-isolation conventions as every other backend this suite starts.
 */

// import.meta, not __dirname: this package is type=module, so Playwright loads spec files as ESM
// (same reason backend.ts resolves the jar path this way).
const here = path.dirname(fileURLToPath(import.meta.url))
const licenseFixtures = path.join(here, '..', '..', 'backend', 'src', 'test', 'resources', 'license')
const TEST_KEYS_PATH = path.join(licenseFixtures, 'test-keys.json')
const ENTERPRISE_LICENSE_TOKEN = fs
  .readFileSync(path.join(licenseFixtures, 'enterprise-test.license'), 'utf8')
  .trim()

test('Settings explains there is no license and links to where to get one', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'Settings' }).click()

  await expect(page.getByText(/request a free one/)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Request a license' })).toHaveAttribute(
    'href',
    'https://www.concentus-ai.com/#license',
  )
})

test('an unverifiable token is refused with the server message, verbatim', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'Settings' }).click()

  await page.getByLabel('License token').fill('garbage-token')
  const install = page.waitForResponse(
    (r) => r.url().includes('/api/license') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Apply' }).click()
  expect((await install).status()).toBe(400)

  // LicenseVerifier's InvalidLicenseException message, shown as-is — written to be read by
  // whoever pasted the token in, so the UI does not summarize or reword it.
  await expect(page.getByText(/not a Concentus license/)).toBeVisible()
})

test('the free seat limit refuses a second member, naming the limit and the fix', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  // Reached through the real UI, not a raw request.post: Members is unconditionally in the tab
  // strip and the e2e setup account is this organization's only admin, so nothing in this profile
  // keeps the panel from rendering.
  await page.getByRole('button', { name: 'Members' }).click()
  await page.getByRole('button', { name: 'Add member' }).click()
  await page.getByLabel('Email').fill('second-member@e2e.test')
  await page.getByLabel('Temporary password').fill('another-e2e-password-long-enough')

  const attempt = page.waitForResponse(
    (r) => r.url().includes('/api/account/members') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Add member' }).click()
  expect((await attempt).status()).toBe(400)

  // AccountController.seatLimitReachedMessage: names the limit this install is actually under and
  // the fix, rather than a generic "forbidden".
  const alert = page.getByRole('alert')
  await expect(alert).toContainText('limited to 1 member')
  await expect(alert).toContainText('enterprise license')
})

test('with an enterprise license: Settings shows it, and a second member can be added', async (
  { browser },
  testInfo,
) => {
  // Generous on purpose: this test pays for an extra JVM boot + initdb on top of the page work
  // below, the same cost the worker-scoped backend fixture budgets 180s for in fixtures.ts.
  test.setTimeout(150_000)

  // 8900, not 8800 + parallelIndex — that range is the per-worker backend every other test in this
  // suite (including the three above) is already running on.
  const licensed = await startBackend(8900 + testInfo.parallelIndex, {
    CONCENTUS_LICENSE_TEST_KEYS: TEST_KEYS_PATH,
    CONCENTUS_LICENSE: ENTERPRISE_LICENSE_TOKEN,
  })
  try {
    // Its own browser context, bound to this backend's own baseURL — the shared `page` fixture is
    // wired to the per-worker backend and cannot be pointed anywhere else mid-test.
    const context = await browser.newContext({ baseURL: licensed.baseURL })
    const errors: string[] = []
    try {
      const page = await context.newPage()
      // Same uncaught-exception guard fixtures.ts's `page` fixture gives every other test; this
      // page is not that fixture, so it is reproduced here.
      page.on('pageerror', (error) => errors.push(String(error)))

      await openApp(page)
      await goTo(page, 'Resources')
      await page.getByRole('button', { name: 'Settings' }).click()
      await expect(page.getByText(/Licensed to Test Corp/)).toBeVisible()
      await expect(page.getByText(/5 seats/)).toBeVisible()

      await page.getByRole('button', { name: 'Members' }).click()
      await page.getByRole('button', { name: 'Add member' }).click()
      await page.getByLabel('Email').fill('second-member@e2e.test')
      await page.getByLabel('Temporary password').fill('another-e2e-password-long-enough')

      const added = page.waitForResponse(
        (r) => r.url().includes('/api/account/members') && r.request().method() === 'POST',
      )
      await page.getByRole('button', { name: 'Add member' }).click()
      expect((await added).ok()).toBe(true)
      await expect(page.getByText('second-member@e2e.test')).toBeVisible()
    } finally {
      await context.close()
    }
    expect(errors, 'uncaught page errors during the test').toEqual([])
  } finally {
    await licensed.stop()
  }
})
