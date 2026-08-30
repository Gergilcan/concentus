import { expect, test } from './fixtures'
import {
  addNode, closeInspector, createCredential, drag, edges, field, handle, newFlow, nodesOf, openInspector, part, refuse,
  removeAllEdges, reopenFlow, saveFlow, savedFlow, savedNode, select, showOutput, stamp,
} from './nodes'

/**
 * The Send mail block: only ever a target, with no source handle to draw from. Its card reads
 * WHICH output it hangs off — the final answer, a block's failure — from the wire, not a field.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('send mail: recipients, SMTP account and credential persist; it hangs off an output and feeds nothing', async ({ page }) => {
  const NAME = 'E2E nodes · mail'
  await newFlow(page, NAME)
  const cred = await createCredential(page, `E2E mailbox password ${stamp()}`)
  const coord = await addNode(page, 'coordinator')
  const mail = await addNode(page, 'mail')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(mail, 'icon')).toHaveText('✉')
  await expect(part(mail, 'title')).toHaveText('mail')
  await expect(part(mail, 'badge')).toHaveText('AFTER')
  await expect(part(mail, 'snippet')).toHaveText('no recipient yet · {{flow}}: {{status}}')
  await expect(mail).toContainText("sends the run's final answer")
  await expect(mail.locator('.react-flow__handle.source')).toHaveCount(0)
  await expect(handle(mail, 'target')).toHaveCount(1)

  const dialog = await openInspector(page, mail)
  await field(dialog, 'Label').fill('notify')
  await field(dialog, 'To ⓘ').fill('ops@example.test, gerard@example.test')
  await field(dialog, 'Subject ⓘ').fill('Done: {{flow}}')
  await field(dialog, 'SMTP host ⓘ').fill('smtp.example.test')
  const port = field(dialog, 'Port')
  await expect(port).toHaveValue('587')
  await field(dialog, 'Use STARTTLS (port 587) ⓘ').uncheck()
  await expect(port).toHaveValue('465')
  await field(dialog, 'Use STARTTLS (port 587) ⓘ').check()
  await expect(port).toHaveValue('587')
  await port.fill('2525')
  await field(dialog, 'From address ⓘ').fill('bot@example.test')
  await field(dialog, 'Username ⓘ').fill('bot')
  await select(dialog, 'Mailbox password').selectOption(cred)
  await expect(part(mail, 'title')).toHaveText('notify')
  await expect(part(mail, 'snippet')).toHaveText('ops@example.test, gerard@example.test · Done: {{flow}}')
  await closeInspector(page)

  // Off the wire it says so; off a block's error output it sends that block's failure.
  await removeAllEdges(page)
  await expect(part(mail, 'badge')).toHaveText('—')
  await expect(mail).toContainText('not wired to a block — it will not send')
  await showOutput(coord, 'on error')
  await drag(page, handle(coord, 'error'), handle(mail, 'target'))
  await expect(edges(page)).toHaveCount(1)
  await expect(part(mail, 'badge')).toHaveText('ON ERROR')
  await expect(mail).toContainText("sends that block's failure and log")
  // A capability has nothing to mail: the wire is refused.
  const kb = await addNode(page, 'knowledge')
  await expect(edges(page)).toHaveCount(2)
  await refuse(page, handle(kb, 'source'), handle(mail, 'target'))

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const block = nodesOf(page, 'mail').first()
  await expect(part(block, 'badge')).toHaveText('ON ERROR')
  const again = await openInspector(page, block)
  await expect(field(again, 'Label')).toHaveValue('notify')
  await expect(field(again, 'To ⓘ')).toHaveValue('ops@example.test, gerard@example.test')
  await expect(field(again, 'Subject ⓘ')).toHaveValue('Done: {{flow}}')
  await expect(field(again, 'SMTP host ⓘ')).toHaveValue('smtp.example.test')
  await expect(field(again, 'Port')).toHaveValue('2525')
  await expect(field(again, 'Use STARTTLS (port 587) ⓘ')).toBeChecked()
  await expect(field(again, 'From address ⓘ')).toHaveValue('bot@example.test')
  await expect(field(again, 'Username ⓘ')).toHaveValue('bot')
  await expect(select(again, 'Mailbox password')).toHaveValue(cred)
  const saved = await savedFlow(page, NAME)
  const node = savedNode(saved, 'mail')
  expect(node.data).toMatchObject({
    label: 'notify', to: 'ops@example.test, gerard@example.test', subject: 'Done: {{flow}}', smtpHost: 'smtp.example.test',
    smtpPort: 2525, smtpStarttls: true, from: 'bot@example.test', smtpUsername: 'bot', credentialId: cred,
  })
  expect(saved.edges).toEqual(expect.arrayContaining([
    expect.objectContaining({ source: savedNode(saved, 'agent', 'coordinator').id, target: node.id, sourceHandle: 'error' }),
  ]))
})
