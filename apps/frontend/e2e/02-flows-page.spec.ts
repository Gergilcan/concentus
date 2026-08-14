import { expect, flowCard, openApp, test } from './fixtures'

/**
 * The Flows dashboard: KPIs, the toolbar, search and the empty states. Tolerant of whether the
 * fresh database seeded library flows or not — both are legitimate first-boot states, and the
 * CRUD spec that follows creates its own flows regardless.
 */

test('shows the dashboard: title, KPIs and toolbar', async ({ page }) => {
  await openApp(page)
  await expect(page.getByRole('heading', { name: 'Flows', level: 1 })).toBeVisible()
  await expect(page.getByText('Design multi-agent flows')).toBeVisible()

  // By the KPI class, not by text: "Flows" is also the nav button and the page title, and a
  // text lookup trips over all three. The order is the component's own render order.
  await expect(page.locator('[class*="kpiLabel"]')).toHaveText([
    'Flows',
    'Executions',
    'Success rate',
    'Running now',
    'Est. cost',
  ])

  await expect(page.getByLabel('Search flows')).toBeVisible()
  await expect(page.getByLabel('Sort flows')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Import' })).toBeVisible()
  await expect(page.getByRole('button', { name: '✨ Describe a flow' })).toBeVisible()
  await expect(page.getByRole('button', { name: '+ New flow' })).toBeVisible()
})

test('a recipe builds a configured flow from the bundled sample', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '🍳 Recipes' }).click()

  const dialog = page.getByRole('dialog')
  await dialog.getByText('Send me a briefing every morning').click()

  await dialog.getByLabel('Topics').fill('E2E topics')
  await dialog.getByRole('button', { name: 'Next' }).click()
  // exact: label matching is case-insensitive and substring-based, and the "Start it now"
  // checkbox's own text says "it runs on a schedule".
  await dialog.getByLabel('Schedule', { exact: true }).fill('15 6 * * *')
  await dialog.getByRole('button', { name: 'Create the flow' }).click()

  // Straight onto the canvas, carrying the answers: this is assembled from the REAL bundled
  // sample through the real API, so a sample the seeder stopped installing would fail here.
  await expect(page.getByLabel('Flow name')).toHaveValue('Send me a briefing every morning')
  await expect(page.locator('.react-flow__node')).toHaveCount(2)

  // Cleaned up: this really saves a flow, and the later tests in this file count what is on the
  // dashboard. A test that leaves state behind breaks its neighbours, not itself.
  page.on('dialog', (d) => void d.accept())
  await page.getByRole('button', { name: '← Flows' }).click()
  await flowCard(page, 'Send me a briefing every morning').getByTitle('Delete').click()
  await expect(flowCard(page, 'Send me a briefing every morning')).toHaveCount(0)
})

