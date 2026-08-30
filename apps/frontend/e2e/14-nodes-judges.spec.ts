import { expect, test } from './fixtures'
import {
  addNode, apiPost, closeInspector, drag, edges, field, fineTuning, handle, hideOutput, newFlow, nodesOf, openInspector,
  part, refuse, removeAllEdges, reopenFlow, saveFlow, savedFlow, savedNode, select, showOutput, stamp,
} from './nodes'

/**
 * The two extra processes of an independent-workers flow: the verifier that judges every worker
 * and the merge that speaks last. Each is a consumer — capabilities feed it — with its own model,
 * instructions and strictness, and its own second outputs.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('merge: model, instructions, effort, retries and facade persist; it takes an on-error output', async ({ page }) => {
  const NAME = 'E2E nodes · merge'
  await newFlow(page, NAME)
  const facade = await apiPost<{ id: string }>(page, '/api/facade-profiles', {
    name: `E2E dry-run ${stamp()}`, description: '', tools: [], readOnly: false, dryRun: true, readAlso: [],
  })
  await addNode(page, 'coordinator')
  const merge = await addNode(page, 'merge')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(merge, 'icon')).toHaveText('⛙')
  await expect(part(merge, 'title')).toHaveText('Merge')
  await expect(part(merge, 'badge')).toHaveText('MERGE')
  await expect(part(merge, 'snippet')).toHaveText('claude-opus-4-8')

  const dialog = await openInspector(page, merge)
  await field(dialog, 'Name').fill('Reconcile')
  await select(dialog, 'Model').selectOption('claude-opus-4-7')
  await field(dialog, 'Merge instructions ⓘ').fill('Run the tests before accepting any claim.')
  await expect(part(merge, 'title')).toHaveText('Reconcile')
  await expect(part(merge, 'snippet')).toHaveText('claude-opus-4-7')
  await fineTuning(dialog)
  await select(dialog, 'Effort').selectOption('medium')
  await field(dialog, 'Max tokens').fill('9000')
  await field(dialog, /^Retries after a failure/).fill('1')
  await select(dialog, 'Facade profile ⓘ').selectOption(facade.id)
  await closeInspector(page)
  await showOutput(merge, 'on error')

  // A server feeds the merge (the palette drew it); the merge feeds no server.
  const mcp = await addNode(page, 'mcp')
  await expect(edges(page)).toHaveCount(2)
  await refuse(page, handle(merge, 'source'), handle(mcp, 'target'))

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const block = nodesOf(page, 'merge').first()
  await expect(handle(block, 'error')).toHaveCount(1)
  const again = await openInspector(page, block)
  await expect(field(again, 'Name')).toHaveValue('Reconcile')
  await expect(select(again, 'Model')).toHaveValue('claude-opus-4-7')
  await expect(field(again, 'Merge instructions ⓘ')).toHaveValue('Run the tests before accepting any claim.')
  await fineTuning(again)
  await expect(select(again, 'Effort')).toHaveValue('medium')
  await expect(field(again, 'Max tokens')).toHaveValue('9000')
  await expect(field(again, /^Retries after a failure/)).toHaveValue('1')
  await expect(select(again, 'Facade profile ⓘ')).toHaveValue(facade.id)
  const saved = await savedFlow(page, NAME)
  expect(savedNode(saved, 'merge').data).toMatchObject({
    name: 'Reconcile', model: 'claude-opus-4-7', systemPrompt: 'Run the tests before accepting any claim.', effort: 'medium',
    maxTokens: 9000, retries: 1, facadeProfileId: facade.id, errorOutput: true,
  })
  expect(saved.edges).toEqual(expect.arrayContaining([
    expect.objectContaining({ source: savedNode(saved, 'mcp').id, target: savedNode(saved, 'merge').id }),
  ]))
})

test('verifier: criteria and strictness persist; on rejected and on error are separate outputs', async ({ page }) => {
  const NAME = 'E2E nodes · verifier'
  await newFlow(page, NAME)
  const coord = await addNode(page, 'coordinator')
  const verifier = await addNode(page, 'verifier')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(verifier, 'icon')).toHaveText('⚖')
  await expect(part(verifier, 'title')).toHaveText('Verifier')
  await expect(part(verifier, 'badge')).toHaveText('VERIFY')
  await expect(part(verifier, 'snippet')).toHaveText('claude-opus-4-8')

  const dialog = await openInspector(page, verifier)
  await field(dialog, 'Name').fill('Judge')
  await select(dialog, 'Model').selectOption('__custom__')
  await field(dialog, 'Model id').fill('claude-opus-4-9')
  await field(dialog, 'Rejection criteria ⓘ').fill('Reject numbers that appear in no worker file.')
  await expect(part(verifier, 'title')).toHaveText('Judge')
  await expect(part(verifier, 'snippet')).toHaveText('claude-opus-4-9')
  await fineTuning(dialog)
  await select(dialog, 'Effort').selectOption('xhigh')
  await field(dialog, 'Max tokens').fill('3000')
  await field(dialog, /^Retries after a failure/).fill('0')
  await closeInspector(page)

  // Two second outputs, each its own fact: the rejection is the verifier working, the error is it breaking.
  await showOutput(verifier, 'on rejected')
  await showOutput(verifier, 'on error')
  await hideOutput(verifier, 'on error')

  // The report goes out of "on rejected"; once wired, the label no longer puts the output away.
  const mail = await addNode(page, 'mail')
  await expect(edges(page)).toHaveCount(2)
  await removeAllEdges(page)
  await drag(page, handle(verifier, 'rejected'), handle(mail, 'target'))
  await expect(edges(page)).toHaveCount(1)
  await expect(part(mail, 'badge')).toHaveText('ON REJECTED')
  await expect(mail).toContainText('sends the verification report')
  await expect(verifier.getByRole('button', { name: 'on rejected', exact: true })).toHaveCount(0)
  await drag(page, handle(coord, 'source'), handle(verifier, 'target'))
  await expect(edges(page)).toHaveCount(2)
  await refuse(page, handle(mail, 'target'), handle(verifier, 'target'))

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const judge = nodesOf(page, 'verifier').first()
  await expect(handle(judge, 'rejected')).toHaveCount(1)
  await expect(handle(judge, 'error')).toHaveCount(0)
  const again = await openInspector(page, judge)
  await expect(field(again, 'Name')).toHaveValue('Judge')
  await expect(field(again, 'Model id')).toHaveValue('claude-opus-4-9')
  await expect(field(again, 'Rejection criteria ⓘ')).toHaveValue('Reject numbers that appear in no worker file.')
  await fineTuning(again)
  await expect(select(again, 'Effort')).toHaveValue('xhigh')
  await expect(field(again, 'Max tokens')).toHaveValue('3000')
  await expect(field(again, /^Retries after a failure/)).toHaveValue('0')
  const saved = await savedFlow(page, NAME)
  const node = savedNode(saved, 'verifier')
  expect(node.data).toMatchObject({
    name: 'Judge', model: 'claude-opus-4-9', systemPrompt: 'Reject numbers that appear in no worker file.', effort: 'xhigh',
    maxTokens: 3000, retries: 0, rejectedOutput: true, errorOutput: false,
  })
  expect(saved.edges).toEqual(expect.arrayContaining([
    expect.objectContaining({ source: node.id, target: savedNode(saved, 'mail').id, sourceHandle: 'rejected' }),
    expect.objectContaining({ source: savedNode(saved, 'agent', 'coordinator').id, target: node.id }),
  ]))
})
