import { expect, openApp, test } from './fixtures'
import { apiJson, ENTERPRISE_PORT, onLicensed, openTab } from './16-settings.helpers'

/**
 * The License panel's feature list — what 11-license.spec.ts does not look at — and the Storage
 * panel's form, without ever migrating anything.
 */

interface LicenseStatus {
  features: Array<{ key: string; label: string; allowed: boolean }>
}

/** The ten Enterprise features, as one list item each, on the Settings tab. */
function featureItems(page: Parameters<typeof openTab>[0]) {
  return page.getByRole('list').filter({ hasText: 'Organization policies' }).getByRole('listitem')
}

test('without a license the feature list is ten locks, each named as the backend names it', async ({ page }) => {
  await openApp(page)
  await openTab(page, 'Settings')
  await expect(page.getByRole('heading', { name: 'What this license unlocks' })).toBeVisible()
  const items = featureItems(page)
  await expect(items).toHaveCount(10)
  await expect(items.filter({ hasText: '🔒' })).toHaveCount(10)
  await expect(items.filter({ hasText: '✓' })).toHaveCount(0)
  await expect(page.getByText('The locked ones are Enterprise features.')).toBeVisible()

  const status = await apiJson<LicenseStatus>(page, '/api/license')
  expect(status.features).toHaveLength(10)
  expect(status.features.every((f) => !f.allowed)).toBe(true)
  await expect(items).toContainText(status.features.map((f) => f.label))
})

test('with an enterprise license every row is a tick', async ({ browser }, testInfo) => {
  test.setTimeout(150_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page }) => {
    await openTab(page, 'Settings')
    await expect(page.getByText(/Licensed to Test Corp · enterprise · 5 seats/)).toBeVisible()
    const items = featureItems(page)
    await expect(items).toHaveCount(10)
    await expect(items.filter({ hasText: '✓' })).toHaveCount(10)
    await expect(items.filter({ hasText: '🔒' })).toHaveCount(0)
    await expect(page.getByText('The locked ones are Enterprise features.')).toHaveCount(0)
  })
})

test('storage: the embedded database is what runs, and an external URL that is not PostgreSQL is refused with the server sentence', async ({ page }) => {
  await openApp(page)
  await openTab(page, 'Storage')
  const mode = page.getByLabel('Where Concentus stores its data')
  await expect(mode).toHaveValue('embedded')
  await expect(page.getByText('Ships with the app; nothing to install.')).toBeVisible()
  await expect(page.getByText('Restart required')).toHaveCount(0)
  expect(await apiJson<{ mode: string; activeMode: string }>(page, '/api/storage')).toMatchObject({
    mode: 'embedded',
    activeMode: 'embedded',
  })

  // The external form: the restart warning appears the moment the choice differs from what runs.
  await mode.selectOption('external')
  await expect(page.getByLabel('JDBC URL')).toBeVisible()
  await expect(page.getByText('Restart required')).toBeVisible()
  await expect(page.getByText(/currently running on the embedded database/)).toBeVisible()

  // Refused before any connection is tried, in the backend's words — a blank, then the wrong engine.
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByText('✗ A JDBC URL is required for an external database.')).toBeVisible()
  await page.getByLabel('JDBC URL').fill('jdbc:mysql://db.internal:3306/concentus')
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByText(/✗ Only PostgreSQL is supported \(the URL must start with jdbc:postgresql:\)/)).toBeVisible()

  // Save is refused the same way, and nothing was written.
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Only PostgreSQL is supported')
  expect((await apiJson<{ mode: string }>(page, '/api/storage')).mode).toBe('embedded')

  // The move is armed by a filled-in form and disarmed by leaving it — and never pressed here.
  await expect(page.getByRole('button', { name: 'Move it now' })).toBeEnabled()
  await mode.selectOption('embedded')
  await expect(page.getByRole('button', { name: 'Move it now' })).toBeDisabled()
  await expect(page.getByText('Restart required')).toHaveCount(0)
})
