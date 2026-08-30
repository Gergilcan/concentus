import { expect, test } from './fixtures'
import {
  addNode, closeInspector, createCredential, edges, field, fineTuning, handle, hideOutput, newFlow, nodesOf,
  openInspector, PALETTE, part, reopenFlow, saveFlow, savedFlow, savedNode, select, showOutput, stamp,
} from './nodes'

/**
 * The two blocks a run starts from: the trigger, and the coordinator it addresses. Every field of
 * each inspector is exercised against the real backend and read back after a save and a reopen —
 * the card, the dialog and the stored JSON all have to agree.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('input: every trigger mode shows its own fields, and the chosen one persists', async ({ page }) => {
  const NAME = 'E2E nodes · input'
  await newFlow(page, NAME)
  const cred = await createCredential(page, `E2E mailbox ${stamp()}`)
  const input = await addNode(page, 'input')
  await expect(part(input, 'icon')).toHaveText('▶')
  await expect(part(input, 'title')).toHaveText('Input')
  await expect(part(input, 'badge')).toHaveText('Manual')
  await expect(input).toContainText('you type the first message')
  // A trigger is where the run starts: nothing can be wired INTO it.
  await expect(handle(input, 'target')).toHaveCount(0)

  const dialog = await openInspector(page, input)
  const mode = select(dialog, 'Execution type')
  await expect(dialog.getByText('The run starts idle')).toBeVisible()

  await mode.selectOption('prompt')
  await field(dialog, 'Execution prompt').fill('Build the login page')
  await expect(part(input, 'badge')).toHaveText('Prompt')
  await expect(input).toContainText('Build the login page')

  // The builder itself is 07's; here only that cron brings it, and the card says the schedule.
  await mode.selectOption('cron')
  await expect(field(dialog, /^Schedule/)).toBeVisible()
  await expect(part(input, 'badge')).toHaveText('Automatic (cron)')
  await expect(input).toContainText('Every day at 09:00')

  await mode.selectOption('webhook')
  await expect(part(input, 'badge')).toHaveText('Webhook')
  await select(dialog, 'Provider').selectOption('github')
  await expect(field(dialog, 'Validation parameter')).toHaveValue('X-Hub-Signature-256')
  await select(dialog, 'Provider').selectOption('custom')
  await field(dialog, 'Validation parameter').fill('token')
  await field(dialog, 'Secret').fill('shh-e2e')
  await expect(field(dialog, 'Webhook URL')).toHaveValue(/Save the flow first/)
  await field(dialog, /^Shadow mode/).check()

  await mode.selectOption('watch')
  await expect(part(input, 'badge')).toHaveText('Watch')
  await field(dialog, 'Folder to watch').fill('C:\\drop\\incoming')
  await field(dialog, 'Files that count').fill('*.pdf')
  await field(dialog, 'Quiet time before a run (seconds)').fill('9')
  await expect(part(input, 'meta')).toHaveText('C:\\drop\\incoming · *.pdf')

  await mode.selectOption('subflow')
  await expect(part(input, 'badge')).toHaveText('Another flow')
  await expect(dialog.getByText(/^Started by another flow/)).toBeVisible()

  await mode.selectOption('mail')
  await expect(part(input, 'badge')).toHaveText('Mail (IMAP)')
  await field(dialog, 'IMAP host').fill('imap.example.test')
  await expect(field(dialog, 'Port')).toHaveValue('993')
  await field(dialog, 'Use TLS (IMAPS)').uncheck()
  await expect(field(dialog, 'Port')).toHaveValue('143')
  await field(dialog, 'Username').fill('presupuestos@example.test')
  await select(dialog, 'Authentication').selectOption('microsoft-oauth')
  await expect(dialog.getByRole('button', { name: 'Connect Microsoft account' })).toBeVisible()
  await select(dialog, 'Authentication').selectOption('password')
  await select(dialog, 'Password').selectOption(cred)
  await field(dialog, 'Folder to watch').fill('Presupuestos')
  await expect(part(input, 'meta')).toHaveText('Presupuestos')
  await fineTuning(dialog)
  await field(dialog, 'From contains').fill('@cliente.com')
  await field(dialog, 'Subject contains').fill('presupuesto')
  await field(dialog, 'Unread only').uncheck()
  await field(dialog, 'Flagged only').check()
  await field(dialog, 'Poll every (seconds)').fill('120')
  await field(dialog, 'Move to folder').fill('Presupuestos/Procesados')
  await field(dialog, 'Mark as read').uncheck()
  await field(dialog, 'Flag it').check()

  // Any mode: publishing adds a door. The token is minted in the browser and revoked by minting another.
  await field(dialog, /^Publish as an endpoint/).check()
  const token = await field(dialog, 'Endpoint token').inputValue()
  expect(token.length).toBeGreaterThan(10)
  await dialog.getByRole('button', { name: 'Regenerate' }).click()
  const regenerated = await field(dialog, 'Endpoint token').inputValue()
  expect(regenerated).not.toBe(token)
  await closeInspector(page)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'input').first())
  await expect(select(again, 'Execution type')).toHaveValue('mail')
  await expect(field(again, 'IMAP host')).toHaveValue('imap.example.test')
  await expect(field(again, 'Port')).toHaveValue('143')
  await expect(field(again, 'Use TLS (IMAPS)')).not.toBeChecked()
  await expect(field(again, 'Username')).toHaveValue('presupuestos@example.test')
  await expect(select(again, 'Password')).toHaveValue(cred)
  await expect(field(again, 'Folder to watch')).toHaveValue('Presupuestos')
  await fineTuning(again)
  await expect(field(again, 'Flagged only')).toBeChecked()
  await expect(field(again, 'Poll every (seconds)')).toHaveValue('120')
  await expect(field(again, /^Publish as an endpoint/)).toBeChecked()
  await expect(field(again, 'Endpoint token')).toHaveValue(regenerated)
  await expect(field(again, 'Endpoint URL')).toHaveValue(/\/api\/public\/flows\//)

  const saved = savedNode(await savedFlow(page, NAME), 'input')
  expect(saved.data).toMatchObject({
    mode: 'mail', prompt: 'Build the login page', secret: 'shh-e2e', authParam: 'token', shadow: true,
    watchPath: 'C:\\drop\\incoming', watchGlob: '*.pdf', watchDebounceSeconds: 9,
    mailHost: 'imap.example.test', mailPort: 143, mailSsl: false, mailUsername: 'presupuestos@example.test',
    mailAuthMode: 'password', mailCredentialId: cred, mailFolder: 'Presupuestos', mailFrom: '@cliente.com',
    mailSubjectContains: 'presupuesto', mailUnseenOnly: false, mailFlaggedOnly: true, mailPollSeconds: 120,
    mailMoveToFolder: 'Presupuestos/Procesados', mailMarkSeen: false, mailFlagAfter: true,
    published: true, publishToken: regenerated,
  })
  expect(saved.data._pos).toEqual({ x: expect.any(Number), y: expect.any(Number) })
})

test('coordinator: one per flow, its run-wide settings persist, and its on-error output toggles', async ({ page }) => {
  const NAME = 'E2E nodes · coordinator'
  await newFlow(page, NAME)
  await addNode(page, 'input')
  const coord = await addNode(page, 'coordinator')
  // The palette wired the trigger to the lead it just added, and now refuses a second lead.
  await expect(edges(page)).toHaveCount(1)
  const button = page.getByRole('button', { name: PALETTE.coordinator })
  await expect(button).toBeDisabled()
  await expect(button).toHaveAttribute('title', 'A flow has one coordinator, and this one already does.')
  await expect(part(coord, 'icon')).toHaveText('★')
  await expect(part(coord, 'title')).toHaveText('Coordinator')
  await expect(part(coord, 'badge')).toHaveText('coordinator')
  await expect(coord).toContainText('claude-opus-4-8')
  await expect(coord).toContainText('no system prompt')

  const dialog = await openInspector(page, coord)
  await field(dialog, 'Name').fill('Lead')
  await select(dialog, 'Model').selectOption('claude-sonnet-5')
  await expect(part(coord, 'title')).toHaveText('Lead')
  await expect(coord).toContainText('claude-sonnet-5')
  // The lead is addressed, never delegated to: no routing text, and no per-worker settings.
  await expect(field(dialog, 'Delegate when… (routing)')).toHaveCount(0)
  await field(dialog, 'System prompt').fill('Plan first, then delegate.')
  await expect(coord).toContainText('Plan first, then delegate.')
  await fineTuning(dialog)
  await expect(field(dialog, /^Allowed tools/)).toHaveCount(0)
  await select(dialog, 'Effort').selectOption('max')
  await field(dialog, 'Max tokens').fill('4000')
  await select(dialog, "Permissions for this flow's agents ⓘ").selectOption('acceptEdits')
  const execution = select(dialog, 'Execution ⓘ')
  await expect(execution).toHaveValue('fanout')
  await execution.selectOption('')
  await expect(select(dialog, 'Coordinator access ⓘ')).toHaveCount(0)
  await execution.selectOption('fanout')
  await select(dialog, 'Coordinator access ⓘ').selectOption('may-act')
  await select(dialog, 'When the weekly allowance is spent ⓘ').selectOption('local-model')
  await select(dialog, 'Fallback model').selectOption('claude-haiku-4-5')
  await field(dialog, /^Context folders/).fill('C:\\code\\a\nC:\\code\\b')
  await field(dialog, /^CLAUDE\.md path/).fill('C:\\code\\a\\CLAUDE.md')
  await closeInspector(page)

  // The second output: shown by its chip, put away by its label, shown again to be saved.
  await showOutput(coord, 'on error')
  await hideOutput(coord, 'on error')
  await showOutput(coord, 'on error')

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const lead = nodesOf(page, 'coordinator').first()
  await expect(part(lead, 'title')).toHaveText('Lead')
  await expect(handle(lead, 'error')).toHaveCount(1)
  await expect(page.getByRole('button', { name: PALETTE.coordinator })).toBeDisabled()
  const again = await openInspector(page, lead)
  await expect(field(again, 'Name')).toHaveValue('Lead')
  await expect(select(again, 'Model')).toHaveValue('claude-sonnet-5')
  await expect(field(again, 'System prompt')).toHaveValue('Plan first, then delegate.')
  await fineTuning(again)
  await expect(select(again, 'Effort')).toHaveValue('max')
  await expect(field(again, 'Max tokens')).toHaveValue('4000')
  await expect(select(again, "Permissions for this flow's agents ⓘ")).toHaveValue('acceptEdits')
  await expect(select(again, 'Coordinator access ⓘ')).toHaveValue('may-act')
  await expect(select(again, 'Fallback model')).toHaveValue('claude-haiku-4-5')
  await expect(field(again, /^Context folders/)).toHaveValue('C:\\code\\a\nC:\\code\\b')

  const saved = await savedFlow(page, NAME)
  const node = savedNode(saved, 'agent', 'coordinator')
  expect(node.data).toMatchObject({
    role: 'coordinator', name: 'Lead', model: 'claude-sonnet-5', systemPrompt: 'Plan first, then delegate.',
    effort: 'max', maxTokens: 4000, permissionMode: 'acceptEdits', execution: 'fanout', coordinatorAccess: 'may-act',
    allowanceFallback: 'local-model', allowanceFallbackModel: 'claude-haiku-4-5',
    contextFolders: ['C:\\code\\a', 'C:\\code\\b'], claudeMdPath: 'C:\\code\\a\\CLAUDE.md', errorOutput: true,
  })
  expect(saved.edges).toEqual([expect.objectContaining({ source: savedNode(saved, 'input').id, target: node.id })])
})
