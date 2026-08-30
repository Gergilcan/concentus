import { expect, test } from './fixtures'
import {
  addNode, apiPost, closeInspector, drag, edges, field, fineTuning, handle, newFlow, nodesOf, openInspector, part,
  refuse, removeAllEdges, reopenFlow, saveFlow, savedFlow, savedNode, select, stamp,
} from './nodes'

/**
 * The agent block: what it IS can come from the library (a live link) or be its own; what it
 * GETS in this flow — tools, facade, escalation — is always the block's. Both shapes are saved
 * and read back, and the wires an agent accepts are drawn by hand.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('agent: links a library agent, keeps its own tools, and only takes wires that mean something', async ({ page }) => {
  const NAME = 'E2E nodes · agent'
  await newFlow(page, NAME)
  const libName = `E2E librarian ${stamp()}`
  const lib = await apiPost<{ id: string }>(page, '/api/agents', {
    name: libName, model: 'claude-haiku-4-5', effort: 'low', maxTokens: 1234,
    systemPrompt: 'Be brief.', description: 'Use for documentation.',
  })
  const facade = await apiPost<{ id: string }>(page, '/api/facade-profiles', {
    name: `E2E read-only ${stamp()}`, description: '', tools: [], readOnly: true, dryRun: true, readAlso: [],
  })

  const coord = await addNode(page, 'coordinator')
  const agent = await addNode(page, 'agent')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(agent, 'icon')).toHaveText('◆')
  await expect(part(agent, 'title')).toHaveText('Agent')
  await expect(part(agent, 'badge')).toHaveText('agent')

  // Linked: the six governed fields come from the library and are shown, not editable.
  const dialog = await openInspector(page, agent)
  await select(dialog, 'Link to a library agent ⓘ').selectOption(lib.id)
  await expect(dialog.getByText(/linked to library · v1/)).toBeVisible()
  const name = field(dialog, 'Name')
  await expect(name).toHaveValue(libName)
  await expect(name).toHaveAttribute('readonly')
  await expect(select(dialog, 'Model')).toBeDisabled()
  await expect(select(dialog, 'Model')).toHaveValue('claude-haiku-4-5')
  await expect(part(agent, 'title')).toContainText(libName)
  await expect(agent).toContainText('⛓')

  // Unlinked: the copy stays and becomes the block's own.
  await dialog.getByRole('button', { name: 'Unlink (keep a copy)' }).click()
  await expect(name).not.toHaveAttribute('readonly')
  await expect(field(dialog, 'System prompt')).toHaveValue('Be brief.')
  await name.fill('Writer')
  await field(dialog, 'Delegate when… (routing)').fill('Use PROACTIVELY for docs.')
  await field(dialog, 'System prompt').fill('Write the docs.')
  await select(dialog, 'Model').selectOption('__custom__')
  await field(dialog, 'Model id').fill('qwen3:32b')
  await expect(agent).toContainText('qwen3:32b')
  await fineTuning(dialog)
  await select(dialog, 'Effort').selectOption('medium')
  await field(dialog, 'Max tokens').fill('2048')
  await field(dialog, /^Retries after a failure/).fill('2')
  // The allowlist is a comma list: a tool is added and another removed the way a person edits it.
  const tools = field(dialog, /^Allowed tools/)
  await tools.fill('Read, Grep')
  await tools.fill('Read, Grep, Glob')
  await tools.fill('Read, Glob')
  await select(dialog, 'Facade profile (independent workers) ⓘ').selectOption(facade.id)
  await select(dialog, 'Escalation model (blank = off) ⓘ').selectOption('claude-sonnet-5')
  await closeInspector(page)

  // A second agent, linked and left linked, so the link itself is what gets saved.
  const linked = await addNode(page, 'agent')
  await expect(edges(page)).toHaveCount(2)
  const second = await openInspector(page, linked)
  await select(second, 'Link to a library agent ⓘ').selectOption(lib.id)
  await closeInspector(page)

  // Wires. An agent cannot feed its own MCP server; the server feeds the agent, and so does the lead.
  const mcp = await addNode(page, 'mcp')
  await expect(edges(page)).toHaveCount(3)
  await refuse(page, handle(agent, 'source'), handle(mcp, 'target'))
  await removeAllEdges(page)
  await drag(page, handle(mcp, 'source'), handle(agent, 'target'))
  await expect(edges(page)).toHaveCount(1)
  await drag(page, handle(coord, 'source'), handle(agent, 'target'))
  await expect(edges(page)).toHaveCount(2)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const writer = nodesOf(page, 'agent').filter({ hasText: 'Writer' })
  await expect(writer).toContainText('qwen3:32b')
  const again = await openInspector(page, writer)
  await expect(field(again, 'Name')).toHaveValue('Writer')
  await expect(select(again, 'Model')).toHaveValue('__custom__')
  await expect(field(again, 'Model id')).toHaveValue('qwen3:32b')
  await expect(field(again, 'Delegate when… (routing)')).toHaveValue('Use PROACTIVELY for docs.')
  await fineTuning(again)
  await expect(select(again, 'Effort')).toHaveValue('medium')
  await expect(field(again, 'Max tokens')).toHaveValue('2048')
  await expect(field(again, /^Retries after a failure/)).toHaveValue('2')
  await expect(field(again, /^Allowed tools/)).toHaveValue('Read, Glob')
  await expect(select(again, 'Facade profile (independent workers) ⓘ')).toHaveValue(facade.id)
  await expect(select(again, 'Escalation model (blank = off) ⓘ')).toHaveValue('claude-sonnet-5')
  await closeInspector(page)
  await expect(nodesOf(page, 'agent').filter({ hasText: libName })).toContainText('⛓')

  const saved = await savedFlow(page, NAME)
  const own = saved.nodes.find((n) => n.data.name === 'Writer')!
  expect(own).toMatchObject({ type: 'agent', role: 'subagent' })
  expect(own.data).toMatchObject({
    model: 'qwen3:32b', description: 'Use PROACTIVELY for docs.', systemPrompt: 'Write the docs.', effort: 'medium',
    maxTokens: 2048, retries: 2, tools: ['Read', 'Glob'], facadeProfileId: facade.id, fallbackModelId: 'claude-sonnet-5',
  })
  expect(own.data.libraryAgentId).toBeUndefined()
  const ref = saved.nodes.find((n) => n.data.libraryAgentId === lib.id)!
  expect(ref.data).toMatchObject({
    name: libName, model: 'claude-haiku-4-5', effort: 'low', maxTokens: 1234, systemPrompt: 'Be brief.', libraryVersion: 1,
  })
  const wires = saved.edges.map((e) => [e.source, e.target])
  expect(wires).toHaveLength(2)
  expect(wires).toEqual(expect.arrayContaining([
    [savedNode(saved, 'mcp').id, own.id],
    [savedNode(saved, 'agent', 'coordinator').id, own.id],
  ]))
})
