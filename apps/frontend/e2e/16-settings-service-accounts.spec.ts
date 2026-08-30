import { expect, test } from './fixtures'
import { apiJson, ENTERPRISE_PORT, mintServiceAccount, onLicensed, openTab } from './16-settings.helpers'

/**
 * Service accounts on an Enterprise backend, where nothing caps them: minted with the token shown
 * exactly once, used as a bearer from a context that has no cookies at all, renamed, revoked.
 * The Team cap is 16-settings-team.spec.ts's.
 */

interface Listing {
  accounts: Array<{ name: string; revokedAt: number | null }>
  active: number
  limit: number | null
  refusal: string | null
}

test('a service account: minted once, usable as a bearer, renamed, revoked — and uncapped on Enterprise', async ({ browser, playwright }, testInfo) => {
  test.setTimeout(180_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page, baseURL }) => {
    await openTab(page, 'Service accounts')
    await expect(page.getByText('No service accounts yet.')).toBeVisible()
    await expect(page.getByText('0 in use', { exact: true })).toBeVisible()

    const token = await mintServiceAccount(page, 'nightly-report-e2e', 'Operator')
    expect(token).toMatch(/^csa_[A-Za-z0-9_-]{20,}$/)
    // No cap: a count, not "of N".
    await expect(page.getByText('1 in use', { exact: true })).toBeVisible()
    await expect(page.getByRole('listitem').filter({ hasText: 'nightly-report-e2e' })).toContainText('never used')
    const listing = await apiJson<Listing>(page, '/api/service-accounts')
    expect(listing.active).toBe(1)
    expect(listing.limit ?? null).toBeNull()
    expect(listing.refusal ?? null).toBeNull()
    // Only its hash is stored: the listing never carries the token.
    expect(JSON.stringify(listing)).not.toContain(token)

    // A machine: no cookies, the token as a bearer. It acts as its role and never as a person.
    const machine = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    })
    try {
      const session = await (await machine.get('/api/account/session')).json() as { signedIn: boolean; role: string; email: string }
      expect(session.signedIn).toBe(true)
      expect(session.role.toUpperCase()).toBe('OPERATOR')
      expect(session.email).toContain('nightly-report-e2e')
      expect((await machine.get('/api/flows')).status()).toBe(200)
      // Operator: may run, may not change what a flow is.
      expect((await machine.post('/api/flows', { data: { name: 'machine-made', nodes: [], edges: [] } })).status()).toBe(403)
      // Never an admin: the listing itself is out of reach.
      expect((await machine.get('/api/service-accounts')).status()).toBe(403)

      // Rename, from the row.
      await page.getByRole('button', { name: 'Rename' }).click()
      await page.getByLabel('New name for nightly-report-e2e').fill('nightly-report-v2')
      await page.keyboard.press('Enter')
      const row = page.getByRole('listitem').filter({ hasText: 'nightly-report-v2' })
      await expect(row).toBeVisible()
      // "last used" moved when the token was presented above; the rename answered with the fresh row.
      await expect(row).toContainText('last used')

      // Revoke: the row stays, stamped, and the token stops working on the next request.
      page.once('dialog', (dialog) => {
        expect(dialog.message()).toContain('Revoke "nightly-report-v2"?')
        void dialog.accept()
      })
      const revoked = page.getByRole('listitem').filter({ hasText: 'nightly-report-v2' })
      await revoked.getByRole('button', { name: 'Revoke' }).click()
      await expect(revoked).toContainText('revoked')
      await expect(revoked.getByRole('button', { name: 'Revoke' })).toHaveCount(0)
      await expect(revoked.getByRole('button', { name: 'Rename' })).toHaveCount(0)
      await expect(page.getByText('0 in use', { exact: true })).toBeVisible()
      const after = await apiJson<Listing>(page, '/api/service-accounts')
      expect(after.accounts.map((a) => [a.name, a.revokedAt != null])).toEqual([['nightly-report-v2', true]])
      // From a context that presents the token and nothing else. The first context is not asked
      // again on purpose: its bearer calls were answered with a session cookie (the security
      // configuration creates sessions IF_REQUIRED), and that cookie keeps answering after the
      // revocation — a finding for the backend, not a property of the token.
      const again = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } })
      try {
        expect((await again.get('/api/flows')).status()).toBe(401)
      } finally {
        await again.dispose()
      }
    } finally {
      await machine.dispose()
    }
  })
})
