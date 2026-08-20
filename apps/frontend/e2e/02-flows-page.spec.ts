import { expect, flowCard, openApp, test } from './fixtures'

/**
 * The Flows dashboard: KPIs, the toolbar, search and the empty states. Tolerant of whether the
 * fresh database seeded library flows or not — both are legitimate first-boot states, and the
 * CRUD spec that follows creates its own flows regardless.
 */

test('shows the dashboard: KPIs and toolbar', async ({ page }) => {
  await openApp(page)

  // By the KPI class, not by text: "Flows" is also the nav button, and a text lookup trips over
  // both. The order is the component's own render order.
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
  await dialog.getByRole('button', { name: 'Next' }).click()
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

test('drag and drop: a card into a folder, a folder into a folder, a card back out', async ({
  page,
}) => {
  await openApp(page)

  /**
   * A folder, made where folders are made.
   *
   * <p>This used to be set up through the flow's settings dialog, which had a folder field. That
   * field is gone: it asked somebody to remember the tree and to spell it, next to a dashboard
   * where the folders are visible and a card can be dropped on one. So the test files things the
   * way the application now does.
   */
  const newFolder = async (name: string) => {
    await page.getByRole('button', { name: '+ New folder' }).click()
    await page.getByLabel('New folder name').fill(name)
    await page.getByLabel('New folder name').press('Enter')
    // .first(): a folder is a tile in the grid and, once you are inside it, a breadcrumb too.
    await expect(page.getByRole('button', { name: `Folder ${name}` }).first()).toBeVisible()
  }
  const newFlow = async (name: string) => {
    await page.getByRole('button', { name: '+ New flow' }).first().click()
    await page.getByLabel('Flow name').fill(name)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await page.getByRole('button', { name: '← Flows' }).click()
  }
  const folder = (name: string) => page.getByRole('button', { name: `Folder ${name}` }).first()

  await newFolder('DnD Uno')
  await newFolder('DnD Dos')
  await newFlow('E2E dnd mover')
  // A folder with a flow in it is a real folder — derived from where its flows live — rather than
  // a draft this browser is remembering. Only a real one can be moved, because moving it is
  // re-filing what is inside it.
  await newFlow('E2E dnd resident')
  await flowCard(page, 'E2E dnd resident').dragTo(folder('DnD Dos'))

  // Card onto a folder tile: the card leaves the root and is found inside.
  await flowCard(page, 'E2E dnd mover').dragTo(folder('DnD Uno'))
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(0)
  await folder('DnD Uno').click()
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(1)

  // Card onto the breadcrumb: back out to the root.
  await flowCard(page, 'E2E dnd mover').dragTo(page.getByRole('button', { name: 'All flows' }))
  await page.getByRole('button', { name: 'All flows' }).click()
  await expect(flowCard(page, 'E2E dnd mover')).toHaveCount(1)

  // Folder onto a folder: what was at the root is now inside the other one.
  //
  // Not asserted by its absence at the root: "+ New folder" also records a draft path in this
  // browser, and a draft outlives the flows moving out from under it — deliberately, so a folder
  // somebody made does not vanish the moment it empties. What matters here is that the nesting
  // happened, which is what walking into it shows.
  await folder('DnD Dos').dragTo(folder('DnD Uno'))
  await folder('DnD Uno').click()
  await expect(folder('DnD Dos')).toBeVisible()
  await folder('DnD Dos').click()
  await expect(flowCard(page, 'E2E dnd resident')).toHaveCount(1)

  // Clean up: the flow is at the root, and the folders are drafts this browser remembers.
  await page.getByRole('button', { name: 'All flows' }).click()
  page.on('dialog', (d) => void d.accept())
  for (const name of ['E2E dnd mover', 'E2E dnd resident']) {
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
