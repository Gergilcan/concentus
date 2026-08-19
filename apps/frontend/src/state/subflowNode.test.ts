import { beforeEach, describe, expect, it } from 'vitest'
import { useFlowStore } from './store.ts'
import type { FlowRunNodeData } from '../api/types.ts'

/**
 * The node that runs another flow.
 *
 * Two drawings, one kind. Wired to an agent it is a capability the agent may reach for; left
 * terminal it is a hand-off that fires when the run finishes. The mode is what tells them apart,
 * so it has to survive the round trip to the backend intact — a lost mode would turn a tool the
 * agent chooses into a chain that fires by itself.
 */
describe('flow nodes (sub-flows)', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('starts as a capability that waits for the child, which is what a caller usually wants', () => {
    useFlowStore.getState().addNode('flow')

    const node = useFlowStore.getState().nodes.find((n) => n.type === 'flow')!
    const data = node.data as FlowRunNodeData
    expect(data.mode).toBe('tool')
    expect(data.waitForResult).toBe(true)
    expect(data.flowId).toBe('')
  })

  it('survives the round trip through the backend shape', () => {
    const { addNode, updateNodeData, toBackendFlow, loadBackendFlow } = useFlowStore.getState()
    addNode('flow')
    const id = useFlowStore.getState().nodes.find((n) => n.type === 'flow')!.id
    updateNodeData(id, { label: 'Weekly report', flowId: 'flow_report', mode: 'after', waitForResult: false })

    const saved = toBackendFlow()
    expect(saved.nodes.find((n) => n.id === id)!.data).toMatchObject({
      label: 'Weekly report',
      flowId: 'flow_report',
      mode: 'after',
      waitForResult: false,
    })

    loadBackendFlow(saved)
    const reloaded = useFlowStore.getState().nodes.find((n) => n.id === id)!.data as FlowRunNodeData
    expect(reloaded.mode).toBe('after')
    expect(reloaded.waitForResult).toBe(false)
  })
})
