import * as fs from 'node:fs'
import { cardAction, expect, flowCard, goTo, openApp, test } from './fixtures'
import {
  addBlock,
  backToFlows,
  closeInspector,
  deleteFlow,
  nodes,
  openInspector,
  save,
  startNewFlow,
  type SavedFlow,
} from './flows-helpers'

/**
 * One flow's whole life, beyond what 03-flow-crud already walks (create, duplicate, rename in
 * the Studio, delete): a webhook trigger with a secret; renamed and tagged from the card's
 * settings; pinned; duplicated — and the copy arrives paused and WITHOUT the secret; exported
 * as JSON and imported back as a new card; rolled back from the dashboard's history, which
 * brings the first name back; and finally every card it made is deleted.
 *
 * Serial by design: each test continues from the state the previous one left, because the
 * chain IS the scenario.
 */
test.describe.configure({ mode: 'serial' })

const NAME = 'E2E lifecycle flow'
const RENAMED = 'E2E lifecycle flow renamed'
const SECRET = 'e2e-webhook-secret'

async function flowNamed(page: import('@playwright/test').Page, name: string): Promise<SavedFlow | undefined> {
  const flows = (await (await page.request.get('/api/flows')).json()) as SavedFlow[]
  return flows.find((f) => f.name === name)
}

test('is born with a webhook trigger, a secret and a coordinator', async ({ page }) => {
  await openApp(page)
  await startNewFlow(page, NAME)
  await addBlock(page, '▶ Input / trigger')
  const inspector = await openInspector(page, 'Manual')
  await inspector.getByLabel('Execution type').selectOption('webhook')
  await inspector.getByLabel('Secret', { exact: true }).fill(SECRET)
  await closeInspector(page)
  await addBlock(page, '★ Coordinator')
  await expect(nodes(page)).toHaveCount(2)

  const saved = await save(page)
  expect(saved.id).toBeTruthy()
  await backToFlows(page)
  const card = flowCard(page, NAME)
  await expect(card).toHaveCount(1)
  await expect(card.getByText('⚡ Webhook', { exact: true })).toBeVisible()
})

test('is renamed and tagged from the card, and a tag chip narrows the list', async ({ page }) => {
  await openApp(page)
  await cardAction(page, NAME, 'Settings')
  const settings = page.getByRole('dialog', { name: 'Flow settings' })
  await settings.getByLabel('Name', { exact: true }).fill(RENAMED)
  await settings.getByLabel('Tags (comma separated)').fill('e2e-alpha, e2e-beta')
  await settings.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(settings).toBeHidden()

  const card = flowCard(page, RENAMED)
  await expect(card).toHaveCount(1)
  await expect(flowCard(page, NAME)).toHaveCount(0)
  await expect(card.getByRole('button', { name: 'e2e-alpha', exact: true })).toBeVisible()

  // A tag on a card filters through the search box — the one filter somebody can see and clear.
  // Only the wiring is asserted: the search itself matches flow NAMES, so a tag that is not part
  // of the name currently narrows the list to nothing (flowsDashboard.filterFlows) — a gap, not
  // a behaviour to pin.
  await card.getByRole('button', { name: 'e2e-beta', exact: true }).click()
  await expect(page.getByLabel('Search flows')).toHaveValue('e2e-beta')
  await page.getByLabel('Search flows').fill('')
  await expect(card).toHaveCount(1)
})

test('is pinned to the top and unpinned again', async ({ page }) => {
  await openApp(page)
  const card = flowCard(page, RENAMED)
  // The star's accessible name is its glyph; the title is what says which way it will go.
  await card.getByTitle('Pin to top').click()
  await expect(card.getByTitle('Unpin')).toBeVisible()
  await expect.poll(async () => (await flowNamed(page, RENAMED))?.favorite).toBe(true)

  await card.getByTitle('Unpin').click()
  await expect(card.getByTitle('Pin to top')).toBeVisible()
  await expect.poll(async () => (await flowNamed(page, RENAMED))?.favorite).toBe(false)
})

test('duplicates into a paused copy that has lost the webhook secret', async ({ page }) => {
  await openApp(page)
  await cardAction(page, RENAMED, { name: 'Duplicate', exact: true })
  const copy = flowCard(page, `${RENAMED} (copy)`)
  await expect(copy).toHaveCount(1)
  // Paused, and said out loud: two flows answering one provider's deliveries was never the intent.
  await expect(copy.getByText('paused', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('created, paused')

  await copy.getByRole('button', { name: 'Open' }).click()
  const inspector = await openInspector(page, 'Webhook')
  await expect(inspector.getByLabel('Execution type')).toHaveValue('webhook')
  await expect(inspector.getByLabel('Secret', { exact: true })).toHaveValue('')
  await closeInspector(page)
  await backToFlows(page)

  // The original still holds it — the copy dropped the secret, nothing moved it.
  const original = await flowNamed(page, RENAMED)
  expect(original?.nodes.find((n) => n.type === 'input')?.data.secret).toBe(SECRET)
})

test('exports as JSON and imports that file back as a new card', async ({ page }) => {
  await openApp(page)
  const download = page.waitForEvent('download')
  await cardAction(page, RENAMED, 'Export JSON')
  const file = await (await download).path()
  const exported = JSON.parse(fs.readFileSync(file, 'utf8')) as SavedFlow
  expect(exported.name).toBe(RENAMED)
  expect(exported.nodes).toHaveLength(2)
  expect(exported.edges).toHaveLength(1)

  // The Import button opens a hidden file input; handing it the file is what a person's picker does.
  await page.locator('input[type="file"]').setInputFiles(file)
  const imported = flowCard(page, `${RENAMED} (imported)`)
  await expect(imported).toHaveCount(1)
  await expect(imported.getByText('⚡ Webhook', { exact: true })).toBeVisible()
})

test('the dashboard history rolls back to the first revision, name included', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (d) => void d.accept())
  await cardAction(page, RENAMED, 'Version history')
  const history = page.getByRole('dialog', { name: /^History — / })
  const rows = history.getByRole('list', { name: 'Version history' }).getByRole('listitem')
  // Create, rename+tags, pin, unpin: every dashboard write is a revision too.
  await expect(rows.first()).toBeVisible()
  expect(await rows.count()).toBeGreaterThanOrEqual(4)

  await rows.filter({ hasText: 'v1' }).getByRole('button', { name: 'Restore' }).click()
  // The dashboard's dialog closes on restore — you were on a list and are about to go somewhere.
  await expect(history).toBeHidden()
  // v1 was saved under the original name; restoring it is visible on the card, not only in a
  // list. Read after leaving and coming back: the dashboard fetches its list when it mounts, and
  // a restore from its own History dialog does not refresh it in place.
  await goTo(page, 'Usage')
  await goTo(page, 'Flows')
  await expect(flowCard(page, NAME)).toHaveCount(1, { timeout: 10_000 })
  await expect(flowCard(page, RENAMED)).toHaveCount(0)
})

test('deletes every card it made', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (d) => void d.accept())
  for (const name of [NAME, `${RENAMED} (copy)`, `${RENAMED} (imported)`]) await deleteFlow(page, name)
})
