import { expect, flowCard, goTo, openApp, test, cardAction } from './fixtures'

/**
 * A flow's whole life through the real UI and the real API: created in the Studio, named, saved,
 * visible on the dashboard, duplicated, renamed, and deleted — with the browser-native confirm
 * dialog answered like a user would.
 *
 * Serial by design: each test continues from the state the previous one left, because that chain
 * — create, then find it, then duplicate it, then delete it — IS the scenario.
 */
test.describe.configure({ mode: 'serial' })

const NAME = 'E2E flow'

test('creates a flow in the Studio and finds it on the dashboard', async ({ page }) => {
  await openApp(page)
  // first(): a truly empty dashboard renders a second "+ New flow" inside the empty-state card.
  await page.getByRole('button', { name: '+ New flow' }).first().click()

  // The Studio opens on a fresh, unsaved flow.
  await expect(page.getByLabel('Flow name')).toHaveValue('Untitled flow')
  await page.getByLabel('Flow name').fill(NAME)
  await page.getByRole('button', { name: 'Save', exact: true }).click()

  await page.getByRole('button', { name: '← Flows' }).click()
  await expect(flowCard(page, NAME)).toHaveCount(1)
})

test('duplicates the flow', async ({ page }) => {
  await openApp(page)
  // exact: "Duplicate as sandbox" is a second button on the same card, and title matching is
  // substring-based.
  await cardAction(page, NAME, { name: 'Duplicate', exact: true })
  await expect(flowCard(page, `${NAME} (copy)`)).toHaveCount(1)
})

test('makes a sandbox copy that is paused, tagged, and in plan mode', async ({ page }) => {
  // Its own flow, with a real coordinator: the shared one in this file is deliberately empty, and
  // plan mode has to land on an agent to be worth asserting.
  const SANDBOXED = 'E2E sandbox source'
  await openApp(page)
  page.on('dialog', (dialog) => {
    // The confirmation must say what is NOT simulated — a sandbox that hides a gap buys
    // confidence it has not earned.
    if (dialog.message().includes('sandbox copy')) expect(dialog.message()).toContain('NOT covered')
    void dialog.accept()
  })

  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill(SANDBOXED)
  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  await page.getByRole('button', { name: '◆ Agent' }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('button', { name: '← Flows' }).click()

  await cardAction(page, SANDBOXED, 'Duplicate as sandbox')

  const sandbox = flowCard(page, `${SANDBOXED} (sandbox)`)
  await expect(sandbox).toHaveCount(1)
  // The tag chip, not the name — both say "sandbox" on this card.
  await expect(sandbox.getByRole('button', { name: 'sandbox', exact: true })).toBeVisible()

  // Saved through the real API: the copy really carries plan mode on its coordinator, which is
  // the value the backend actually honours.
  await sandbox.getByRole('button', { name: 'Open' }).click()
  // Double-click: a block's properties open in a dialog now, not in a panel beside the canvas.
  await page.locator('.react-flow__node').filter({ hasText: 'Coordinator' }).first().dblclick()
  await page.getByText('Fine-tuning').click()
  await expect(page.getByLabel(/Permissions for this flow/)).toHaveValue('plan')

  // The properties are a dialog now, and a dialog covers the toolbar behind it.
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
  await page.getByRole('button', { name: '← Flows' }).click()
  for (const name of [`${SANDBOXED} (sandbox)`, SANDBOXED]) {
    await cardAction(page, name, 'Delete')
    await expect(flowCard(page, name)).toHaveCount(0)
  }
})

test('opens a flow from its card and renames it', async ({ page }) => {
  await openApp(page)
  await flowCard(page, `${NAME} (copy)`).getByRole('button', { name: 'Open' }).click()

  await expect(page.getByLabel('Flow name')).toHaveValue(`${NAME} (copy)`)
  await page.getByLabel('Flow name').fill(`${NAME} renamed`)
  await page.getByRole('button', { name: 'Save', exact: true }).click()

  await goTo(page, 'Flows')
  await expect(flowCard(page, `${NAME} renamed`)).toHaveCount(1)
  await expect(flowCard(page, `${NAME} (copy)`)).toHaveCount(0)
})

test('deletes both flows, confirming the dialog', async ({ page }) => {
  await openApp(page)
  page.on('dialog', (dialog) => void dialog.accept())

  for (const name of [`${NAME} renamed`, NAME]) {
    await cardAction(page, name, 'Delete')
    await expect(flowCard(page, name)).toHaveCount(0)
  }
})
