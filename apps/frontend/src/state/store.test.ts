import { beforeEach, describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { useFlowStore } from './store.ts'

// Exercises the canvas <-> backend flow transform: nodes added on the React Flow
// canvas (with positions, the coordinator/agent split, etc.) must round-trip through
// toBackendFlow()/loadBackendFlow() without losing data, since that's the exact
// path used to save a flow and reopen it (see Toolbar.tsx `save`/`openFlow`).
describe('useFlowStore canvas <-> backend flow transform', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('toBackendFlow serializes nodes/edges and stashes canvas position in data._pos', () => {
    const { addNode, onConnect, setName } = useFlowStore.getState()
    setName('My Flow')
    addNode('coordinator')
    addNode('mcp')

    const [agentNode, mcpNode] = useFlowStore.getState().nodes
    onConnect({ source: mcpNode.id, target: agentNode.id, sourceHandle: null, targetHandle: null })

    const backendFlow = useFlowStore.getState().toBackendFlow()

    expect(backendFlow.name).toBe('My Flow')
    expect(backendFlow.nodes).toHaveLength(2)
    expect(backendFlow.edges).toHaveLength(1)
    expect(backendFlow.edges[0]).toMatchObject({ source: mcpNode.id, target: agentNode.id })

    const serializedAgent = backendFlow.nodes.find((n) => n.id === agentNode.id)!
    // On the wire the lead is an agent with the coordinator role — the shape every stored flow,
    // the MCP flow tools and the generator already speak. The role travels inside the data too,
    // where the trigger's permission lookup reads it.
    expect(serializedAgent.type).toBe('agent')
    expect(serializedAgent.role).toBe('coordinator')
    expect(serializedAgent.data.role).toBe('coordinator')
    expect(serializedAgent.data._pos).toEqual(agentNode.position)
    // `kind` is a client-side discriminant and must not leak into the persisted data blob
    expect(serializedAgent.data.kind).toBeUndefined()
  })

  it('loadBackendFlow reconstructs canvas nodes (position, and the lead as a coordinator block) from a persisted flow', () => {
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
    expect(state.nodes[0].data.kind).toBe('coordinator')
    expect(state.nodes[0].type).toBe('coordinator')
    // The role is a wire fact, not a canvas field: it is derived from the kind on save.
    expect((state.nodes[0].data as { role?: unknown }).role).toBeUndefined()
    expect((state.nodes[0].data as { name: string }).name).toBe('Lead')
  })

  it('a flow takes one coordinator; a second is refused, and agents are agents', () => {
    const { addNode } = useFlowStore.getState()
    addNode('coordinator')
    addNode('coordinator')
    addNode('agent')

    const nodes = useFlowStore.getState().nodes
    expect(nodes.map((n) => n.data.kind)).toEqual(['coordinator', 'agent'])
    const backend = useFlowStore.getState().toBackendFlow()
    expect(backend.nodes.map((n) => [n.type, n.role])).toEqual([
      ['agent', 'coordinator'],
      ['agent', 'subagent'],
    ])
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

  it('duplicating the coordinator makes the copy an agent and renames it', () => {
    const { addNode, duplicateNode } = useFlowStore.getState()
    addNode('coordinator')
    const original = useFlowStore.getState().nodes[0]
    expect(original.data).toMatchObject({ kind: 'coordinator', name: 'Coordinator' })

    duplicateNode(original.id)

    const { nodes } = useFlowStore.getState()
    expect(nodes).toHaveLength(2)
    // the original is untouched...
    expect(nodes[0].data).toMatchObject({ kind: 'coordinator', name: 'Coordinator' })
    // ...and the copy is a distinct node that can't produce a second coordinator: the data AND
    // the canvas node type say agent, so it is drawn and saved as one.
    expect(nodes[1].id).not.toBe(original.id)
    expect(nodes[1].type).toBe('agent')
    expect(nodes[1].data).toMatchObject({ kind: 'agent', name: 'Coordinator copy' })
    expect(nodes[1].position).not.toEqual(original.position)
  })

  it('gives each duplicate a unique name instead of colliding', () => {
    const { addNode, duplicateNode } = useFlowStore.getState()
    addNode('agent')
    const id = useFlowStore.getState().nodes[0].id

    duplicateNode(id)
    duplicateNode(id)

    const names = useFlowStore.getState().nodes.map((n) => (n.data as { name: string }).name)
    expect(names).toEqual(['Agent', 'Agent copy', 'Agent copy 2'])
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
    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })
    // an edge with only ONE endpoint in the copied set — must not be carried over
    onConnect({ source: sql.id, target: agent.id, sourceHandle: null, targetHandle: null })

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

// Direct node/edge editing used by the canvas toolbar and Inspector: updating a single
// node's data, deleting nodes/edges, and the id/selection bookkeeping that goes with it.
describe('useFlowStore node/edge mutations', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    useFlowStore.setState({ clipboard: null })
  })

  it('updateNodeData merges a patch into one node without touching others', () => {
    const { addNode, updateNodeData } = useFlowStore.getState()
    addNode('agent')
    addNode('agent')
    const [first, second] = useFlowStore.getState().nodes
    const secondNameBefore = (second.data as { name: string }).name

    updateNodeData(first.id, { name: 'Renamed' })

    const state = useFlowStore.getState()
    expect((state.nodes[0].data as { name: string }).name).toBe('Renamed')
    expect((state.nodes[1].data as { name: string }).name).toBe(secondNameBefore)
  })

  // A Save answers whenever the server gets round to it; if the person has meanwhile opened a
  // block's inspector, the reload must not pull the selection out from under it.
  it('loadBackendFlow keeps the selection when the same block is in the loaded flow, and clears it otherwise', () => {
    const { addNode, selectNode, toBackendFlow, loadBackendFlow } = useFlowStore.getState()
    addNode('agent')
    const agent = useFlowStore.getState().nodes[0]
    selectNode(agent.id)

    loadBackendFlow({ ...toBackendFlow(), id: 'f1' })
    expect(useFlowStore.getState().selectedId).toBe(agent.id)

    loadBackendFlow({ id: 'f2', name: 'Other', mode: 'managed', nodes: [], edges: [] })
    expect(useFlowStore.getState().selectedId).toBeNull()
  })

  it('deleteNode removes the node, any edges touching it, and clears selection if it was selected', () => {
    const { addNode, onConnect, selectNode, deleteNode } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes
    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })
    selectNode(agent.id)

    deleteNode(agent.id)

    const state = useFlowStore.getState()
    expect(state.nodes.map((n) => n.id)).toEqual([mcp.id])
    expect(state.edges).toHaveLength(0)
    expect(state.selectedId).toBeNull()
  })

  it('deleteNode leaves selection untouched when a different node is deleted', () => {
    const { addNode, selectNode, deleteNode } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes
    selectNode(agent.id)

    deleteNode(mcp.id)

    expect(useFlowStore.getState().selectedId).toBe(agent.id)
  })

  it('deleteEdge removes only the targeted edge', () => {
    const { addNode, onConnect, deleteEdge } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    addNode('sql')
    const [agent, mcp, sql] = useFlowStore.getState().nodes
    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })
    onConnect({ source: sql.id, target: agent.id, sourceHandle: null, targetHandle: null })
    const [edgeToRemove, keep] = useFlowStore.getState().edges

    deleteEdge(edgeToRemove.id)

    const { edges } = useFlowStore.getState()
    expect(edges).toHaveLength(1)
    expect(edges[0].id).toBe(keep.id)
  })

  it('onConnect assigns a unique "e_"-prefixed id to the new edge', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes

    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })

    expect(useFlowStore.getState().edges[0].id).toMatch(/^e_/)
  })

  it('selectNode sets and clears selectedId', () => {
    const { addNode, selectNode } = useFlowStore.getState()
    addNode('agent')
    const id = useFlowStore.getState().nodes[0].id

    selectNode(id)
    expect(useFlowStore.getState().selectedId).toBe(id)

    selectNode(null)
    expect(useFlowStore.getState().selectedId).toBeNull()
  })

  it('setMode switches between managed and local', () => {
    useFlowStore.getState().setMode('local')
    expect(useFlowStore.getState().mode).toBe('local')

    useFlowStore.getState().setMode('managed')
    expect(useFlowStore.getState().mode).toBe('managed')
  })
})

