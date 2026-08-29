import { expect, flowCard, goTo, openApp, test, cardAction } from './fixtures'

/**
 * The Studio: palette, canvas and inspector working together. Nodes are added through the palette
 * buttons rather than drag-and-drop — dragging exercises @xyflow's internals, clicking exercises
 * ours, and ours is what can regress here.
 */
test.describe.configure({ mode: 'serial' })

const NAME = 'E2E studio flow'

test('palette adds nodes to the canvas', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill(NAME)

  const nodes = page.locator('.react-flow__node')
  await expect(nodes).toHaveCount(0)

  // The icon is part of each button's accessible name ("◆ Agent"), and a bare "Agent" with
  // exact matching therefore matches nothing.
  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  await expect(nodes).toHaveCount(1)
  await page.getByRole('button', { name: '★ Coordinator' }).click()
  await expect(nodes).toHaveCount(2)
  // A flow has one lead: the button that added it is now off, and clicking it adds nothing.
  await expect(page.getByRole('button', { name: '★ Coordinator' })).toBeDisabled()
  await page.getByRole('button', { name: '◆ Agent' }).click()
  await expect(nodes).toHaveCount(3)
  await page.getByRole('button', { name: '⚙ MCP server' }).click()
  await expect(nodes).toHaveCount(4)

  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await goTo(page, 'Flows')
  await expect(flowCard(page, NAME)).toHaveCount(1)
})

test('a click selects a node, a double-click opens it; the canvas survives a reload', async ({ page }) => {
  await openApp(page)
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()

  // Saved and reloaded through the API: the four nodes came back — the lead as a lead.
  const nodes = page.locator('.react-flow__node')
  await expect(nodes).toHaveCount(4)
  await expect(page.getByRole('button', { name: '★ Coordinator' })).toBeDisabled()

  // One click selects and nothing more: the properties panel that used to sit beside the canvas
  // is gone, and a single click must not throw a dialog over the flow somebody is reading.
  await nodes.first().click()
  await expect(nodes.first()).toHaveClass(/selected/)
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // Two clicks open the block, with room to edit a prompt in.
  await nodes.first().dblclick()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // The runs panel sits under the canvas, folded until this installation has run something —
  // its whole content before that is a sentence saying so, and the canvas wants the room.
  await expect(page.getByRole('button', { name: 'Show the executions panel' })).toBeVisible()
})

test('the pre-run check reports on the saved flow', async ({ page }) => {
  await openApp(page)
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()

  await page.getByRole('button', { name: '⚕ Check' }).click()

  // Against the real backend: whether this machine has a signed-in CLI is not something to assert
  // — what must hold is that the check RAN and answered, either all-clear or with findings that
  // each name a fix.
  const dialog = page.getByRole('dialog')
  await expect(
    dialog.getByText(/Nothing to fix/).or(dialog.getByRole('list', { name: 'Findings' })),
  ).toBeVisible({ timeout: 20_000 })
})

test('every studio panel folds away and comes back', async ({ page }) => {
  await openApp(page)
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()
  await expect(page.getByRole('heading', { name: 'Add node' })).toBeVisible()

  // Palette → a slim rail naming what it will bring back.
  await page.getByRole('button', { name: 'Hide the node palette' }).click()
  await expect(page.getByRole('heading', { name: 'Add node' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Show the node palette' }).click()
  await expect(page.getByRole('heading', { name: 'Add node' })).toBeVisible()

  // No inspector rail on the right any more: a block's properties are a dialog, opened by
  // double-clicking it, so there is no panel there to fold.
  await expect(page.getByRole('button', { name: /the node properties/ })).toHaveCount(0)

  // Executions (console) → a bottom bar. It starts folded on an installation with no runs, so
  // this one unfolds first and folds after.
  await page.getByRole('button', { name: 'Show the executions panel' }).click()
  await expect(page.getByText(/No executions/)).toBeVisible()
  await page.getByRole('button', { name: 'Hide the executions panel' }).click()
  await expect(page.getByText(/No executions/)).toHaveCount(0)
})

test('cleans up its flow', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (dialog) => void dialog.accept())
  await cardAction(page, NAME, 'Delete')
  await expect(flowCard(page, NAME)).toHaveCount(0)
})
