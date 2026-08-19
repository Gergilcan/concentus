import { beforeEach, describe, expect, it } from 'vitest'
import { useFlowStore } from './store.ts'
import type { FlowRunNodeData } from '../api/types.ts'

/**
 * The node that runs another flow.
 *
 * It carries which flow and whether to wait — and deliberately not WHEN it runs, because the
 * canvas already says that: wired into an agent it runs before, wired out of one it runs after.
 * A field for it would be the drawing repeated in words, free to disagree with the picture.
 */
describe('flow nodes (sub-flows)', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('starts waiting for the child, which is what a caller usually wants', () => {
    useFlowStore.getState().addNode('flow')

    const node = useFlowStore.getState().nodes.find((n) => n.type === 'flow')!
    const data = node.data as FlowRunNodeData
    expect(data.waitForResult).toBe(true)
    expect(data.flowId).toBe('')
    expect(data).not.toHaveProperty('mode')
  })

  it('survives the round trip through the backend shape', () => {
    const { addNode, updateNodeData, toBackendFlow, loadBackendFlow } = useFlowStore.getState()
    addNode('flow')
    const id = useFlowStore.getState().nodes.find((n) => n.type === 'flow')!.id
    updateNodeData(id, { label: 'Weekly report', flowId: 'flow_report', waitForResult: false })

    const saved = toBackendFlow()
    expect(saved.nodes.find((n) => n.id === id)!.data).toMatchObject({
      label: 'Weekly report',
      flowId: 'flow_report',
      waitForResult: false,
    })

    loadBackendFlow(saved)
    const reloaded = useFlowStore.getState().nodes.find((n) => n.id === id)!.data as FlowRunNodeData
    expect(reloaded.label).toBe('Weekly report')
    expect(reloaded.waitForResult).toBe(false)
  })

  // A node saved by an older version said WHEN it runs in a field, and the compiler still honours
  // it rather than silently changing what somebody's flow does. So the field has to survive a
  // load-and-save: dropping it here would change the flow's behaviour the next time it is opened.
  it('carries a node saved by an older version through unchanged', () => {
    const { loadBackendFlow, toBackendFlow } = useFlowStore.getState()

    loadBackendFlow({
      id: 'flow_old',
      name: 'Old',
      mode: 'local',
      nodes: [
        { id: 'agent-1', type: 'agent', role: 'coordinator', data: { name: 'Lead' } },
        { id: 'sub-1', type: 'flow', role: null, data: { label: 'child', flowId: 'flow_b', mode: 'tool', waitForResult: true } },
      ],
      edges: [{ id: 'e1', source: 'agent-1', target: 'sub-1' }],
    })

    const loaded = useFlowStore.getState().nodes.find((n) => n.id === 'sub-1')!.data as FlowRunNodeData
    expect(loaded.mode).toBe('tool')
    expect(toBackendFlow().nodes.find((n) => n.id === 'sub-1')!.data).toMatchObject({ mode: 'tool' })
  })

  it('lets the wiring take over once the old field is cleared', () => {
    const { loadBackendFlow, updateNodeData, toBackendFlow } = useFlowStore.getState()
    loadBackendFlow({
      id: 'flow_old',
      name: 'Old',
      mode: 'local',
      nodes: [
        { id: 'sub-1', type: 'flow', role: null, data: { label: 'child', flowId: 'flow_b', mode: 'after', waitForResult: true } },
      ],
      edges: [],
    })

    updateNodeData('sub-1', { mode: undefined })

    expect(toBackendFlow().nodes.find((n) => n.id === 'sub-1')!.data.mode).toBeUndefined()
  })

  it('keeps both wirings when the same node is drawn on both sides', () => {
    // Before AND after: unusual, but the graph can express it, so it must survive a save.
    const { addNode, onConnect, toBackendFlow } = useFlowStore.getState()
    addNode('agent')
    addNode('flow')
    const [agent, flow] = useFlowStore.getState().nodes

    onConnect({ source: flow.id, target: agent.id, sourceHandle: null, targetHandle: null })
    onConnect({ source: agent.id, target: flow.id, sourceHandle: null, targetHandle: null })

    const saved = toBackendFlow()
    expect(saved.edges).toHaveLength(2)
  })
})
