import { expect, goTo, openApp, test } from './fixtures'

/**
 * Every Resources tab renders its own panel. Each assertion targets text that exists only inside
 * that panel — the tab buttons share names with some panel headings, so headings and in-panel
 * copy are what prove the switch actually happened.
 */

test('all nine tabs render their panels', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')

  // Agents is the initial tab: the library CRUD panel.
  await expect(page.getByRole('heading', { name: 'Agents' })).toBeVisible()

  await page.getByRole('button', { name: 'MCP Servers' }).click()
  await expect(page.getByText('Catalog — one click to add')).toBeVisible()
  // Collapsed by default: the toggle row is there, the wall of cards is not.
  await expect(page.getByRole('tab')).toHaveCount(0)

  await page.getByRole('button', { name: 'Facades' }).click()
  await expect(page.getByRole('heading', { name: 'Facade profiles' })).toBeVisible()

  await page.getByRole('button', { name: 'Databases' }).click()
  await expect(page.getByRole('heading', { name: 'Databases' })).toBeVisible()

  await page.getByRole('button', { name: 'Knowledge' }).click()
  // The search box only exists once a base is selected; the CRUD shell is what always renders.
  await expect(page.getByRole('heading', { name: 'Knowledge bases' })).toBeVisible()

  await page.getByRole('button', { name: 'Skills' }).click()
  await expect(page.getByRole('heading', { name: 'Agent Skills' })).toBeVisible()

  await page.getByRole('button', { name: 'Variables' }).click()
  await expect(page.getByRole('heading', { name: 'Variables' })).toBeVisible()

  await page.getByRole('button', { name: 'Credentials' }).click()
  await expect(
    page
      .getByText('Select a credential, or create one.')
      .or(page.getByText('Credential storage is disabled')),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Storage' }).click()
  await expect(page.locator('option', { hasText: 'Embedded — ships with the app' })).toHaveCount(1)
})

test('the MCP catalog expands into category tabs with one-click entries', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'MCP Servers' }).click()

  await page.getByText('Catalog — one click to add').click()

  // The gallery ships in the frontend itself, so entries must exist even on a fresh install —
  // one category at a time, switched with the tabs.
  await expect(page.getByRole('tab', { name: 'Development' })).toBeVisible()
  await expect(page.getByText('Issues, PRs, repositories')).toBeVisible()
  await page.getByRole('tab', { name: 'Business' }).click()
  await expect(page.getByText('Customers, invoices, payments')).toBeVisible()
  await expect(page.getByText('Issues, PRs, repositories')).toHaveCount(0)

  // The Google and Microsoft shelves: local stdio servers, labelled as such.
  await page.getByRole('tab', { name: 'Google' }).click()
  await expect(page.getByText('Google Search Console')).toBeVisible()
  await page.getByRole('tab', { name: 'Microsoft' }).click()
  await expect(page.getByText('Microsoft Graph')).toBeVisible()
  await expect(page.getByText('Official Microsoft/Azure docs search')).toBeVisible()
})

test('picking a local catalogue server opens setup: its runtime, then its variables', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'MCP Servers' }).click()
  await page.getByText('Catalog — one click to add').click()
  await page.getByRole('tab', { name: 'Email' }).click()

  // Mailchimp runs locally (uvx) and reads two variables — it cannot just be added.
  await page.getByText('Mailchimp').click()
  const setup = page.getByRole('dialog')
  await expect(setup.getByText('Step 1 of 2')).toBeVisible()
  await expect(setup.getByText('uvx mailchimp-mcp')).toBeVisible()

  // Whether uv exists on the machine running these tests is not something to assert — CI runners
  // differ. What must hold is that the machine was asked, and answered.
  await expect(
    setup.getByText(/uv is not installed/).or(setup.getByText(/uv .* found/)),
  ).toBeVisible({ timeout: 15_000 })

  await setup.getByRole('button', { name: 'Next' }).click()
  // The variables come named from the catalogue: nothing to type but the values. exact, because
  // the "is a secret" checkbox next to each one carries the variable's name too.
  await expect(setup.getByLabel('MAILCHIMP_READ_ONLY', { exact: true })).toHaveValue('true')
  await setup.getByRole('button', { name: 'Add Mailchimp' }).click()

  // Saved through the real API and picked up by the list below. Found by the row's own delete
  // control: the name alone also matches the catalogue card it was added from.
  await expect(page.getByLabel('Delete Mailchimp')).toBeVisible()
})

test('a local server opens on its own JSON, and its variables are edited there', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'MCP Servers' }).click()
  await page.getByText('Catalog — one click to add').click()
  await page.getByRole('tab', { name: 'Email' }).click()
  await page.getByText('Mailtrap').click()
  const setup = page.getByRole('dialog')
  await setup.getByRole('button', { name: 'Next' }).click()
  await setup.getByRole('button', { name: 'Add Mailtrap' }).click()
  await expect(page.getByLabel('Delete Mailtrap')).toBeVisible()

  // Opening it is the part that used to throw: a local server has no URL, and reading one as a
  // string took the whole panel down.
  await page.getByRole('button', { name: 'Mailtrap Delete Mailtrap' }).click()
  const json = page.getByLabel('This server as JSON')
  await expect(json).toContainText('mcp-mailtrap')
  // One server, not the registry — and the URL form is gone, because this one is a command.
  await expect(json).not.toContainText('mcpServers')
  await expect(page.getByLabel('URL')).toHaveCount(0)

  await json.fill('{"command":"npx","args":["-y","mcp-mailtrap"],"env":{"MAILTRAP_API_TOKEN":"credential:x"}}')
  await page.getByRole('button', { name: 'Save JSON' }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()

  // Saved for real: leaving the form and coming back shows what the backend kept.
  await page.getByRole('button', { name: '+ New' }).click()
  await page.getByRole('button', { name: 'Mailtrap Delete Mailtrap' }).click()
  await expect(json).toContainText('MAILTRAP_API_TOKEN')
})

test('export downloads the whole configuration and import accepts it back', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'Storage' }).click()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export everything (.json)' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/^concentus-export-.*\.json$/)

  // The file is a real export: versioned, with every category present and no secrets.
  const path = await download.path()
  const fs = await import('node:fs')
  const doc = JSON.parse(fs.readFileSync(path, 'utf8')) as Record<string, unknown>
  expect(doc.concentusExport).toBe(1)
  for (const key of ['flows', 'agents', 'mcpServers', 'skills', 'variables', 'credentials']) {
    expect(doc, key).toHaveProperty(key)
  }

  // And it round-trips: importing the file we just exported reports what landed.
  await page.getByLabel('Import from file…').setInputFiles(path)
  await expect(page.getByText(/Imported |Nothing new to import/)).toBeVisible({ timeout: 20_000 })
})

test('the skill catalog lists the pinned GitHub collections', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Resources')
  await page.getByRole('button', { name: 'Skills' }).click()

  // Collapsed by default, like the MCP catalog.
  const toggle = page.getByText('Browse catalog — popular on GitHub')
  await expect(toggle).toBeVisible()
  await toggle.click()

  // Deterministic even offline or rate-limited: the backend always serves the pinned
  // repositories; GitHub search only ever adds to them.
  await expect(page.getByText('anthropics/skills')).toBeVisible({ timeout: 20_000 })
})
