import { expect, flowCard, goTo, openApp, test } from './fixtures'

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
  await flowCard(page, NAME).getByTitle('Duplicate').click()
  await expect(flowCard(page, `${NAME} (copy)`)).toHaveCount(1)
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
    await flowCard(page, name).getByTitle('Delete').click()
    await expect(flowCard(page, name)).toHaveCount(0)
  }
})