// copySelection/duplicateSelection target either the multi-selection or, absent one, the
// single inspected node (see `targetNodes`) — that fallback path wasn't exercised above
// since the copy/paste tests always used an explicit multi-selection.
describe('useFlowStore selection targeting for copy/duplicate', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    useFlowStore.setState({ clipboard: null })
  })

  it('copySelection returns 0 and leaves the clipboard untouched when nothing is selected or inspected', () => {
    useFlowStore.getState().addNode('agent')
    // addNode auto-selects via selectedId; clear it so neither targeting path matches.
    useFlowStore.setState({ selectedId: null })

    expect(useFlowStore.getState().copySelection()).toBe(0)
    expect(useFlowStore.getState().clipboard).toBeNull()
  })

  it('copySelection falls back to the inspected (selectedId) node when no node has .selected', () => {
    const { addNode, copySelection } = useFlowStore.getState()
    addNode('agent')
    expect(useFlowStore.getState().nodes[0].selected).toBeFalsy()

    expect(copySelection()).toBe(1)
    expect(useFlowStore.getState().clipboard?.nodes).toHaveLength(1)
  })

  it('duplicateSelection clones every selected node plus their internal edge in one shot', () => {
    const { addNode, onConnect, duplicateSelection } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes
    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })
    useFlowStore.setState((s) => ({ nodes: s.nodes.map((n) => ({ ...n, selected: true })) }))

    duplicateSelection()

    const { nodes, edges } = useFlowStore.getState()
    expect(nodes).toHaveLength(4)
    const copies = nodes.slice(2)
    expect(copies.every((n) => n.selected)).toBe(true)
    const copyIds = new Set(copies.map((n) => n.id))
    const internalEdges = edges.filter((e) => copyIds.has(e.source) && copyIds.has(e.target))
    expect(internalEdges).toHaveLength(1)
  })

  it('duplicateSelection is a no-op when nothing is selected or inspected', () => {
    useFlowStore.getState().addNode('agent')
    useFlowStore.setState({ selectedId: null })

    useFlowStore.getState().duplicateSelection()

    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })
})

