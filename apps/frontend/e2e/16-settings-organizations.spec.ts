import { expect, flowCard, openApp, test } from './fixtures'
import { apiJson, ENTERPRISE_PORT, onLicensed, openTab } from './16-settings.helpers'

/**
 * Organizations: one on a free install, refused a second in the panel's own alert; several on
 * Enterprise, each a workspace of its own, switched from the account menu.
 */

interface Organization {
  id: string
  name: string
  role: string | null
  current: boolean
}

const ORG_ONE_FLOW = 'E2E org-one flow'
const SECOND = 'E2E second org'
const RENAMED = 'E2E second org (renamed)'

test('without a license a second organization is refused with the feature sentence, and the menu offers no switch', async ({ page }) => {
  await openApp(page)
  await openTab(page, 'Organizations')
  await expect(page.getByText('current')).toBeVisible()
  await page.getByRole('button', { name: 'New organization' }).click()
  await page.getByLabel('Name', { exact: true }).fill(SECOND)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText(
    'Several organizations on one deployment is an Enterprise feature. Install an enterprise license to use it.',
  )
  expect(await apiJson<Organization[]>(page, '/api/organizations')).toHaveLength(1)

  await page.getByRole('button', { name: /^Account: / }).click()
  await expect(page.getByRole('menu')).toBeVisible()
  await expect(page.getByText('Switch organization')).toHaveCount(0)
  await page.keyboard.press('Escape')
})

test('a second organization is its own workspace, reached from the account menu and left the same way', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page }) => {
    // A flow that belongs to the first organization.
    await page.getByRole('button', { name: '+ New flow' }).first().click()
    await page.getByLabel('Flow name').fill(ORG_ONE_FLOW)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await page.getByRole('button', { name: '← Flows' }).click()
    await expect(flowCard(page, ORG_ONE_FLOW)).toHaveCount(1)
    const first = (await apiJson<Organization[]>(page, '/api/organizations')).find((o) => o.current)!

    await openTab(page, 'Organizations')
    await page.getByRole('button', { name: 'New organization' }).click()
    await page.getByLabel('Name', { exact: true }).fill(SECOND)
    const created = page.waitForResponse((r) => r.url().endsWith('/api/organizations') && r.request().method() === 'POST')
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    expect((await created).ok()).toBe(true)
    const row = page.getByRole('listitem').filter({ hasText: SECOND })
    await expect(row).toContainText('Admin')
    await expect(row).not.toContainText('current')

    // Renamed in place.
    await row.getByRole('button', { name: 'Rename' }).click()
    await page.getByLabel(`New name for ${SECOND}`).fill(RENAMED)
    await page.keyboard.press('Enter')
    await expect(page.getByRole('listitem').filter({ hasText: RENAMED })).toHaveCount(1)

    // Switch: the page reloads under the other organization, where the flow is not.
    const switchTo = async (name: string) => {
      await page.getByRole('button', { name: /^Account: / }).click()
      await expect(page.getByText('Switch organization')).toBeVisible()
      const reloaded = page.waitForEvent('load')
      await page.getByRole('menuitem', { name }).click()
      await reloaded
      await expect(page.getByRole('button', { name: 'Flows', exact: true })).toBeVisible({ timeout: 30_000 })
      await expect(page.getByText('No flows yet').or(page.getByRole('article').first())).toBeVisible()
    }
    await switchTo(RENAMED)
    expect((await apiJson<{ organizationName: string }>(page, '/api/account/session')).organizationName).toBe(RENAMED)
    await expect(page.getByRole('heading', { name: ORG_ONE_FLOW, exact: true })).toHaveCount(0)
    expect((await apiJson<{ name: string }[]>(page, '/api/flows')).map((f) => f.name)).not.toContain(ORG_ONE_FLOW)
    await openTab(page, 'Organizations')
    await expect(page.getByRole('listitem').filter({ hasText: RENAMED })).toContainText('current')

    // And back.
    await switchTo(first.name)
    expect((await apiJson<{ organizationName: string }>(page, '/api/account/session')).organizationName).toBe(first.name)
    await expect(flowCard(page, ORG_ONE_FLOW)).toHaveCount(1)
  })
})
