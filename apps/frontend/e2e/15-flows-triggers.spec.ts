import { expect, flowCard, openApp, test } from './fixtures'
import {
  addBlock,
  apiWrite,
  backToFlows,
  closeInspector,
  deleteFlow,
  flowErrors,
  minimalFlow,
  openInspector,
  runDoctor,
  save,
  startNewFlow,
} from './flows-helpers'

/**
 * Every way a saved flow can start, on the real API: a schedule the card can pause and resume, a
 * webhook whose URL exists only once the flow is saved and whose published endpoint honours
 * exactly the token it shows, a mailbox whose IMAP settings survive a reload, and a flow started
 * by another one — which the doctor accepts once a flow is chosen.
 *
 * The cron BUILDER is 07's; here the schedule is only the default the palette gives.
 */

/** A flow with one trigger of the given mode and a coordinator, saved. Leaves the Studio open. */
async function flowWithTrigger(
  page: import('@playwright/test').Page,
  name: string,
  mode: string,
  configure?: (inspector: import('@playwright/test').Locator) => Promise<void>,
) {
  await startNewFlow(page, name)
  await addBlock(page, '▶ Input / trigger')
  const inspector = await openInspector(page, 'Manual')
  await inspector.getByLabel('Execution type').selectOption(mode)
  await configure?.(inspector)
  await closeInspector(page)
  await addBlock(page, '★ Coordinator')
  return save(page)
}

test('cron: the card wears the schedule, and pauses and resumes it', async ({ page }) => {
  const NAME = 'E2E cron trigger'
  await openApp(page)
  await flowWithTrigger(page, NAME, 'cron')
  await backToFlows(page)

  const card = flowCard(page, NAME)
  await expect(card.getByText('⏱ 0 9 * * *', { exact: true })).toBeVisible()
  await expect(card.getByText('paused', { exact: true })).toHaveCount(0)

  await card.getByTitle('Pause schedule').click()
  await expect(card.getByText('paused', { exact: true })).toBeVisible()
  await card.getByTitle('Resume schedule').click()
  await expect(card.getByText('paused', { exact: true })).toHaveCount(0)

  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, NAME)
})

test('webhook: the URL appears once saved; publishing mints a token the public chat page checks', async ({ page }) => {
  const NAME = 'E2E webhook trigger'
  await openApp(page)
  const saved = await flowWithTrigger(page, NAME, 'webhook', async (inspector) => {
    await inspector.getByLabel('Secret', { exact: true }).fill('e2e-hook-secret')
    // No id yet, so no URL yet — and the field says so instead of showing a URL that 404s.
    await expect(inspector.getByLabel('Webhook URL')).toHaveValue(/Save the flow first/)
  })

  const inspector = await openInspector(page, 'Webhook')
  await expect(inspector.getByLabel('Webhook URL')).toHaveValue(new RegExp(`/api/webhooks/${saved.id}$`))
  await expect(inspector.getByLabel('Secret', { exact: true })).toHaveValue('e2e-hook-secret')

  await inspector.getByLabel('Publish as an endpoint').check()
  const token = inspector.getByLabel('Endpoint token')
  await expect(token).not.toHaveValue('')
  const first = await token.inputValue()
  await inspector.getByRole('button', { name: 'Regenerate' }).click()
  await expect(token).not.toHaveValue(first)
  const current = await token.inputValue()
  await expect(inspector.getByLabel('Endpoint URL')).toHaveValue(new RegExp(`/api/public/flows/${saved.id}/run$`))
  await closeInspector(page)
  await save(page)

  // The demo chat page is the endpoint's door: open to the saved token, shut to everything else.
  const chat = (flowId: string, t?: string) =>
    page.request.get(`/api/public/flows/${flowId}/chat${t ? `?token=${encodeURIComponent(t)}` : ''}`)
  expect((await chat(saved.id, current)).status()).toBe(200)
  expect((await chat(saved.id)).status(), 'no token').toBe(401)
  expect((await chat(saved.id, first)).status(), 'the regenerated token is revoked by the save').toBe(401)
  expect((await chat('flow_that_does_not_exist', current)).status(), 'no such flow').toBe(404)

  await backToFlows(page)
  await expect(flowCard(page, NAME).getByText('⚡ Webhook', { exact: true })).toBeVisible()
  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, NAME)
})

test('mail: the IMAP settings persist and the card names the folder', async ({ page }) => {
  const NAME = 'E2E mail trigger'
  await openApp(page)
  await flowWithTrigger(page, NAME, 'mail', async (inspector) => {
    await inspector.getByLabel('IMAP host').fill('imap.example.test')
    await inspector.getByLabel('Port', { exact: true }).fill('993')
    await inspector.getByLabel('Username', { exact: true }).fill('e2e@example.test')
    await inspector.getByLabel('Folder to watch').fill('E2E-Inbox')
  })
  await backToFlows(page)

  const card = flowCard(page, NAME)
  await expect(card.getByText('✉ E2E-Inbox', { exact: true })).toBeVisible()
  // Reopened from the saved record, not from what the canvas remembers.
  await card.getByRole('button', { name: 'Open' }).click()
  const inspector = await openInspector(page, 'Mail (IMAP)')
  await expect(inspector.getByLabel('IMAP host')).toHaveValue('imap.example.test')
  await expect(inspector.getByLabel('Port', { exact: true })).toHaveValue('993')
  await expect(inspector.getByLabel('Username', { exact: true })).toHaveValue('e2e@example.test')
  await expect(inspector.getByLabel('Folder to watch')).toHaveValue('E2E-Inbox')
  await closeInspector(page)
  await backToFlows(page)

  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, NAME)
})

test('sub-flow: a Run-another-flow block pointing at a saved flow passes the doctor', async ({ page }) => {
  const PARENT = 'E2E parent flow'
  const CHILD = 'E2E child flow'
  await openApp(page)
  const child = await apiWrite(page, 'POST', '/api/flows', minimalFlow(CHILD))

  await startNewFlow(page, PARENT)
  await addBlock(page, '▶ Input / trigger')
  await addBlock(page, '★ Coordinator')
  await addBlock(page, '🔗 Run another flow')
  const inspector = await openInspector(page, '🔗')
  await inspector.getByLabel('Flow to run').selectOption({ label: CHILD })
  await closeInspector(page)
  await save(page)

  const doctor = await runDoctor(page)
  expect(flowErrors(doctor), 'the graph compiles once the block names a flow').toEqual([])

  await backToFlows(page)
  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, PARENT)
  await apiWrite(page, 'DELETE', `/api/flows/${child.id}`)
})
