import { expect, test } from './fixtures'
import {
  apiCall,
  apiJson,
  mintServiceAccount,
  onLicensed,
  openTab,
  settingControl,
  settingText,
  TEAM_PORT,
} from './16-settings.helpers'

/**
 * Where the Enterprise gates actually bite: a Team license.
 *
 * A free installation is never held back — LicenseService.withheld() is true only for a paid,
 * not-Enterprise deployment — so the disabled rows, the inactive custom issuer, the service
 * account cap and the ninety-day retention exist on a Team backend and nowhere else. One backend,
 * one pass over every panel that changes shape under it.
 */

const OTEL = ['management.otlp.tracing.export.enabled', 'management.otlp.metrics.export.enabled']

test('a Team license: the Enterprise-only rows say so, the caps bite, and retention is ninety days', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await onLicensed(browser, 'team', TEAM_PORT + testInfo.parallelIndex, async ({ page }) => {
    // Settings: the license line, ten locks, and the two export flags disabled with the sentence.
    await openTab(page, 'Settings')
    await expect(page.getByText(/Licensed to Test Team · team · 3 seats/)).toBeVisible()
    const features = page.getByRole('list').filter({ hasText: 'Organization policies' })
    await expect(features.getByRole('listitem')).toHaveCount(10)
    await expect(features.getByRole('listitem').filter({ hasText: '🔒' })).toHaveCount(10)
    for (const key of OTEL) {
      await expect(settingControl(page, key), key).toBeDisabled()
      await expect(settingText(page, key), key).toContainText(
        'OpenTelemetry export to your collector is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together',
      )
    }
    await expect(settingControl(page, 'management.otlp.tracing.endpoint')).toBeEnabled()
    const { settings } = await apiJson<{ settings: { key: string; refusal: string | null }[] }>(page, '/api/settings')
    expect(settings.filter((s) => s.refusal != null).map((s) => s.key).sort()).toEqual([...OTEL].sort())

    // Sign-in providers: the custom issuer is listed, inactive, and says which tier has it.
    await openTab(page, 'Members')
    const custom = page.locator('section').filter({ has: page.getByRole('heading', { name: 'your organization' }) })
    await expect(custom.getByText('Enterprise — inactive')).toBeVisible()
    await expect(custom).toContainText('Custom identity providers (any OpenID Connect issuer) is an Enterprise feature')
    await expect(custom.getByLabel('Client id')).toBeDisabled()
    await expect(custom.getByRole('button', { name: 'Save and offer it' })).toBeDisabled()
    const microsoft = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Microsoft' }) })
    await expect(microsoft.getByLabel('Client id')).toBeEnabled()
    await expect(page.getByText(/Automatic accounts for an email domain is an Enterprise feature/)).toBeVisible()

    // Service accounts: two, then the cap — on the button, in its tooltip, and from the API.
    await openTab(page, 'Service accounts')
    await expect(page.getByText('0 of 2 in use')).toBeVisible()
    await mintServiceAccount(page, 'ci-one')
    await mintServiceAccount(page, 'ci-two')
    await expect(page.getByText('2 of 2 in use')).toBeVisible()
    const mint = page.getByRole('button', { name: '+ New' })
    await expect(mint).toBeDisabled()
    await expect(mint).toHaveAttribute(
      'title',
      /Unlimited service accounts is an Enterprise feature.*This deployment has 2 of 2 service accounts in use; revoke one to mint another\./,
    )
    const refused = await apiCall(page, 'POST', '/api/service-accounts', { name: 'ci-three', role: 'OPERATOR' })
    expect(refused.status()).toBe(403)
    expect(((await refused.json()) as { error: string }).error).toContain('revoke one to mint another')
    page.once('dialog', (dialog) => void dialog.accept())
    await page.getByRole('button', { name: 'Revoke' }).first().click()
    await expect(page.getByText('1 of 2 in use')).toBeVisible()
    await expect(mint).toBeEnabled()

    // Audit: ninety days, the purge answers, and export is the Team sentence.
    await openTab(page, 'Audit')
    await expect(page.getByText(/Team license: runs, flow versions and the audit trail are kept for 90 days/)).toBeVisible()
    await page.getByRole('button', { name: 'Apply retention now' }).click()
    await expect(page.getByText('Nothing to purge.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Export CSV' })).toBeDisabled()
    await expect(page.getByText(/Audit trail export is an Enterprise feature — the Team license/)).toBeVisible()

    // Policies read-only, groups not creatable — each under its own sentence.
    await openTab(page, 'Policies')
    await expect(page.getByRole('note')).toContainText('Organization policies is an Enterprise feature')
    await expect(page.getByLabel('Permission ceiling')).toBeDisabled()
    await openTab(page, 'Groups')
    await expect(page.getByRole('button', { name: '+ New' })).toBeDisabled()
    await expect(page.getByRole('note')).toContainText('Groups inside an organization is an Enterprise feature')
  })
})
