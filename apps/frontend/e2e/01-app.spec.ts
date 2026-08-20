import { expect, goTo, openApp, test } from './fixtures'

test('Ctrl+K reaches a view without touching the navigation', async ({ page }) => {
  await openApp(page)

  await page.keyboard.press('Control+k')
  const palette = page.getByRole('dialog', { name: 'Command palette' })
  await expect(palette).toBeVisible()

  // Three keystrokes and Enter: the promise the palette makes.
  await page.keyboard.type('res')
  await page.keyboard.press('Enter')

  await expect(page.getByRole('button', { name: 'MCP Servers' })).toBeVisible()
  await expect(page.getByRole('dialog', { name: 'Command palette' })).toHaveCount(0)
})

/**
 * The shell: the app loads, the header is there, and all four views actually render. This is the
 * canary spec — if serving the UI from the jar broke, everything here fails first and plainly.
 */

test('loads and shows the header with all four views', async ({ page }) => {
  await openApp(page)
  await expect(page.getByText('Concentus').first()).toBeVisible()
  for (const view of ['Flows', 'Studio', 'Resources', 'Usage'] as const) {
    await expect(page.getByRole('button', { name: view, exact: true })).toBeVisible()
  }
})

test('every view renders its own content', async ({ page }) => {
  await openApp(page)

  await expect(page.getByLabel('Search flows')).toBeVisible()

  await goTo(page, 'Studio')
  await expect(page.getByRole('heading', { name: 'Add node' })).toBeVisible()
  await expect(page.getByLabel('Flow name')).toBeVisible()

  await goTo(page, 'Resources')
  await expect(page.getByRole('button', { name: 'MCP Servers' })).toBeVisible()

  await goTo(page, 'Usage')
  await expect(
    page
      .getByText('Last 5 hours')
      .or(page.getByText(/No Claude Code transcripts/))
      .or(page.getByText('Reading transcripts…')),
  ).toBeVisible()

  await goTo(page, 'Flows')
  await expect(page.getByLabel('Search flows')).toBeVisible()
})
