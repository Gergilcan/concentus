import { expect, test } from './fixtures'
import {
  addNode, apiPost, closeInspector, drag, edges, field, handle, newFlow, nodesOf, openInspector, part, refuse,
  removeAllEdges, reopenFlow, saveFlow, savedFlow, savedNode, select, stamp,
} from './nodes'

/**
 * What stands between an agent and what it hands off to: a condition, a for-each, another flow.
 * Gates read on their cards, chain among themselves, and take wires only from consumers and
 * other gates — a capability pointed at a gate is refused.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('condition: every test shape reads on the card, the else output is always there', async ({ page }) => {
  const NAME = 'E2E nodes · condition'
  await newFlow(page, NAME)
  const coord = await addNode(page, 'coordinator')
  const cond = await addNode(page, 'condition')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(cond, 'icon')).toHaveText('⑂')
  await expect(part(cond, 'title')).toHaveText('if')
  await expect(part(cond, 'badge')).toHaveText('IF')
  await expect(part(cond, 'snippet')).toHaveText('answered anything')
  // Not optional: a gate with one visible branch is half a gate, so there is no chip to show it.
  await expect(handle(cond, 'else')).toHaveCount(1)
  await expect(cond.getByText('else', { exact: true })).toBeVisible()
  await expect(cond.getByRole('button')).toHaveCount(0)

  const dialog = await openInspector(page, cond)
  await field(dialog, 'Label').fill('has deadline')
  await expect(part(cond, 'title')).toHaveText('has deadline')
  const shape = select(dialog, 'Run the branch when the answer ⓘ')
  await expect(field(dialog, 'Text')).toHaveCount(0)
  await shape.selectOption('contains')
  await field(dialog, 'Text').fill('deadline')
  await expect(part(cond, 'snippet')).toHaveText('contains “deadline”')
  await field(dialog, 'Case sensitive').check()
  await shape.selectOption('not_contains')
  await expect(part(cond, 'snippet')).toHaveText('does not contain “deadline”')
  await shape.selectOption('equals')
  await expect(part(cond, 'snippet')).toHaveText('is exactly “deadline”')
  await shape.selectOption('matches')
  await field(dialog, 'Regular expression').fill('dead(line)?')
  await expect(part(cond, 'snippet')).toHaveText('matches “dead(line)?”')
  await expect(dialog.getByText('A pattern that does not compile fails the gate rather than the run.')).toBeVisible()
  await shape.selectOption('not_empty')
  await expect(field(dialog, 'Text')).toHaveCount(0)
  await expect(part(cond, 'snippet')).toHaveText('answered anything')
  await shape.selectOption('matches')
  await closeInspector(page)

  // A capability cannot point at a gate; the else branch can be wired like any output.
  const mcp = await addNode(page, 'mcp')
  const mail = await addNode(page, 'mail')
  await expect(edges(page)).toHaveCount(3)
  await refuse(page, handle(mcp, 'source'), handle(cond, 'target'))
  await removeAllEdges(page)
  await drag(page, handle(cond, 'else'), handle(mail, 'target'))
  await expect(edges(page)).toHaveCount(1)
  await drag(page, handle(coord, 'source'), handle(cond, 'target'))
  await expect(edges(page)).toHaveCount(2)
  await expect(part(mail, 'badge')).toHaveText('AFTER')

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'condition').first())
  await expect(field(again, 'Label')).toHaveValue('has deadline')
  await expect(select(again, 'Run the branch when the answer ⓘ')).toHaveValue('matches')
  await expect(field(again, 'Regular expression')).toHaveValue('dead(line)?')
  await expect(field(again, 'Case sensitive')).toBeChecked()
  const saved = await savedFlow(page, NAME)
  const gate = savedNode(saved, 'condition')
  expect(gate.data).toMatchObject({ label: 'has deadline', test: 'matches', value: 'dead(line)?', caseSensitive: true })
  expect(saved.edges).toEqual(expect.arrayContaining([
    expect.objectContaining({ source: gate.id, target: savedNode(saved, 'mail').id, sourceHandle: 'else' }),
    expect.objectContaining({ source: savedNode(saved, 'agent', 'coordinator').id, target: gate.id }),
  ]))
})

test('for each: source and ceiling persist, the ceiling is clamped, gates chain', async ({ page }) => {
  const NAME = 'E2E nodes · for each'
  await newFlow(page, NAME)
  await addNode(page, 'coordinator')
  const each = await addNode(page, 'foreach')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(each, 'icon')).toHaveText('⟳')
  await expect(part(each, 'title')).toHaveText('for each')
  await expect(part(each, 'badge')).toHaveText('EACH')
  await expect(part(each, 'snippet')).toHaveText('one item per line · up to 25')

  const dialog = await openInspector(page, each)
  await field(dialog, 'Label').fill('per invoice')
  await select(dialog, 'Read the list as ⓘ').selectOption('json')
  await field(dialog, 'At most').fill('40')
  await expect(part(each, 'snippet')).toHaveText('a JSON array · up to 40')
  await field(dialog, 'At most').fill('1000')
  await expect(field(dialog, 'At most')).toHaveValue('500')
  await field(dialog, 'At most').fill('40')
  await closeInspector(page)

  // A trigger cannot point at a gate; a gate feeds the next gate.
  const input = await addNode(page, 'input')
  await expect(edges(page)).toHaveCount(2)
  await refuse(page, handle(input, 'source'), handle(each, 'target'))
  const cond = await addNode(page, 'condition')
  await expect(edges(page)).toHaveCount(3)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'foreach').first())
  await expect(field(again, 'Label')).toHaveValue('per invoice')
  await expect(select(again, 'Read the list as ⓘ')).toHaveValue('json')
  await expect(field(again, 'At most')).toHaveValue('40')
  const saved = await savedFlow(page, NAME)
  expect(savedNode(saved, 'foreach').data).toMatchObject({ label: 'per invoice', source: 'json', limit: 40 })
  expect(saved.edges).toContainEqual(
    expect.objectContaining({ source: savedNode(saved, 'foreach').id, target: savedNode(saved, 'condition').id }),
  )
  await expect(cond).toHaveCount(1)
})

test('run another flow: picks a saved flow, and the wiring says when it runs', async ({ page }) => {
  const NAME = 'E2E nodes · run another flow'
  await newFlow(page, NAME)
  const childName = `E2E child flow ${stamp()}`
  const child = await apiPost<{ id: string }>(page, '/api/flows', { name: childName, nodes: [], edges: [] })
  const coord = await addNode(page, 'coordinator')
  const flow = await addNode(page, 'flow')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(flow, 'icon')).toHaveText('🔗')
  await expect(part(flow, 'title')).toHaveText('flow')
  await expect(part(flow, 'badge')).toHaveText('AFTER')
  await expect(part(flow, 'snippet')).toHaveText('no flow selected')

  const dialog = await openInspector(page, flow)
  await field(dialog, 'Label').fill('child')
  await select(dialog, 'Flow to run ⓘ').selectOption(child.id)
  await expect(part(flow, 'snippet')).toHaveText('runs when this flow finishes')
  await expect(dialog.getByText(/wired out of an agent/)).toBeVisible()
  // A hand-off has nobody left to wait: the checkbox exists only for a flow wired into an agent.
  await expect(field(dialog, 'Wait for its answer ⓘ')).toHaveCount(0)
  await closeInspector(page)

  await removeAllEdges(page)
  await expect(part(flow, 'badge')).toHaveText('—')
  await expect(part(flow, 'snippet')).toHaveText('not wired to an agent — it will not run')
  await drag(page, handle(flow, 'source'), handle(coord, 'target'))
  await expect(edges(page)).toHaveCount(1)
  await expect(part(flow, 'badge')).toHaveText('BEFORE')
  await expect(part(flow, 'snippet')).toHaveText('runs first; its answer goes to the agent')
  const before = await openInspector(page, flow)
  await expect(before.getByText(/wired into an agent/)).toBeVisible()
  await expect(field(before, 'Wait for its answer ⓘ')).toBeChecked()
  await field(before, 'Wait for its answer ⓘ').uncheck()
  await expect(part(flow, 'snippet')).toHaveText('starts first; nobody waits for it')
  await closeInspector(page)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  await expect(part(nodesOf(page, 'flow').first(), 'badge')).toHaveText('BEFORE')
  const again = await openInspector(page, nodesOf(page, 'flow').first())
  await expect(field(again, 'Label')).toHaveValue('child')
  await expect(select(again, 'Flow to run ⓘ')).toHaveValue(child.id)
  await expect(field(again, 'Wait for its answer ⓘ')).not.toBeChecked()
  const saved = await savedFlow(page, NAME)
  const run = savedNode(saved, 'flow')
  expect(run.data).toMatchObject({ label: 'child', flowId: child.id, waitForResult: false })
  expect(saved.edges).toEqual([expect.objectContaining({ source: run.id, target: savedNode(saved, 'agent', 'coordinator').id })])
})
