import { describe, expect, it } from 'vitest'
import { AgentNode } from './nodes/AgentNode.tsx'
import { ApiNode } from './nodes/ApiNode.tsx'
import { ConditionNode } from './nodes/ConditionNode.tsx'
import { ForEachNode } from './nodes/ForEachNode.tsx'
import { InputNode } from './nodes/InputNode.tsx'
import { McpNode } from './nodes/McpNode.tsx'
import { KnowledgeNode } from './nodes/KnowledgeNode.tsx'
import { FlowRunNode } from './nodes/FlowRunNode.tsx'
import { MergeNode } from './nodes/MergeNode.tsx'
import { RepoNode } from './nodes/RepoNode.tsx'
import { SqlNode } from './nodes/SqlNode.tsx'
import { VerifierNode } from './nodes/VerifierNode.tsx'
import { WorkerNode } from './nodes/WorkerNode.tsx'
import { nodeTypes } from './nodeTypes.ts'

// React Flow looks up a node's renderer by its `type` string in this map — a typo or a
// missing/extra key silently breaks rendering for that node kind (falls back to the
// default renderer instead of erroring), so pin the exact key set and each mapping.
// 'worker' is deliberately in the registry but NOT in NodeKind: its boxes are synthesized
// from the run report and must never be addable from the palette or saved with a flow.
describe('nodeTypes', () => {
  it('has exactly the fourteen expected node-kind keys, no more, no fewer', () => {
    expect(Object.keys(nodeTypes).sort()).toEqual(['agent', 'api', 'condition', 'coordinator', 'flow', 'foreach', 'input', 'knowledge', 'mcp', 'merge', 'repo', 'sql', 'verifier', 'worker'])
  })

  it('maps each key to the matching imported component', () => {
    expect(nodeTypes.agent).toBe(AgentNode)
    // The lead is the same card: one component, two kinds.
    expect(nodeTypes.coordinator).toBe(AgentNode)
    expect(nodeTypes.input).toBe(InputNode)
    expect(nodeTypes.mcp).toBe(McpNode)
    expect(nodeTypes.repo).toBe(RepoNode)
    expect(nodeTypes.knowledge).toBe(KnowledgeNode)
    expect(nodeTypes.sql).toBe(SqlNode)
    expect(nodeTypes.api).toBe(ApiNode)
    expect(nodeTypes.flow).toBe(FlowRunNode)
    expect(nodeTypes.merge).toBe(MergeNode)
    expect(nodeTypes.verifier).toBe(VerifierNode)
    expect(nodeTypes.condition).toBe(ConditionNode)
    expect(nodeTypes.foreach).toBe(ForEachNode)
    expect(nodeTypes.worker).toBe(WorkerNode)
  })
})
