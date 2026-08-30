import * as fs from 'node:fs'
import { E2E_EMAIL, expect, openApp, test } from './fixtures'
import {
  apiCall,
  ENTERPRISE_PORT,
  onLicensed,
  openTab,
  settingControl,
  settingsSave,
} from './16-settings.helpers'

/**
 * The audit trail and the retention line beside it.
 *
 * Unlicensed: the rows are there and the filters narrow them, export is the Enterprise sentence,
 * and nothing is ever purged. Enterprise: the days chosen under Settings → Retention are what the
 * line reports, "Apply retention now" answers with what it removed (nothing, on a fresh install),
 * and the export is a real CSV.
 */

const utcDay = (offsetDays: number) => new Date(Date.now() + offsetDays * 86_400_000).toISOString().slice(0, 10)

test('the trail lists what the admin did, the kind and date filters narrow it, and export is refused without a license', async ({ page }) => {
  await openApp(page)
  await openTab(page, 'Settings')
  await settingControl(page, 'knowledge.ocr-max-pages').fill('12')
  await settingsSave(page).click()
  await expect(page.getByText('Saved.')).toBeVisible()

  await page.getByRole('button', { name: 'Audit', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Audit trail' })).toBeVisible()
  const rows = page.locator('table tbody tr')
  const change = rows.filter({ hasText: 'setting.changed' }).first()
  await expect(change).toContainText(E2E_EMAIL)
  await expect(change).toContainText('ADMIN')
  await expect(change).toContainText('OCR pages per PDF')

  // The kind filter: only that kind remains.
  await page.getByRole('combobox', { name: 'Kind', exact: true }).selectOption('setting.changed')
  await page.getByRole('button', { name: 'Apply', exact: true }).click()
  await expect(rows.first()).toBeVisible()
  await expect(rows.filter({ hasNotText: 'setting.changed' })).toHaveCount(0)

  // The date filter: nothing from tomorrow on; everything from today.
  await page.getByLabel('From', { exact: true }).fill(utcDay(1))
  await page.getByRole('button', { name: 'Apply', exact: true }).click()
  await expect(page.getByText('Nothing recorded yet — or nothing matches these filters.')).toBeVisible()
  await page.getByLabel('From', { exact: true }).fill(utcDay(0))
  await page.getByLabel('To', { exact: true }).fill(utcDay(0))
  await page.getByRole('button', { name: 'Apply', exact: true }).click()
  await expect(rows.first()).toBeVisible()

  // Export: the buttons are held back, the sentence is printed, and the API says the same.
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Export JSON' })).toBeDisabled()
  await expect(page.getByText('Audit trail export is an Enterprise feature. Install an enterprise license to use it.')).toBeVisible()
  const refused = await page.request.get('/api/audit/export?format=csv')
  expect(refused.status()).toBe(403)

  // Retention on a free install: nothing is purged, so there is nothing to apply.
  await expect(page.getByText(/No paid license: this is a single-person installation and nothing on it is purged/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Apply retention now' })).toHaveCount(0)
})

test('on Enterprise the chosen retention is reported, applying it purges nothing on a fresh install, and the export is a CSV', async ({ browser }, testInfo) => {
  test.setTimeout(150_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page }) => {
    await openTab(page, 'Audit')
    await expect(page.getByText(/Enterprise license: runs, flow versions and the audit trail are kept without limit/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Apply retention now' })).toHaveCount(0)

    await page.getByRole('button', { name: 'Settings', exact: true }).click()
    await settingControl(page, 'retention.enterprise-days').fill('30')
    await settingsSave(page).click()
    await expect(page.getByText(/In effect now\./)).toBeVisible()

    await page.getByRole('button', { name: 'Audit', exact: true }).click()
    await expect(page.getByText(/Enterprise license: an administrator chose to keep 30 days of runs, flow versions and audit trail/)).toBeVisible()
    await page.getByRole('button', { name: 'Apply retention now' }).click()
    await expect(page.getByText('Nothing to purge.')).toBeVisible()
    const report = await apiCall(page, 'POST', '/api/retention/run-now')
    expect(await report.json()).toEqual({ days: 30, runs: 0, versions: 0, auditEvents: 0 })

    // The file: the header the controller writes, and the rows the trail holds.
    const downloading = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Export CSV' }).click()
    const download = await downloading
    expect(download.suggestedFilename()).toMatch(/^concentus-audit-\d{4}-\d{2}-\d{2}\.csv$/)
    const csv = fs.readFileSync(await download.path(), 'utf8')
    expect(csv.split('\n')[0]).toBe('id,at,actor,role,kind,subject_type,subject_id,subject_label,detail')
    expect(csv).toContain('setting.changed')
    expect(csv).toContain(E2E_EMAIL)
    await expect(page.getByText('Exported.')).toBeVisible()
  })
})
