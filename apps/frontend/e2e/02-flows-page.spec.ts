import { expect, openApp, test } from './fixtures'

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
  await expect(page.getByRole('button', { name: '+ New flow' })).toBeVisible()
})

test('search filters the cards and clearing brings them back', async ({ page }) => {
  await openApp(page)
  const cards = page.getByRole('article')
  const initial = await cards.count()

  if (initial === 0) {
    // A fresh install with no seeded flows: the empty state IS the content here.
    await expect(page.getByText('No flows yet')).toBeVisible()
    return
  }

  await page.getByLabel('Search flows').fill('zzz-nothing-matches-this-zzz')
  await expect(page.getByText('Nothing matches those filters')).toBeVisible()
  await expect(cards).toHaveCount(0)

  await page.getByLabel('Search flows').fill('')
  await expect(cards).toHaveCount(initial)
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
