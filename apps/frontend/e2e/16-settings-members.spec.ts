import { E2E_EMAIL, expect, goTo, test } from './fixtures'
import { addMember, apiCall, ENTERPRISE_PORT, onLicensed, openTab, signInAs } from './16-settings.helpers'

/**
 * Members and roles on a backend licensed for them (five seats: the admin plus the four below).
 *
 * The roster is exercised as an admin, then the interface is looked at from the other side — as
 * the viewer — because "the interface withholds edit actions" is only true from that side. What
 * the screen hides is asserted alongside what the API refuses: the second is the rule, the first
 * is the courtesy.
 */

const VIEWER = 'viewer-e2e@e2e.test'
const OPERATOR = 'operator-e2e@e2e.test'
const MEMBER = 'member-e2e@e2e.test'
const ADMIN2 = 'admin2-e2e@e2e.test'

const EDIT_DENIED = 'Your role (viewer) cannot change flows. An admin can change that under Resources → Members.'

test('every role is added, the only admin cannot demote themselves, roles move both ways, and a viewer is shown nothing to edit', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async (s) => {
    const { page } = s
    await openTab(page, 'Members')
    await addMember(page, VIEWER, 'Viewer')
    await addMember(page, OPERATOR, 'Operator')
    await addMember(page, MEMBER, 'Member')
    await expect(page.getByText('4 accounts.')).toBeVisible()
    for (const [email, role] of [[VIEWER, 'VIEWER'], [OPERATOR, 'OPERATOR'], [MEMBER, 'MEMBER'], [E2E_EMAIL, 'ADMIN']]) {
      await expect(page.getByLabel(`Role for ${email}`)).toHaveValue(role)
    }

    // The guard, while this account is still the only admin.
    const refused = page.waitForResponse((r) => r.url().includes('/role') && r.request().method() === 'POST')
    await page.getByLabel(`Role for ${E2E_EMAIL}`).selectOption('VIEWER')
    expect((await refused).status()).toBe(409)
    await expect(page.getByRole('alert')).toContainText("This is the organization's only admin. Promote someone else first.")
    await expect(page.getByLabel(`Role for ${E2E_EMAIL}`)).toHaveValue('ADMIN')

    // A second admin, demoted; the viewer promoted and back.
    await addMember(page, ADMIN2, 'Admin')
    await expect(page.getByText('5 accounts.')).toBeVisible()
    await page.getByLabel(`Role for ${ADMIN2}`).selectOption('MEMBER')
    await expect(page.getByLabel(`Role for ${ADMIN2}`)).toHaveValue('MEMBER')
    await page.getByLabel(`Role for ${VIEWER}`).selectOption('OPERATOR')
    await expect(page.getByLabel(`Role for ${VIEWER}`)).toHaveValue('OPERATOR')
    await page.getByLabel(`Role for ${VIEWER}`).selectOption('VIEWER')
    await expect(page.getByLabel(`Role for ${VIEWER}`)).toHaveValue('VIEWER')
    const members = await (await page.request.get('/api/account/members')).json() as { email: string; role: string }[]
    expect(Object.fromEntries(members.map((m) => [m.email, m.role]))).toEqual({
      [E2E_EMAIL]: 'ADMIN', [VIEWER]: 'VIEWER', [OPERATOR]: 'OPERATOR', [MEMBER]: 'MEMBER', [ADMIN2]: 'MEMBER',
    })

    // The viewer's side of the screen.
    const viewer = await signInAs(s, VIEWER)
    try {
      await expect(viewer.getByRole('button', { name: `Account: ${VIEWER} (Viewer)` })).toBeVisible()
      const card = viewer.getByRole('article').first()
      await expect(card.getByRole('button', { name: '▶ Run' })).toBeDisabled()
      await card.getByRole('button', { name: /More actions/ }).click()
      for (const item of ['Delete', 'Duplicate', 'Settings']) {
        await expect(viewer.getByRole('menuitem', { name: item, exact: true }), item).toBeDisabled()
      }
      await viewer.keyboard.press('Escape')

      // The Studio opens for reading: Save and Run are there, disabled, and say why.
      await card.getByRole('button', { name: 'Open' }).click()
      const save = viewer.getByRole('button', { name: 'Save', exact: true })
      await expect(save).toBeDisabled()
      await expect(save).toHaveAttribute('title', EDIT_DENIED)
      await expect(viewer.getByRole('button', { name: '▶ Run' })).toBeDisabled()
      await viewer.getByRole('button', { name: '← Flows' }).click()

      // Resources: the administration tabs are not offered at all.
      await goTo(viewer, 'Resources')
      await expect(viewer.getByRole('heading', { name: 'Agents' })).toBeVisible()
      for (const tab of ['Service accounts', 'Organizations', 'Groups']) {
        await expect(viewer.getByRole('button', { name: tab, exact: true }), tab).toHaveCount(0)
      }

      // And the rule itself: a write is refused whatever the screen offered.
      const flow = await apiCall(viewer, 'POST', '/api/flows', { name: 'viewer-made', nodes: [], edges: [] })
      expect(flow.status()).toBe(403)
      const agent = await apiCall(viewer, 'POST', '/api/agents', { name: 'viewer-made' })
      expect(agent.status()).toBe(403)
      expect((await viewer.request.get('/api/settings')).status()).toBe(403)
      expect((await viewer.request.get('/api/service-accounts')).status()).toBe(403)
    } finally {
      await viewer.context().close()
    }
  })
})
