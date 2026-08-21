import { expect, openApp, test } from './fixtures'

/**
 * The schedule builder: cron for people who don't speak cron. The builder edits presets and the
 * summary line shows the expression it writes — which is also what these tests read, because it
 * is the value the flow actually saves.
 */
test('a schedule builds from natural choices, and custom cron still exists', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill('E2E cron flow')

  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  await page.locator('.react-flow__node').first().dblclick()
  await page.getByLabel('Execution type').selectOption('cron')

  // The builder recognises the palette's default (0 9 * * *) and the canvas node says it in
  // words. Node-scoped: the builder's summary line repeats the same text.
  await expect(page.getByLabel(/^Schedule/)).toBeVisible()
  await expect(page.locator('.react-flow__node').getByText('Every day at 09:00')).toBeVisible()

  // "Every 15 minutes on working days" — Gerard's own example, built with two controls. The raw
  // expression appears only in the summary line; the words appear on the canvas card too.
  await page.getByLabel(/^Schedule/).selectOption('minutes')
  await page.getByLabel('Every how many minutes').fill('15')
  await page.getByLabel(/^On which days/).selectOption('weekdays')
  await expect(page.getByText('*/15 * * * 1-5')).toBeVisible()
  await expect(page.locator('.react-flow__node').getByText('Every 15 minutes on working days (Mon–Fri)')).toBeVisible()

  // Weekly: pick days with the chips.
  await page.getByLabel(/^Schedule/).selectOption('weekly')
  await page.getByRole('button', { name: 'Thu', exact: true }).click()
  await expect(page.getByText('0 9 * * 1,4')).toBeVisible()
  await expect(page.locator('.react-flow__node').getByText('Every Monday and Thursday at 09:00')).toBeVisible()

  // Custom keeps raw cron editable for the literate.
  await page.getByLabel(/^Schedule/).selectOption('custom')
  await page.getByRole('textbox', { name: 'Cron expression' }).fill('0 0 29 2 *')
  await expect(page.getByText('used as written', { exact: false })).toBeVisible()

  // An existing expression is recognised when the flow is saved and reopened from scratch.
  await page.getByRole('textbox', { name: 'Cron expression' }).fill('0 7 * * 1-5')
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('button', { name: '← Flows' }).click()
  await page
    .getByRole('article')
    .filter({ hasText: 'E2E cron flow' })
    .getByRole('button', { name: 'Open' })
    .click()
  await page.locator('.react-flow__node').first().dblclick()
  await expect(page.getByLabel(/^Schedule/)).toHaveValue('daily')
  await expect(page.getByLabel('At', { exact: true })).toHaveValue('07:00')
  await expect(page.getByLabel(/^On which days/)).toHaveValue('weekdays')

  // Clean up through the dashboard, with the dialog out of the way first.
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
  await page.getByRole('button', { name: '← Flows' }).click()
  page.on('dialog', (d) => void d.accept())
  const card = page.getByRole('article').filter({ hasText: 'E2E cron flow' })
  await card.getByTitle('Delete').click()
  await expect(card).toHaveCount(0)
})
