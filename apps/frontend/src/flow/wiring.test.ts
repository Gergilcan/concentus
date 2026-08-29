import { beforeEach, describe, expect, it } from 'vitest'
import { canConnect, wiringRoleOf } from './wiring.ts'
import { useFlowStore } from '../state/store.ts'

/**
 * What a wire is allowed to mean.
 *
 * A capability feeds an agent — an MCP server, a knowledge base, a query. Nothing feeds a
 * capability, so an arrow pointing back at one says nothing true, and the canvas should refuse to
 * draw it rather than accept a picture that lies about the run.
 *
 * Sub-flows are the exception that proves the rule: they are the one kind that reads BOTH ways,
 * and that is exactly why they no longer need a dropdown saying which way they meant.
 */
describe('wiring rules', () => {
  it('capabilities feed agents and nothing feeds a capability', () => {
    for (const kind of ['mcp', 'repo', 'sql', 'knowledge', 'api'] as const) {
      expect(wiringRoleOf(kind)).toBe('feeds')
      expect(canConnect(kind, 'agent')).toBe(true)
      expect(canConnect('agent', kind)).toBe(false)
    }
  })

  it('a trigger only ever feeds', () => {
    expect(canConnect('input', 'agent')).toBe(true)
    expect(canConnect('agent', 'input')).toBe(false)
  })

  it('a sub-flow reads both ways, which is how it says when it runs', () => {
    expect(wiringRoleOf('flow')).toBe('both')
    expect(canConnect('flow', 'agent')).toBe(true)
    expect(canConnect('agent', 'flow')).toBe(true)
  })

  it('agents connect to agents, in either direction, as they always have', () => {
    // Delegation is worked out by walking outward from the coordinator, so the arrow's direction
    // has never mattered there — and thousands of saved flows are drawn both ways.
    expect(canConnect('agent', 'agent')).toBe(true)
    expect(canConnect('agent', 'merge')).toBe(true)
    expect(canConnect('verifier', 'agent')).toBe(true)
  })

  it('one capability cannot feed another', () => {
    expect(canConnect('mcp', 'sql')).toBe(false)
    expect(canConnect('knowledge', 'flow')).toBe(false)
  })

  it('a send-mail node only ever receives — from a block or a gate, never from a capability', () => {
    expect(wiringRoleOf('mail')).toBe('receives')
    for (const kind of ['agent', 'coordinator', 'merge', 'verifier', 'condition', 'foreach'] as const) {
      expect(canConnect(kind, 'mail')).toBe(true)
    }
    for (const kind of ['mcp', 'knowledge', 'input', 'flow', 'mail'] as const) {
      expect(canConnect(kind, 'mail')).toBe(false)
    }
    // A mailbox hands nothing back, so a wire leaving the box would be a picture of nothing.
    for (const kind of ['agent', 'coordinator', 'flow', 'condition', 'mcp', 'input'] as const) {
      expect(canConnect('mail', kind)).toBe(false)
    }
  })
})

describe('the canvas refuses a wire the rules reject', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('drops an agent → MCP connection instead of storing it', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes

    onConnect({ source: agent.id, target: mcp.id, sourceHandle: null, targetHandle: null })

    // Scoped to the pair rather than counting every edge: adding a server beside a lone agent
    // now wires the one that means something, and this test is about the one that does not.
    expect(useFlowStore.getState().edges.filter((e) => e.source === agent.id && e.target === mcp.id))
      .toHaveLength(0)
  })

  it('keeps the same pair drawn the way round that means something', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('mcp')
    const [agent, mcp] = useFlowStore.getState().nodes

    onConnect({ source: mcp.id, target: agent.id, sourceHandle: null, targetHandle: null })

    expect(useFlowStore.getState().edges).toHaveLength(1)
  })

  it('keeps agent → mail and drops mail → agent', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('mail')
    const [agent, mail] = useFlowStore.getState().nodes
    // Adding the box beside the agent already drew the wire that means something; this test is
    // about the pair drawn by hand, both ways round.
    useFlowStore.setState({ edges: [] })

    onConnect({ source: mail.id, target: agent.id, sourceHandle: null, targetHandle: null })
    onConnect({ source: agent.id, target: mail.id, sourceHandle: 'error', targetHandle: null })

    const between = useFlowStore.getState().edges.filter((e) => [e.source, e.target].includes(mail.id))
    expect(between.map((e) => [e.source, e.target, e.sourceHandle])).toEqual([[agent.id, mail.id, 'error']])
  })

  it('lets a sub-flow be wired either way', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('agent')
    addNode('flow')
    const [agent, flow] = useFlowStore.getState().nodes

    onConnect({ source: flow.id, target: agent.id, sourceHandle: null, targetHandle: null })
    onConnect({ source: agent.id, target: flow.id, sourceHandle: null, targetHandle: null })

    expect(useFlowStore.getState().edges).toHaveLength(2)
  })
})
