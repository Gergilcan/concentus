import { beforeEach, describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { useFlowStore } from './store.ts'

// Exercises the canvas <-> backend flow transform: nodes added on the React Flow
// canvas (with positions, agent role inference, etc.) must round-trip through
// toBackendFlow()/loadBackendFlow() without losing data, since that's the exact
// path used to save a flow and reopen it (see Toolbar.tsx `save`/`openFlow`).
describe('useFlowStore canvas <-> backend flow transform', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('toBackendFlow serializes nodes/edges and stashes canvas position in data._pos', () => {
    const { addNode, onConnect, setName } = useFlowStore.getState()
    setName('My Flow')
    addNode('agent')
    addNode('mcp')

    const [agentNode, mcpNode] = useFlowStore.getState().nodes
    onConnect({ source: agentNode.id, target: mcpNode.id, sourceHandle: null, targetHandle: null })

    const backendFlow = useFlowStore.getState().toBackendFlow()

    expect(backendFlow.name).toBe('My Flow')
    expect(backendFlow.nodes).toHaveLength(2)
    expect(backendFlow.edges).toHaveLength(1)
    expect(backendFlow.edges[0]).toMatchObject({ source: agentNode.id, target: mcpNode.id })

    const serializedAgent = backendFlow.nodes.find((n) => n.id === agentNode.id)!
    expect(serializedAgent.type).toBe('agent')
    // the first agent added becomes the coordinator
    expect(serializedAgent.role).toBe('coordinator')
    expect(serializedAgent.data._pos).toEqual(agentNode.position)
    // `kind` is a client-side discriminant and must not leak into the persisted data blob
    expect(serializedAgent.data.kind).toBeUndefined()
  })

  it('loadBackendFlow reconstructs canvas nodes (position + role) from a persisted flow', () => {
    const flow: BackendFlow = {
      id: 'flow-1',
      name: 'Loaded flow',
      mode: 'local',
      nodes: [
        {
          id: 'agent-1',
          type: 'agent',
          role: 'coordinator',
          data: { name: 'Lead', _pos: { x: 42, y: 7 } },
        },
      ],
      edges: [],
    }

    useFlowStore.getState().loadBackendFlow(flow)
    const state = useFlowStore.getState()

    expect(state.flowId).toBe('flow-1')
    expect(state.name).toBe('Loaded flow')
    expect(state.mode).toBe('local')
    expect(state.nodes).toHaveLength(1)
    expect(state.nodes[0].position).toEqual({ x: 42, y: 7 })
    expect(state.nodes[0].data.kind).toBe('agent')
    expect((state.nodes[0].data as { role: string }).role).toBe('coordinator')
    expect((state.nodes[0].data as { name: string }).name).toBe('Lead')
  })

  it('a second agent added after the first defaults to a subagent role', () => {
    const { addNode } = useFlowStore.getState()
    addNode('agent')
    addNode('agent')

    const [first, second] = useFlowStore.getState().nodes
    expect((first.data as { role: string }).role).toBe('coordinator')
    expect((second.data as { role: string }).role).toBe('subagent')
  })
})

