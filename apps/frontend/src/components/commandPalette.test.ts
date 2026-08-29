import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFlowStore } from '../state/store.ts'
import { blockCommands, filterCommands, type Command } from './commandPalette.ts'

/** i18next's t, minus i18next: the key with its placeholders filled, which is what English shows. */
const t = (key: string, values?: Record<string, unknown>) =>
  key.replace(/\{\{(\w+)\}\}/g, (_, name) => String(values?.[name] ?? ''))

/**
 * "Go to block": the flow open in the Studio, reachable by name from Ctrl+K. Choosing a row does
 * not touch React Flow — it files a focus request with the store, and the canvas answers it.
 */
describe('blockCommands', () => {
  beforeEach(() => useFlowStore.getState().newFlow())

  it('lists one row per block of the open flow, named and kinded, and a chosen row asks the canvas to go there', () => {
    const s = useFlowStore.getState()
    s.addNode('coordinator')
    s.addNode('mcp')
    const [lead, server] = useFlowStore.getState().nodes
    const focus = vi.fn()

    const rows = blockCommands(useFlowStore.getState().nodes, t, focus)

    expect(rows.map((c) => c.label)).toEqual([
      'Go to block: Coordinator (Coordinator)',
      'Go to block: github (MCP server)',
    ])
    expect(rows.every((c) => c.group === 'Blocks')).toBe(true)
    expect(rows.map((c) => c.id)).toEqual([`block:${lead.id}`, `block:${server.id}`])

    rows[1].run()
    expect(focus).toHaveBeenCalledWith(server.id)
  })

  it('through the store, a chosen row sets the focus request and selects the block', () => {
    const s = useFlowStore.getState()
    s.addNode('coordinator')
    s.addNode('agent')
    const [lead, agent] = useFlowStore.getState().nodes
    const rows = blockCommands(useFlowStore.getState().nodes, t, useFlowStore.getState().requestFocus)

    rows[1].run()

    const after = useFlowStore.getState()
    expect(after.focusNodeId).toBe(agent.id)
    expect(after.selectedId).toBe(agent.id)
    expect(after.nodes.find((n) => n.id === agent.id)?.selected).toBe(true)
    expect(after.nodes.find((n) => n.id === lead.id)?.selected).toBe(false)

    // The canvas, once it has centred on the block, clears the request; the selection stays.
    after.clearFocus()
    expect(useFlowStore.getState().focusNodeId).toBeNull()
    expect(useFlowStore.getState().selectedId).toBe(agent.id)
  })

  it('names a note by its first line and a nameless block by its kind, so every row says something', () => {
    const s = useFlowStore.getState()
    s.addNode('input')
    s.addNode('note')
    const note = useFlowStore.getState().nodes.find((n) => n.data.kind === 'note')!
    useFlowStore.getState().updateNodeData(note.id, { text: '\nBudget: ask ops first\nmore lines' })

    const rows = blockCommands(useFlowStore.getState().nodes, t, vi.fn())

    expect(rows.map((c) => c.label)).toEqual([
      'Go to block: Input / trigger (Input / trigger)',
      'Go to block: Budget: ask ops first (Note)',
    ])
    // And the subsequence match reaches them the way it reaches everything else.
    expect(filterCommands(rows, 'budget').map((c) => c.id)).toEqual([`block:${note.id}`])
  })
})

function command(label: string, group = 'Flows'): Command {
  return { id: `${group}:${label}`, group, label, run: vi.fn() }
}

describe('filterCommands', () => {
  it('keeps the caller’s order when nothing has been typed', () => {
    // The palette opens on what its caller thought was most useful, not on an alphabetical list.
    const commands = [command('Zebra'), command('Apple')]

    expect(filterCommands(commands, '').map((c) => c.label)).toEqual(['Zebra', 'Apple'])
    expect(filterCommands(commands, '   ').map((c) => c.label)).toEqual(['Zebra', 'Apple'])
  })

  it('matches initials, not just prefixes', () => {
    // The promise is "reach anything in a few keystrokes", and that only holds if the keystrokes
    // can be the initials of what you want.
    const commands = [command('Mail triage'), command('Docs from code')]

    expect(filterCommands(commands, 'mtr').map((c) => c.label)).toEqual(['Mail triage'])
    expect(filterCommands(commands, 'dfc').map((c) => c.label)).toEqual(['Docs from code'])
  })

  it('ranks the tighter match first', () => {
    const commands = [command('Monthly transfer report'), command('Mail triage')]

    expect(filterCommands(commands, 'mtr')[0].label).toEqual('Mail triage')
  })

  it('matches the group too, so "flow d" finds a flow called D', () => {
    const commands = [command('Daily briefing', 'Flows'), command('Dark', 'Theme')]

    expect(filterCommands(commands, 'flow d').map((c) => c.label)).toEqual(['Daily briefing'])
  })

  it('ignores case and drops what does not match at all', () => {
    const commands = [command('Mail triage'), command('Usage')]

    expect(filterCommands(commands, 'MAIL').map((c) => c.label)).toEqual(['Mail triage'])
    expect(filterCommands(commands, 'zzz')).toEqual([])
  })

  it('needs the letters in order, which is what makes the ranking mean anything', () => {
    expect(filterCommands([command('Mail triage')], 'egairt')).toEqual([])
  })
})
