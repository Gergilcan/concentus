import { beforeEach, describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { canConnect } from '../flow/wiring.ts'
import { useFlowStore } from './store.ts'

/**
 * Notes and frames: what the author draws for the next reader, which the run never sees.
 *
 * The contract: they are saved like any node, a frame's members travel with it — on the canvas,
 * through a copy, and on the wire — and a client that knows nothing about frames still finds
 * every block where it was drawn, because `_pos` stays absolute.
 */
describe('annotations on the canvas', () => {
  const s = () => useFlowStore.getState()

  beforeEach(() => {
    s().newFlow()
  })

  it('a note and a frame are added with their defaults, and nothing wires to them', () => {
    s().addNode('agent')
    s().addNode('note')
    s().addNode('group')

    const [agent, group, note] = [...s().nodes].sort((a, b) => a.data.kind.localeCompare(b.data.kind))
    expect(note.data).toEqual({ kind: 'note', text: '', color: 'yellow' })
    expect(group.data).toEqual({ kind: 'group', label: 'Group', color: 'blue' })
    // A frame has a size from birth and sits behind the blocks.
    expect(group.width).toBeGreaterThan(0)
    expect(group.height).toBeGreaterThan(0)
    expect(group.zIndex).toBe(-1)
    // The agent was there first, and nothing auto-wired to the annotations.
    expect(s().edges).toHaveLength(0)
    expect(canConnect('agent', 'note')).toBe(false)
    expect(canConnect('note', 'agent')).toBe(false)
    expect(canConnect('group', 'agent')).toBe(false)
    expect(agent.data.kind).toBe('agent')
  })

  it('a block dropped inside a frame becomes its member, at a position relative to it', () => {
    s().addNode('group', { x: 100, y: 100 })
    const group = s().nodes[0]
    s().addNode('agent', { x: 150, y: 160 })

    const agent = s().nodes.find((n) => n.data.kind === 'agent')!
    expect(agent.parentId).toBe(group.id)
    expect(agent.position).toEqual({ x: 50, y: 60 })
    // Frames come first in the list: React Flow resolves a member from its parent.
    expect(s().nodes[0].id).toBe(group.id)
  })

  it('dragging a member out of the frame releases it at its absolute place', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('agent', { x: 150, y: 160 })
    const agent = s().nodes.find((n) => n.data.kind === 'agent')!

    // The drag itself moved the (relative) position far outside the 480x260 frame.
    s().onNodesChange([{ type: 'position', id: agent.id, position: { x: 900, y: 40 } }])
    s().settleDrop([agent.id])

    const freed = s().nodes.find((n) => n.id === agent.id)!
    expect(freed.parentId).toBeUndefined()
    expect(freed.position).toEqual({ x: 1000, y: 140 })
  })

  it('saves _pos absolute with _parent and _size beside it, and restores the frame on load', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('agent', { x: 150, y: 160 })
    s().addNode('note', { x: 700, y: 20 })
    const groupId = s().nodes[0].id

    const wire = s().toBackendFlow()
    const group = wire.nodes.find((n) => n.type === 'group')!
    const agent = wire.nodes.find((n) => n.type === 'agent')!
    const note = wire.nodes.find((n) => n.type === 'note')!
    // An old client reads only `_pos`, and must see the agent where it is drawn, not at (50, 60).
    expect(agent.data._pos).toEqual({ x: 150, y: 160 })
    expect(agent.data._parent).toBe(groupId)
    expect(group.data._size).toEqual({ w: 480, h: 260 })
    expect(group.data._pos).toEqual({ x: 100, y: 100 })
    expect(note.data).toEqual({ text: '', color: 'yellow', _pos: { x: 700, y: 20 } })
    // Canvas-only facts never leak into the data.
    expect(agent.data.kind).toBeUndefined()
    expect(agent.data._size).toBeUndefined()

    s().newFlow()
    s().loadBackendFlow({ ...wire, id: 'f1' })
    const loadedGroup = s().nodes.find((n) => n.data.kind === 'group')!
    const loadedAgent = s().nodes.find((n) => n.data.kind === 'agent')!
    expect(s().nodes[0].id).toBe(loadedGroup.id)
    expect(loadedGroup.width).toBe(480)
    expect(loadedGroup.height).toBe(260)
    expect(loadedGroup.zIndex).toBe(-1)
    expect(loadedAgent.parentId).toBe(loadedGroup.id)
    expect(loadedAgent.position).toEqual({ x: 50, y: 60 })
    expect((loadedAgent.data as Record<string, unknown>)._parent).toBeUndefined()
    // And the round trip is stable: saving again writes the same absolute place.
    expect(s().toBackendFlow().nodes.find((n) => n.type === 'agent')!.data._pos).toEqual({ x: 150, y: 160 })
  })

  it('a _parent naming no frame is ignored rather than trusted', () => {
    const flow: BackendFlow = {
      id: 'f1',
      name: 'Orphan',
      nodes: [
        { id: 'a1', type: 'agent', role: 'coordinator', data: { name: 'Lead', _pos: { x: 30, y: 40 }, _parent: 'gone' } },
      ],
      edges: [],
    }
    s().loadBackendFlow(flow)
    expect(s().nodes[0].parentId).toBeUndefined()
    expect(s().nodes[0].position).toEqual({ x: 30, y: 40 })
  })

  it('duplicating a frame duplicates its members with it, still inside the copy', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('coordinator', { x: 150, y: 160 })
    s().addNode('agent', { x: 400, y: 160 })
    expect(s().edges).toHaveLength(1) // coordinator ↔ agent, auto-wired
    const groupId = s().nodes[0].id

    s().duplicateNode(groupId)

    const groups = s().nodes.filter((n) => n.data.kind === 'group')
    expect(groups).toHaveLength(2)
    const copy = groups.find((g) => g.id !== groupId)!
    const members = s().nodes.filter((n) => n.parentId === copy.id)
    expect(members.map((n) => n.data.kind).sort()).toEqual(['agent', 'agent'])
    // Members keep their place inside the copy; the copy is the one that moved.
    expect(members.map((n) => n.position)).toEqual(
      expect.arrayContaining([
        { x: 50, y: 60 },
        { x: 300, y: 60 },
      ]),
    )
    expect(copy.position).toEqual({ x: 140, y: 140 })
    // The wire between the members came along, and the originals are untouched.
    expect(s().edges).toHaveLength(2)
    expect(s().nodes.filter((n) => n.parentId === groupId)).toHaveLength(2)
  })

  it('copying a member without its frame pastes it free, at its absolute place', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('agent', { x: 150, y: 160 })
    const agent = s().nodes.find((n) => n.data.kind === 'agent')!
    s().selectNode(agent.id)

    expect(s().copySelection()).toBe(1)
    s().paste()

    const pasted = s().nodes.find((n) => n.data.kind === 'agent' && n.id !== agent.id)!
    expect(pasted.parentId).toBeUndefined()
    expect(pasted.position).toEqual({ x: 190, y: 200 })
  })

  it('deleting a frame keeps its members, released at their absolute places, and undo puts it back', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('agent', { x: 150, y: 160 })
    const groupId = s().nodes[0].id

    s().deleteNode(groupId)

    expect(s().nodes).toHaveLength(1)
    expect(s().nodes[0].parentId).toBeUndefined()
    expect(s().nodes[0].position).toEqual({ x: 150, y: 160 })

    s().undo()
    const agent = s().nodes.find((n) => n.data.kind === 'agent')!
    expect(agent.parentId).toBe(groupId)
    expect(agent.position).toEqual({ x: 50, y: 60 })
  })

  it('the Delete key on a frame does the same through the change stream', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('agent', { x: 150, y: 160 })
    const groupId = s().nodes[0].id

    s().onNodesChange([{ type: 'remove', id: groupId }])

    expect(s().nodes.map((n) => n.data.kind)).toEqual(['agent'])
    expect(s().nodes[0].parentId).toBeUndefined()
    expect(s().nodes[0].position).toEqual({ x: 150, y: 160 })
  })

  it('tidy leaves notes where they are, lays a frame’s members out inside it and sizes the frame to fit', () => {
    s().addNode('group', { x: 100, y: 100 })
    s().addNode('coordinator', { x: 150, y: 160 })
    s().addNode('agent', { x: 150, y: 170 })
    s().addNode('note', { x: 900, y: 900 })
    s().addNode('mcp', { x: 3000, y: 3000 })
    const group = s().nodes.find((n) => n.data.kind === 'group')!
    const noteBefore = s().nodes.find((n) => n.data.kind === 'note')!.position

    s().autoLayout()

    const after = s()
    expect(after.nodes.find((n) => n.data.kind === 'note')!.position).toEqual(noteBefore)
    const members = after.nodes.filter((n) => n.parentId === group.id)
    expect(members.map((n) => n.data.kind).sort()).toEqual(['agent', 'coordinator'])
    // Inside the frame: below the label band, off the left edge, and not on top of each other.
    for (const m of members) {
      expect(m.position.x).toBeGreaterThanOrEqual(16)
      expect(m.position.y).toBeGreaterThanOrEqual(36)
    }
    expect(members[0].position).not.toEqual(members[1].position)
    // The frame wraps them, and is a block of the outer layout rather than a fixed point.
    const frame = after.nodes.find((n) => n.id === group.id)!
    for (const m of members) {
      expect(m.position.x + 214).toBeLessThanOrEqual(frame.width!)
      expect(m.position.y + 110).toBeLessThanOrEqual(frame.height!)
    }
    expect(after.nodes.find((n) => n.data.kind === 'mcp')!.position).not.toEqual({ x: 3000, y: 3000 })
  })
})