// Copy/paste/duplicate of canvas blocks. The risky part isn't the cloning itself
// but the fields that must NOT be shared with the original: FlowCompiler rejects a
// flow with two coordinators, agents are delegated to by name, and an input node's
// webhook `secret` is a per-node credential.
describe('useFlowStore copy / paste / duplicate', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    // newFlow deliberately keeps the clipboard (see the cross-flow paste test
    // below), so tests that assume an empty one must clear it themselves.
    useFlowStore.setState({ clipboard: null })
  })

  it('duplicating the coordinator demotes the copy to subagent and renames it', () => {
    const { addNode, duplicateNode } = useFlowStore.getState()
    addNode('agent')
    const original = useFlowStore.getState().nodes[0]
    expect(original.data).toMatchObject({ role: 'coordinator', name: 'Coordinator' })

    duplicateNode(original.id)

    const { nodes } = useFlowStore.getState()
    expect(nodes).toHaveLength(2)
    // the original is untouched...
    expect(nodes[0].data).toMatchObject({ role: 'coordinator', name: 'Coordinator' })
    // ...and the copy is a distinct node that can't produce a second coordinator
    expect(nodes[1].id).not.toBe(original.id)
    expect(nodes[1].data).toMatchObject({ role: 'subagent', name: 'Coordinator copy' })
    expect(nodes[1].position).not.toEqual(original.position)
  })

  it('gives each duplicate a unique name instead of colliding', () => {
    const { addNode, duplicateNode } = useFlowStore.getState()
    addNode('agent')
    const id = useFlowStore.getState().nodes[0].id

    duplicateNode(id)
    duplicateNode(id)

    const names = useFlowStore.getState().nodes.map((n) => (n.data as { name: string }).name)
    expect(names).toEqual(['Coordinator', 'Coordinator copy', 'Coordinator copy 2'])
    expect(new Set(names).size).toBe(names.length)
  })

  it('clears the webhook secret so a copied input node is not a credential clone', () => {
    const { addNode, duplicateNode, updateNodeData } = useFlowStore.getState()
    addNode('input')
    const original = useFlowStore.getState().nodes[0]
    updateNodeData(original.id, { secret: 'lin_wh_original' })

    duplicateNode(original.id)

    const [a, b] = useFlowStore.getState().nodes as { data: { secret: string } }[]
    expect(a.data.secret).toBe('lin_wh_original')
    // the copy is a separate endpoint and must get its own provider-issued secret
    expect(b.data.secret).toBe('')
  })

  it('copy/paste re-wires edges among the copies and drops half-edges', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    addNode('sql')
    const [agent, mcp, sql] = useFlowStore.getState().nodes
    onConnect({ source: agent.id, target: mcp.id, sourceHandle: null, targetHandle: null })
    // an edge with only ONE endpoint in the copied set — must not be carried over
    onConnect({ source: agent.id, target: sql.id, sourceHandle: null, targetHandle: null })

    // select agent + mcp only
    useFlowStore.setState((s) => ({
      nodes: s.nodes.map((n) => ({ ...n, selected: n.id === agent.id || n.id === mcp.id })),
    }))

    expect(useFlowStore.getState().copySelection()).toBe(2)
    useFlowStore.getState().paste()

    const { nodes, edges } = useFlowStore.getState()
    expect(nodes).toHaveLength(5)

    const copies = nodes.slice(3)
    const copyIds = new Set(copies.map((n) => n.id))
    const newEdges = edges.filter((e) => copyIds.has(e.source) || copyIds.has(e.target))
    // exactly one new edge, fully internal to the copies
    expect(newEdges).toHaveLength(1)
    expect(copyIds.has(newEdges[0].source)).toBe(true)
    expect(copyIds.has(newEdges[0].target)).toBe(true)
    // originals keep both of their edges
    expect(edges).toHaveLength(3)
  })

  it('pasting repeatedly cascades instead of stacking copies on one spot', () => {
    const { addNode } = useFlowStore.getState()
    addNode('mcp')
    const original = useFlowStore.getState().nodes[0]
    useFlowStore.setState((s) => ({ nodes: s.nodes.map((n) => ({ ...n, selected: true })) }))
    useFlowStore.getState().copySelection()

    useFlowStore.getState().paste()
    useFlowStore.getState().paste()

    const [, first, second] = useFlowStore.getState().nodes
    expect(first.position).not.toEqual(original.position)
    expect(second.position).not.toEqual(first.position)
  })

  it('paste is a no-op when nothing has been copied', () => {
    useFlowStore.getState().addNode('agent')
    useFlowStore.getState().paste()
    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })

  it('keeps the clipboard across flows so blocks can be pasted into another flow', () => {
    const { addNode } = useFlowStore.getState()
    addNode('sql')
    useFlowStore.setState((s) => ({ nodes: s.nodes.map((n) => ({ ...n, selected: true })) }))
    useFlowStore.getState().copySelection()

    // switch to a different, empty flow
    useFlowStore.getState().newFlow()
    expect(useFlowStore.getState().nodes).toHaveLength(0)

    useFlowStore.getState().paste()

    const { nodes } = useFlowStore.getState()
    expect(nodes).toHaveLength(1)
    expect(nodes[0].data).toMatchObject({ kind: 'sql', label: 'db copy' })
  })
})
