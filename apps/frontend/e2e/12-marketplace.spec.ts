import { expect, goTo, openApp, test } from './fixtures'

/**
 * The marketplace, end to end on a fresh deployment: what the app seeds is there to browse, and
 * an item published by hand can be installed and then found among the organization's resources.
 *
 * One organization, so the publish form shows no scope and the item is global; the first account
 * administers the oldest organization and is therefore the curator, so when the item arrives
 * pending the same person approves it. Both paths — born published or approved by hand — end in
 * the same card, which is what the assertions look at.
 */
test('the seeded library is browsable, and a published MCP server installs into Resources', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Marketplace')

  // Seeded: the MCP catalogue, the library agents and the starter flows are cards already.
  await expect(page.getByTestId('marketplace-card').first()).toBeVisible()
  const seeded = await page.getByTestId('marketplace-card').count()
  expect(seeded).toBeGreaterThan(10)

  // Search narrows locally.
  await page.getByRole('textbox', { name: 'Search the Marketplace' }).fill('Tech Lead')
  await expect(page.getByTestId('marketplace-card')).toHaveCount(1)
  await page.getByRole('textbox', { name: 'Search the Marketplace' }).fill('')

  // Publish an MCP server by pasting its definition.
  await page.getByRole('button', { name: '+ Publish' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Kind').selectOption('mcp')
  await dialog.getByRole('radio', { name: 'Paste JSON' }).check()
  await dialog.getByLabel('Payload (JSON)').fill('{"name":"E2E weather","url":"https://mcp.example.test/weather","auth":"none"}')
  await dialog.getByLabel('Name', { exact: true }).fill('E2E weather')
  await dialog.getByLabel('Summary (one line)').fill('Forecasts for the e2e suite')
  await dialog.getByLabel('Tags (comma-separated)').fill('weather, e2e')
  await dialog.getByRole('button', { name: 'Publish', exact: true }).click()
  await expect(dialog).toBeHidden()

  // The card is there; open it. A curator approves a pending one, then installs.
  const card = page.getByTestId('marketplace-card').filter({ hasText: 'E2E weather' })
  await expect(card).toHaveCount(1)
  await card.click()
  const item = page.getByRole('dialog')
  const approve = item.getByRole('button', { name: 'Approve' })
  if (await approve.isVisible()) await approve.click()
  await item.getByRole('button', { name: 'Install', exact: true }).click()
  await expect(item.getByRole('button', { name: 'Uninstall' })).toBeVisible()
  await item.getByRole('button', { name: 'Open in Resources' }).click()

  // It landed as an MCP server of this organization.
  await expect(page.getByRole('button', { name: 'MCP Servers' })).toBeVisible()
  await expect(page.getByText('E2E weather').first()).toBeVisible()
})
