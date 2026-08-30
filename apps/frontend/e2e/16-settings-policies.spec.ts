import { cardAction, expect, flowCard, goTo, test } from './fixtures'
import {
  apiCall,
  ENTERPRISE_PORT,
  fanoutFlowWithMcp,
  onLicensed,
  openTab,
  reloadWorkspace,
} from './16-settings.helpers'

/**
 * Organization policies on an Enterprise backend, and the two places they are felt: the doctor,
 * which names the worker the facade rule refuses, and a published endpoint, which answers 404
 * until an administrator approves its token.
 *
 * The endpoint is opened but never run through: the suite makes no model calls, so once the door
 * is open the request is sent with an empty body — authorized, then refused for the body alone.
 * Before approval the same request gets the 404 an unpublished flow gets, on purpose.
 */

const FLOW = 'E2E policy flow'
const TOKEN = 'e2e-endpoint-token-0123456789abcdef'
const FACADE_RULE = "The organization's policy requires a facade profile on every independent worker that reaches MCP, and 'Worker' has none"

test('a default facade, a ceiling, a budget and publish approval — the doctor and the endpoint obey them', async ({ browser, playwright }, testInfo) => {
  test.setTimeout(240_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page, baseURL }) => {
    // A facade profile to point the policy at.
    await openTab(page, 'Facades')
    await page.getByRole('button', { name: '+ New' }).click()
    await page.getByLabel('Name', { exact: true }).fill('e2e-reader')
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()

    // The rules, without a default facade yet: the requirement has to be felt by the doctor.
    await page.getByRole('button', { name: 'Policies', exact: true }).click()
    await expect(page.getByRole('note')).toHaveCount(0)
    await page.getByLabel('Require a facade profile on every independent worker that reaches MCP').check()
    await page.getByLabel('Permission ceiling').selectOption('default')
    await page.getByLabel('Organization budget (USD per month, blank = none)').fill('100')
    await page.getByLabel("Published endpoints need an administrator's approval").check()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved. Applies to the next run.')).toBeVisible()

    await reloadWorkspace(page)
    await openTab(page, 'Policies')
    await expect(page.getByLabel('Require a facade profile on every independent worker that reaches MCP')).toBeChecked()
    await expect(page.getByLabel('Permission ceiling')).toHaveValue('default')
    await expect(page.getByLabel('Organization budget (USD per month, blank = none)')).toHaveValue('100')
    await expect(page.getByLabel("Published endpoints need an administrator's approval")).toBeChecked()

    // A fan-out whose worker reaches MCP with no profile — published, with a token.
    const created = await apiCall(page, 'POST', '/api/flows', fanoutFlowWithMcp(FLOW, TOKEN))
    expect(created.ok()).toBe(true)
    const flowId = ((await created.json()) as { id: string }).id

    const check = async () => {
      const answered = page.waitForResponse((r) => r.url().includes('/doctor'))
      await cardAction(page, FLOW, 'Check this flow')
      expect((await answered).ok()).toBe(true)
      return page.getByRole('dialog', { name: `Check — ${FLOW}` })
    }
    await reloadWorkspace(page)
    let doctor = await check()
    await expect(doctor.getByRole('list', { name: 'Findings' })).toContainText(FACADE_RULE)
    await expect(doctor.getByRole('list', { name: 'Findings' })).toContainText('Resources → Policies')
    await doctor.getByRole('button', { name: 'Close' }).click()

    // With a default, the same flow satisfies the rule.
    await openTab(page, 'Policies')
    await page.getByLabel('Default facade profile for independent workers').selectOption({ label: 'e2e-reader' })
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved. Applies to the next run.')).toBeVisible()
    await goTo(page, 'Flows')
    doctor = await check()
    await expect(doctor.getByText(/Nothing to fix|would fail|Nothing would fail/)).toBeVisible()
    await expect(doctor).not.toContainText('requires a facade profile')
    await doctor.getByRole('button', { name: 'Close' }).click()

    // The endpoint: 404 while it waits, then open once approved from the Input node.
    const caller = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: { Authorization: `Bearer ${TOKEN}` },
    })
    try {
      const knock = () => caller.post(`/api/public/flows/${flowId}/run`, { data: {} })
      expect((await knock()).status()).toBe(404)

      await flowCard(page, FLOW).getByRole('button', { name: 'Open' }).click()
      await page.locator('.react-flow__node').filter({ hasText: 'Input' }).first().dblclick()
      const node = page.getByRole('dialog')
      await expect(node.getByLabel('Endpoint token')).toHaveValue(TOKEN)
      await expect(node.getByText("Waiting for an administrator's approval")).toBeVisible()
      const approved = page.waitForResponse((r) => r.url().includes('/approve') && r.request().method() === 'POST')
      await node.getByRole('button', { name: 'Approve this endpoint' }).click()
      expect((await approved).ok()).toBe(true)
      await expect(node.getByText('Approved', { exact: true })).toBeVisible()
      await expect(node.getByRole('button', { name: 'Revoke approval' })).toBeVisible()
      await node.getByRole('button', { name: 'Close' }).click()

      // Authorized now: the only thing refused is the empty body, and no run was started.
      const opened = await knock()
      expect(opened.status()).toBe(400)
      expect(((await opened.json()) as { error: string }).error).toContain('non-empty "input"')
      // A wrong token on the open door is 401, not 404: the flow is no longer hidden.
      const wrong = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Authorization: 'Bearer nope' } })
      try {
        expect((await wrong.post(`/api/public/flows/${flowId}/run`, { data: { input: 'x' } })).status()).toBe(401)
      } finally {
        await wrong.dispose()
      }
    } finally {
      await caller.dispose()
    }
  })
})