// Live execution overlay: RunPanel/canvas nodes read runExecByNode/runTotals while a run is
// in flight. setActiveRun must reset the overlay on a genuine run switch but NOT wipe it out
// from under an in-progress run just because the same id is set again (e.g. re-renders).
describe('useFlowStore live run overlay', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('setRunExec indexes nodes by id and totals input/output tokens', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'n1',
          kind: 'agent',
          label: 'Coordinator',
          status: 'passed',
          inputTokens: 10,
          outputTokens: 20,
          startedAt: 0,
          endedAt: 1,
        },
      ],
      totalInputTokens: 10,
      totalOutputTokens: 20,
    })

    const state = useFlowStore.getState()
    expect(state.runExecByNode.n1).toMatchObject({ status: 'passed', outputTokens: 20 })
    expect(state.runTotals).toEqual({ input: 10, output: 20, costUsd: 0 })
  })

  it('setRunExec(null) clears the overlay back to empty', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        { nodeId: 'n1', kind: 'agent', label: 'A', status: 'passed', inputTokens: 1, outputTokens: 1, startedAt: 0, endedAt: 1 },
      ],
      totalInputTokens: 1,
      totalOutputTokens: 1,
    })

    useFlowStore.getState().setRunExec(null)

    expect(useFlowStore.getState().runExecByNode).toEqual({})
    expect(useFlowStore.getState().runTotals).toEqual({ input: 0, output: 0, costUsd: 0 })
  })

  it('re-setting the SAME active run id does not wipe the overlay just built for it', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        { nodeId: 'n1', kind: 'agent', label: 'A', status: 'running', inputTokens: 5, outputTokens: 0, startedAt: 0, endedAt: 0 },
      ],
      totalInputTokens: 5,
      totalOutputTokens: 0,
    })

    useFlowStore.getState().setActiveRun('run-1')

    const state = useFlowStore.getState()
    expect(state.runExecByNode.n1).toBeDefined()
    expect(state.runTotals).toEqual({ input: 5, output: 0, costUsd: 0 })
  })

  it('switching to a DIFFERENT active run id clears the stale overlay', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        { nodeId: 'n1', kind: 'agent', label: 'A', status: 'passed', inputTokens: 5, outputTokens: 5, startedAt: 0, endedAt: 1 },
      ],
      totalInputTokens: 5,
      totalOutputTokens: 5,
    })

    useFlowStore.getState().setActiveRun('run-2')

    const state = useFlowStore.getState()
    expect(state.activeRunId).toBe('run-2')
    expect(state.runExecByNode).toEqual({})
    expect(state.runTotals).toEqual({ input: 0, output: 0, costUsd: 0 })
  })

  it('carries fan-out graph metrics through and clears them with the overlay', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        { nodeId: 'w1', kind: 'worker', label: 'W', status: 'passed', inputTokens: 1, outputTokens: 1, startedAt: 0, endedAt: 5 },
      ],
      totalInputTokens: 1,
      totalOutputTokens: 1,
      graph: { workers: 2, workersFailed: 0, workersRejected: 1, retries: 1, verdicts: 2, wallMs: 5, sumWorkerMs: 9 },
    })

    expect(useFlowStore.getState().runGraph).toMatchObject({ workers: 2, workersRejected: 1 })

    useFlowStore.getState().setRunExec(null)
    expect(useFlowStore.getState().runGraph).toBeNull()
  })

  it('a verdict landing AFTER a worker finished still updates the overlay', () => {
    // The bail-out signature must include the verdict: it arrives after the worker's endedAt,
    // so a signature of status+tokens+endedAt alone would dismiss that poll as "no change".
    const finished = {
      nodeId: 'w1', kind: 'worker', label: 'W', status: 'passed' as const,
      inputTokens: 1, outputTokens: 1, startedAt: 0, endedAt: 5,
    }
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({ nodes: [finished], totalInputTokens: 1, totalOutputTokens: 1 })
    useFlowStore.getState().setRunExec({
      nodes: [{ ...finished, verdict: 'rejected', verdictReason: 'off-task' }],
      totalInputTokens: 1,
      totalOutputTokens: 1,
    })

    expect(useFlowStore.getState().runExecByNode.w1.verdict).toBe('rejected')
  })
})