test('“Describe a flow” opens its dialog and promises nothing is saved', async ({ page }) => {
  await openApp(page)

  await page.getByRole('button', { name: '✨ Describe a flow' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('Describe what you want automated')).toBeVisible()
  // The generation itself needs the claude CLI and a real model call, which this suite never
  // makes — what is asserted here is the entry point and its promise. Generate stays disabled
  // until there is something to generate FROM.
  await expect(dialog.getByRole('button', { name: 'Generate' })).toBeDisabled()
  await expect(dialog.getByText(/Nothing is saved/)).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('search filters the cards and clearing brings them back', async ({ page }) => {
  await openApp(page)
  const cards = page.getByRole('article')
  const folderHeads = page.getByRole('button', { name: /^Folder / })

  if ((await cards.count()) === 0 && (await folderHeads.count()) === 0) {
    // A genuinely empty install: the empty state IS the content here.
    await expect(page.getByText('No flows yet')).toBeVisible()
    return
  }

  await page.getByLabel('Search flows').fill('zzz-nothing-matches-this-zzz')
  await expect(page.getByText('Nothing matches those filters')).toBeVisible()
  await expect(cards).toHaveCount(0)

  await page.getByLabel('Search flows').fill('')
  // The settled state returns: root cards, or the closed folder sections.
  await expect(cards.first().or(folderHeads.first())).toBeVisible()
})

test('the samples live in a folder you enter and leave — and search reaches inside', async ({ page }) => {
  await openApp(page)

  // A fresh database seeds the bundled flows into "Samples": the first launch shows one folder
  // tile, an invitation instead of a wall of cards.
  const samples = page.getByRole('button', { name: 'Folder Samples' })
  await expect(samples).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(0)

  // Entering shows the flows and the way back; the breadcrumb returns to the root.
  await samples.click()
  expect(await page.getByRole('article').count()).toBeGreaterThan(0)
  await page.getByRole('button', { name: 'All flows' }).click()
  await expect(page.getByRole('article')).toHaveCount(0)
  await expect(samples).toBeVisible()

  // Searching suspends the tree and shows matches flat, wherever they live — a filter that hid
  // its matches inside folders would be broken search.
  await page.getByLabel('Search flows').fill('a')
  expect(await page.getByRole('article').count()).toBeGreaterThan(0)
  await page.getByLabel('Search flows').fill('')
})

test('a flow moves into a folder from its settings', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill('E2E foldered flow')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('button', { name: '← Flows' }).click()

  await flowCard(page, 'E2E foldered flow').getByTitle('Settings').click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel(/Folder/).fill('E2E Carpeta')
  await dialog.getByRole('button', { name: 'Save' }).click()

  // The card left the root for its new folder's tile.
  const folder = page.getByRole('button', { name: 'Folder E2E Carpeta' })
  await expect(folder).toBeVisible()
  await expect(flowCard(page, 'E2E foldered flow')).toHaveCount(0)
  await folder.click()
  await expect(flowCard(page, 'E2E foldered flow')).toHaveCount(1)

  // Clean up: back to the root, then delete.
  await flowCard(page, 'E2E foldered flow').getByTitle('Settings').click()
  await page.getByRole('dialog').getByLabel(/Folder/).fill('')
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click()
  await page.getByRole('button', { name: 'All flows' }).click()
  page.on('dialog', (d) => void d.accept())
  await flowCard(page, 'E2E foldered flow').getByTitle('Delete').click()
  await expect(flowCard(page, 'E2E foldered flow')).toHaveCount(0)
})

test('drag and drop: a card into a folder, a folder into a folder, a card back out', async ({ page }) => {
  await openApp(page)
  const newFlow = async (name: string, folder?: string) => {
    await page.getByRole('button', { name: '+ New flow' }).first().click()
    await page.getByLabel('Flow name').fill(name)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await page.getByRole('button', { name: '← Flows' }).click()
    if (folder) {
      await flowCard(page, name).getByTitle('Settings').click()
      const dialog = page.getByRole('dialog')
      await dialog.getByLabel(/Folder/).fill(folder)
      await dialog.getByRole('button', { name: 'Save' }).click()
    }
  }
  await newFlow('E2E dnd anchor', 'DnD Uno')
  await newFlow('E2E dnd mover')

  // Card onto a folder tile: the card leaves the root and appears inside.
  const uno = page.getByRole('button', { name: 'Folder DnD Uno' })
  await flowCard(page, 'E2E dnd mover').dragTo(uno)
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(0)
  await uno.click()
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(1)

  // Card onto a breadcrumb segment: back out to the root.
  await flowCard(page, 'E2E dnd mover').dragTo(page.getByRole('button', { name: 'All flows' }))
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(0)
  await page.getByRole('button', { name: 'All flows' }).click()
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(1)

  // Folder onto a folder: nest it, then walk down the two levels.
  await flowCard(page, 'E2E dnd mover').getByTitle('Settings').click()
  await page.getByRole('dialog').getByLabel(/Folder/).fill('DnD Dos')
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click()
  const dos = page.getByRole('button', { name: 'Folder DnD Dos' })
  await dos.dragTo(uno)
  await expect(dos).toHaveCount(0)
  await uno.click()
  await expect(page.getByRole('button', { name: 'Folder DnD Dos' })).toBeVisible()
  await page.getByRole('button', { name: 'Folder DnD Dos' }).click()
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(1)

  // Clean up: pull both flows to the root and delete them.
  page.on('dialog', (d) => void d.accept())
  for (const name of ['E2E dnd mover', 'E2E dnd anchor']) {
    await page.getByLabel('Search flows').fill(name)
    await flowCard(page, name).getByTitle('Delete').click()
    await expect(flowCard(page, name)).toHaveCount(0)
  }
  await page.getByLabel('Search flows').fill('')
})

test('a folder can be born empty, filled by drag, and removed when empty again', async ({ page }) => {
  await openApp(page)
  // Only the delete step confirms via a dialog now — creation is an inline input (window.prompt
  // does not exist in Electron, which is exactly why the tile grew its own field).
  page.on('dialog', (d) => void d.accept())

  await page.getByRole('button', { name: '+ New folder' }).click()
  await page.getByLabel('New folder name').fill('E2E Nueva')
  await page.getByLabel('New folder name').press('Enter')
  const tile = page.getByRole('button', { name: 'Folder E2E Nueva', exact: true })
  await expect(tile).toBeVisible()

  // The empty folder is a real drop target: a dragged card makes it a real folder.
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill('E2E draft flow')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('button', { name: '← Flows' }).click()
  await flowCard(page, 'E2E draft flow').dragTo(tile)
  await expect(flowCard(page, 'E2E draft flow')).toHaveCount(0)
  await tile.click()
  await expect(flowCard(page, 'E2E draft flow')).toHaveCount(1)

  // Deleting its only flow empties it; back at the root the tile is still there (a folder must
  // not vanish under the user), and the ✕ takes it away for good.
  await flowCard(page, 'E2E draft flow').getByTitle('Delete').click()
  await expect(flowCard(page, 'E2E draft flow')).toHaveCount(0)
  await page.getByRole('button', { name: 'All flows' }).click()
  await expect(tile).toBeVisible()
  await page.getByLabel('Remove empty folder E2E Nueva').click()
  await expect(tile).toHaveCount(0)
})

test('sorting does not lose any card', async ({ page }) => {
  await openApp(page)
  const cards = page.getByRole('article')
  const initial = await cards.count()
  for (const sort of ['name', 'runs', 'recent']) {
    await page.getByLabel('Sort flows').selectOption(sort)
    await expect(cards).toHaveCount(initial)
  }
})
