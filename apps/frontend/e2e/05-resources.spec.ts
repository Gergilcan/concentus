import { expect, goTo, openApp, test } from './fixtures'

/**
 * Every Resources tab renders its own panel. Each assertion targets text that exists only inside
 * that panel — the tab buttons share names with some panel headings, so headings and in-panel
 * copy are what prove the switch actually happened.
 */

test('all eight tabs render their panels', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')

  // Agents is the initial tab: the library CRUD panel.
  await expect(page.getByRole('heading', { name: 'Agents' })).toBeVisible()

  await page.getByRole('button', { name: 'MCP Servers' }).click()
  await expect(page.getByText('Catalog — one click to add')).toBeVisible()

  await page.getByRole('button', { name: 'Facades' }).click()
  await expect(page.getByRole('heading', { name: 'Facade profiles' })).toBeVisible()

  await page.getByRole('button', { name: 'Databases' }).click()
  await expect(page.getByRole('heading', { name: 'Databases' })).toBeVisible()

  await page.getByRole('button', { name: 'Knowledge' }).click()
  // The search box only exists once a base is selected; the CRUD shell is what always renders.
  await expect(page.getByRole('heading', { name: 'Knowledge bases' })).toBeVisible()

  await page.getByRole('button', { name: 'Skills' }).click()
  await expect(page.getByRole('heading', { name: 'Agent Skills' })).toBeVisible()

  await page.getByRole('button', { name: 'Credentials' }).click()
  await expect(
    page
      .getByText('Select a credential, or create one.')
      .or(page.getByText('Credential storage is disabled')),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Storage' }).click()
  await expect(page.locator('option', { hasText: 'Embedded — ships with the app' })).toHaveCount(1)
})

test('the MCP catalog has one-click entries', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'MCP Servers' }).click()

  // The gallery ships in the frontend itself, so entries must exist even on a fresh install.
  const catalog = page.getByText('Catalog — one click to add').locator('..')
  await expect(catalog.getByRole('button').first()).toBeVisible()
})
