import { expect, goTo, test } from './fixtures'
import {
  addMember,
  apiCall,
  apiJson,
  ENTERPRISE_PORT,
  groupSettingText,
  onLicensed,
  openTab,
  signInAs,
} from './16-settings.helpers'

/**
 * A group's Settings and Policy sub-tabs, beyond what 13-groups.spec.ts covers (visibility):
 * every group-scoped key inherits until overridden, an override persists without touching the
 * organization's value, a policy rule has an inherit switch — and a manager who is not an admin
 * edits the group but cannot make one.
 */

const MANAGER = 'manager-e2e@e2e.test'
const GROUP = 'E2E squad'

interface GroupSettings {
  values: Record<string, string>
  keys: Array<{ key: string }>
  inherited: Record<string, string>
}

test("a group's settings inherit until overridden, its policy has inherit switches, and a manager edits but cannot create", async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async (s) => {
    const { page } = s
    await openTab(page, 'Members')
    await addMember(page, MANAGER, 'Member')

    await page.getByRole('button', { name: 'Groups', exact: true }).click()
    await page.getByRole('button', { name: '+ New' }).click()
    await page.getByLabel('Name', { exact: true }).fill(GROUP)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    const row = page.getByRole('listitem').filter({ hasText: GROUP })
    await expect(row).toContainText('0 members')
    const groupId = (await apiJson<{ groups: { id: string }[] }>(page, '/api/groups')).groups[0].id
    await row.getByRole('button', { name: 'Open' }).click()

    // The manager, from the organization's accounts.
    await page.getByRole('tab', { name: 'Members' }).click()
    await page.getByRole('button', { name: 'Add member' }).click()
    await page.getByRole('combobox', { name: 'Account', exact: true }).selectOption({ label: MANAGER })
    await page.getByLabel('Manager', { exact: true }).check()
    await page.getByRole('button', { name: 'Add member' }).click()
    await expect(page.getByLabel(`Manager: ${MANAGER}`)).toBeChecked()
    await expect(row).toContainText('1 member')

    // Settings: the group-scoped keys, all inherited; one overridden and saved.
    await page.getByRole('tab', { name: 'Settings' }).click()
    const settings = await apiJson<GroupSettings>(page, `/api/groups/${groupId}/settings`)
    const keys = settings.keys.map((k) => k.key)
    expect(keys).toEqual(
      expect.arrayContaining(['usage.weekly-allowance-usd', 'workers.timeout-seconds', 'workers.retries', 'local.permission-mode']),
    )
    expect(settings.values).toEqual({})
    for (const key of keys) {
      await expect(page.locator(`[id="group-setting-${key}"]`), key).toBeVisible()
      await expect(groupSettingText(page, key), key).toContainText('inherited')
    }
    await page.locator('[id="group-setting-workers.retries"]').fill('7')
    await expect(groupSettingText(page, 'workers.retries')).toContainText('set for this group')
    await expect(page.getByRole('button', { name: 'Reset' })).toHaveCount(1)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved. Applies to the next run.')).toBeVisible()
    expect((await apiJson<GroupSettings>(page, `/api/groups/${groupId}/settings`)).values).toEqual({ 'workers.retries': '7' })
    // The organization's own value did not move.
    const org = await apiJson<{ settings: Array<{ key: string; source: string; value: string }> }>(page, '/api/settings')
    const orgRetries = org.settings.find((e) => e.key === 'workers.retries')!
    expect(orgRetries.source).not.toBe('STORED')
    expect(orgRetries.value).not.toBe('7')
    // Reset puts the key back to inheriting.
    await page.getByRole('button', { name: 'Reset' }).click()
    await expect(groupSettingText(page, 'workers.retries')).toContainText('inherited')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved. Applies to the next run.')).toBeVisible()
    expect((await apiJson<GroupSettings>(page, `/api/groups/${groupId}/settings`)).values).toEqual({})

    // Policy: a rule left to inherit is the organization's; switched off, the group's own applies.
    await page.getByRole('tab', { name: 'Policy' }).click()
    const inherit = page.getByLabel('Inherit: Permission ceiling')
    const ceiling = page.getByLabel('Permission ceiling', { exact: true })
    await expect(inherit).toBeChecked()
    await expect(ceiling).toBeDisabled()
    await expect(page.getByText('in effect: no ceiling')).toBeVisible()
    await inherit.uncheck()
    await expect(ceiling).toBeEnabled()
    await ceiling.selectOption('plan')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved. Applies to the next run.')).toBeVisible()
    await expect(page.getByText('in effect: Plan only — proposes, changes nothing')).toBeVisible()
    expect((await apiJson<{ maxPermissionMode: string | null }>(page, `/api/groups/${groupId}/policy`)).maxPermissionMode).toBe('plan')
    await inherit.check()
    await expect(ceiling).toBeDisabled()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('in effect: no ceiling')).toBeVisible()
    // Inherited again: the rule is left out of the answer (non_null), which is what null looks like over the wire.
    expect((await apiJson<{ maxPermissionMode?: string | null }>(page, `/api/groups/${groupId}/policy`)).maxPermissionMode ?? null).toBeNull()

    // The manager: sees the tab, edits the group, cannot make or delete one.
    const manager = await signInAs(s, MANAGER)
    try {
      await goTo(manager, 'Resources')
      await manager.getByRole('button', { name: 'Groups', exact: true }).click()
      await expect(manager.getByRole('button', { name: '+ New' })).toHaveCount(0)
      const mine = manager.getByRole('listitem').filter({ hasText: GROUP })
      await expect(mine.getByText('manager', { exact: true })).toBeVisible()
      await expect(mine.getByRole('button', { name: 'Delete' })).toHaveCount(0)
      await mine.getByRole('button', { name: 'Open' }).click()
      await manager.getByRole('tab', { name: 'Settings' }).click()
      // One more than what is inherited: typing the inherited value back changes nothing, and a
      // controlled input that did not change never dirties the form.
      const inherited = (await apiJson<GroupSettings>(manager, `/api/groups/${groupId}/settings`)).inherited['workers.timeout-seconds']
      const timeout = String(Number(inherited || 0) + 1)
      await manager.locator('[id="group-setting-workers.timeout-seconds"]').fill(timeout)
      await expect(groupSettingText(manager, 'workers.timeout-seconds')).toContainText('set for this group')
      const saved = manager.waitForResponse((r) => r.url().includes('/settings') && r.request().method() === 'PUT')
      await manager.getByRole('button', { name: 'Save', exact: true }).click()
      expect((await saved).ok()).toBe(true)
      expect((await apiJson<GroupSettings>(manager, `/api/groups/${groupId}/settings`)).values).toEqual({ 'workers.timeout-seconds': timeout })
      const create = await apiCall(manager, 'POST', '/api/groups', { name: 'manager-made', description: '' })
      expect(create.status()).toBe(403)
      expect(((await create.json()) as { error: string }).error).toContain('This action requires an organization administrator.')
      expect((await apiCall(manager, 'DELETE', `/api/groups/${groupId}`)).status()).toBe(403)
      await expect(manager.getByRole('button', { name: 'Organizations', exact: true })).toHaveCount(0)
    } finally {
      await manager.context().close()
    }
  })
})