// Flow-level metadata (enabled/tags/favorite/notifyWebhook) is edited from the Flows dashboard,
// not the canvas, but must survive a load -> canvas-save round trip (toBackendFlow spreads
// `flowMeta` back onto the payload).
describe('useFlowStore flow metadata round-trip', () => {
  it('loadBackendFlow captures dashboard metadata and toBackendFlow serializes it back out', () => {
    const flow: BackendFlow = {
      id: 'flow-9',
      name: 'Meta flow',
      mode: 'managed',
      nodes: [],
      edges: [],
      enabled: false,
      tags: ['prod', 'nightly'],
      favorite: true,
      notifyWebhook: 'https://hooks.example.com/x',
    }

    useFlowStore.getState().loadBackendFlow(flow)

    expect(useFlowStore.getState().flowMeta).toEqual({
      enabled: false,
      tags: ['prod', 'nightly'],
      favorite: true,
      notifyWebhook: 'https://hooks.example.com/x',
    })

    const roundTripped = useFlowStore.getState().toBackendFlow()
    expect(roundTripped).toMatchObject({
      enabled: false,
      tags: ['prod', 'nightly'],
      favorite: true,
      notifyWebhook: 'https://hooks.example.com/x',
    })
  })
})

describe('addNode placement', () => {
  it('puts a dropped node exactly where it was dropped', () => {
    useFlowStore.getState().newFlow()

    useFlowStore.getState().addNode('agent', { x: 412, y: 233 })

    const node = useFlowStore.getState().nodes.at(-1)!
    // Untouched by the cascade: nudging it would move it out from under the cursor.
    expect(node.position).toEqual({ x: 412, y: 233 })
  })

  it('still cascades a node added without a position', () => {
    useFlowStore.getState().newFlow()

    useFlowStore.getState().addNode('mcp')
    useFlowStore.getState().addNode('mcp')

    const [a, b] = useFlowStore.getState().nodes.slice(-2)
    // Clicking remains available — a drag is not reachable by keyboard — and two clicks must not
    // stack one node exactly on top of another.
    expect(b.position).not.toEqual(a.position)
  })
})

describe('adding a block places it and wires the obvious edge', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('does not drop the second block on top of the first', () => {
    const { addNode } = useFlowStore.getState()
    addNode('input')
    addNode('agent')

    const [input, agent] = useFlowStore.getState().nodes
    // A card is 190px wide. Two clicks used to produce two boxes 60px apart, which is a pile
    // the user has to drag apart before they can read either of them.
    expect(agent.position.x - input.position.x).toBeGreaterThanOrEqual(190)
  })

  it('wires an input into the agent it was obviously added for', () => {
    const { addNode } = useFlowStore.getState()
    addNode('input')
    addNode('agent')

    const [input, agent] = useFlowStore.getState().nodes
    const { edges } = useFlowStore.getState()
    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({ source: input.id, target: agent.id })
  })

  it('hangs a capability under the agent, pointing at it', () => {
    const { addNode } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')

    const [agent, mcp] = useFlowStore.getState().nodes
    // A server feeds an agent, so the wire runs that way and the box sits below rather than
    // further along the chain: the drawing reads left to right, capabilities hang underneath.
    expect(useFlowStore.getState().edges[0]).toMatchObject({ source: mcp.id, target: agent.id })
    expect(mcp.position.y).toBeGreaterThan(agent.position.y)
  })

  it('draws nothing when there is nothing it could legally attach to', () => {
    const { addNode } = useFlowStore.getState()
    addNode('mcp')
    addNode('knowledge')

    // Two capabilities and no consumer: neither can feed the other, and inventing a wire the
    // canvas would refuse is worse than leaving them apart.
    expect(useFlowStore.getState().edges).toHaveLength(0)
  })

  it('leaves a dropped block exactly where it was dropped', () => {
    const { addNode } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp', { x: 640, y: 480 })

    const dropped = useFlowStore.getState().nodes[1]
    // Nudging a dropped node moves it out from under the cursor that placed it.
    expect(dropped.position).toEqual({ x: 640, y: 480 })
  })
})
