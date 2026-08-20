import { expect, flowCard, openApp, test } from './fixtures'

/**
 * Flow version history end to end: every save appends a revision, the Studio's Versions tab lists
 * them with who saved them, and a revision can be previewed on the canvas and restored — with the
 * history staying append-only throughout.
 *
 * Serial by design: the whole point is what a SECOND save does to the history the first one made.
 *
 * Not covered here: the version badge on an execution, which needs a real run (and therefore the
 * claude CLI) that this suite deliberately never starts. Its rendering is unit-tested instead.
 */
test.describe.configure({ mode: 'serial' })

const NAME = 'E2E versioned flow'
type Page = import('@playwright/test').Page

const nodes = (page: Page) => page.locator('.react-flow__node')
/** The rows of the version list, scoped by its accessible name so other lists cannot creep in. */
const versionRows = (page: Page) =>
  page.getByRole('list', { name: 'Version history' }).getByRole('listitem')

/**
 * Saves and waits for the write to land. The node count on screen is already what the user drew,
 * so it proves nothing about the save — and the version list read right after would race the POST
 * that is supposed to have produced the revision it is looking for.
 */
async function save(page: Page): Promise<void> {
  const written = page.waitForResponse(
    (r) => r.url().includes('/api/flows') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  expect((await written).ok()).toBe(true)
}

/**
 * Clears the selection so the inspector shows the flow's own tabs. Near a corner rather than the
 * pane's centre, which the nodes themselves sit on — a click there lands on a node and reselects
 * exactly what this is trying to clear.
 */
async function deselect(page: Page): Promise<void> {
  await page.locator('.react-flow__pane').click({ position: { x: 8, y: 8 } })
  await page.getByRole('button', { name: 'Versions' }).click()
}

test('two saves produce two revisions, credited to the local user', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill(NAME)

  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  await save(page)
  await expect(nodes(page)).toHaveCount(1)

  await page.getByRole('button', { name: '◆ Agent' }).click()
  await save(page)
  await expect(nodes(page)).toHaveCount(2)

  await deselect(page)

  await expect(versionRows(page)).toHaveCount(2)
  await expect(page.getByText('v2', { exact: true })).toBeVisible()
  // The desktop profile runs with authentication off: one person, credited as "local". Scoped to
  // the row: unscoped, "local" also matches the mode select's hidden <option> and the header's
  // "Local (subscription)" badge — and which one comes first depends on the machine, not the code.
  await expect(versionRows(page).filter({ hasText: 'v2' }).getByText('e2e@concentus.test')).toBeVisible()
  // The newest revision is the current one, and the second save added exactly one node.
  await expect(page.getByText('current')).toBeVisible()
  await expect(page.getByText(/\+1 nodes/)).toBeVisible()
})

test('previewing a revision shows it on the canvas, and "Back to latest" returns', async ({ page }) => {
  await openApp(page)
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()
  await expect(nodes(page)).toHaveCount(2)

  await page.getByRole('button', { name: 'Versions' }).click()
  // The older revision's row: v1 had a single node.
  await versionRows(page).filter({ hasText: 'v1' }).getByRole('button', { name: 'Preview' }).click()

  await expect(nodes(page)).toHaveCount(1)
  await expect(page.getByText(/Previewing v1/)).toBeVisible()

  await page.getByRole('button', { name: 'Back to latest' }).click()
  await expect(nodes(page)).toHaveCount(2)
  await expect(page.getByText(/Previewing v1/)).toHaveCount(0)
})

test('restoring an old revision appends it as a new one instead of erasing history', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (dialog) => void dialog.accept())
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()
  await page.getByRole('button', { name: 'Versions' }).click()

  await versionRows(page).filter({ hasText: 'v1' }).getByRole('button', { name: 'Restore' }).click()

  // The canvas is v1 again…
  await expect(nodes(page)).toHaveCount(1)
  // …and the restore itself is v3: nothing was overwritten, so v2 is still one click away.
  await expect(page.getByText('v3', { exact: true })).toBeVisible()
  await expect(versionRows(page)).toHaveCount(3)
})

test('cleans up its flow', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (dialog) => void dialog.accept())
  await flowCard(page, NAME).getByTitle('Delete').click()
  await expect(flowCard(page, NAME)).toHaveCount(0)
})
