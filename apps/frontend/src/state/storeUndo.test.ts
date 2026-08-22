import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFlowStore } from './store.ts'

/**
 * Undo/redo over the drawing. The contract: every mutating gesture is one step backwards, a
 * burst of typing is ONE step, and loading another flow does not let Ctrl+Z resurrect the
 * previous one.
 */
describe('canvas undo/redo', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('undoes an added node and redoes it back', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    expect(s().nodes).toHaveLength(1)

    s().undo()
    expect(s().nodes).toHaveLength(0)

    s().redo()
    expect(s().nodes).toHaveLength(1)
    expect(s().nodes[0].data.kind).toBe('agent')
  })

  it('undoes a deletion — the reason this exists', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    const id = s().nodes[0].id
    s().deleteNode(id)
    expect(s().nodes).toHaveLength(0)

    s().undo()
    expect(s().nodes.map((n) => n.id)).toEqual([id])
  })

  it('restores the edges a deletion swept away with the node', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    s().addNode('mcp') // auto-wired to the agent
    expect(s().edges).toHaveLength(1)

    s().deleteNode(s().nodes[0].id)
    expect(s().edges).toHaveLength(0)

    s().undo()
    expect(s().nodes).toHaveLength(2)
    expect(s().edges).toHaveLength(1)
  })

  it('coalesces a burst of updateNodeData into one undo step', () => {
    vi.useFakeTimers()
    try {
      const s = () => useFlowStore.getState()
      s().addNode('agent')
      const id = s().nodes[0].id

      // Three keystrokes inside the window: one checkpoint.
      s().updateNodeData(id, { name: 'R' })
      vi.advanceTimersByTime(100)
      s().updateNodeData(id, { name: 'Re' })
      vi.advanceTimersByTime(100)
      s().updateNodeData(id, { name: 'Rep' })

      s().undo()
      expect((s().nodes[0].data as { name: string }).name).toBe('Coordinator')
    } finally {
      vi.useRealTimers()
    }
  })

  it('a pause between edits starts a new undo step', () => {
    vi.useFakeTimers()
    try {
      const s = () => useFlowStore.getState()
      s().addNode('agent')
      const id = s().nodes[0].id

      s().updateNodeData(id, { name: 'First' })
      vi.advanceTimersByTime(2000)
      s().updateNodeData(id, { name: 'Second' })

      s().undo()
      expect((s().nodes[0].data as { name: string }).name).toBe('First')
      s().undo()
      expect((s().nodes[0].data as { name: string }).name).toBe('Coordinator')
    } finally {
      vi.useRealTimers()
    }
  })

  it('a new change after undo clears the redo branch', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    s().undo()
    s().addNode('mcp')

    s().redo()
    // Redoing must not bring the agent back: history forked at the undo.
    expect(s().nodes).toHaveLength(1)
    expect(s().nodes[0].data.kind).toBe('mcp')
  })

  it('loading a flow resets history — undo cannot resurrect the previous drawing', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    s().loadBackendFlow({ id: 'f1', name: 'Other', mode: 'managed', nodes: [], edges: [] })

    s().undo()
    expect(s().nodes).toHaveLength(0)
    expect(s().name).toBe('Other')
  })

  it('refusing a meaningless wire does not burn an undo step', () => {
    const s = () => useFlowStore.getState()
    s().addNode('mcp')
    s().addNode('knowledge')
    const before = s().past.length
    const [mcp, knowledge] = s().nodes
    s().onConnect({ source: mcp.id, target: knowledge.id, sourceHandle: null, targetHandle: null })
    expect(s().edges).toHaveLength(0)
    expect(s().past.length).toBe(before)
  })
})

describe('auto-layout', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('lays the chain left to right and is one undo step', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    s().addNode('flow') // hand-off: chains to the right of the agent
    const [agent, flow] = s().nodes

    // Scramble by hand, as a canvas that grew over a week would be.
    useFlowStore.setState({
      nodes: s().nodes.map((n) => ({ ...n, position: { x: n.id === agent.id ? 900 : 0, y: 300 } })),
    })

    s().autoLayout()
    const placedAgent = s().nodes.find((n) => n.id === agent.id)!
    const placedFlow = s().nodes.find((n) => n.id === flow.id)!
    expect(placedAgent.position.x).toBeLessThan(placedFlow.position.x)

    s().undo()
    expect(s().nodes.find((n) => n.id === agent.id)!.position.x).toBe(900)
  })

  it('hangs capabilities under their agent instead of ranking them into the chain', () => {
    const s = () => useFlowStore.getState()
    s().addNode('agent')
    s().addNode('mcp')
    const [agent, mcp] = s().nodes

    s().autoLayout()
    const placedAgent = s().nodes.find((n) => n.id === agent.id)!
    const placedMcp = s().nodes.find((n) => n.id === mcp.id)!
    expect(placedMcp.position.y).toBeGreaterThan(placedAgent.position.y)
    expect(placedMcp.position.x).toBe(placedAgent.position.x)
  })
})
