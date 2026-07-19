import { describe, expect, it } from 'vitest'
import { AgentNode } from './nodes/AgentNode.tsx'
import { InputNode } from './nodes/InputNode.tsx'
import { McpNode } from './nodes/McpNode.tsx'
import { RepoNode } from './nodes/RepoNode.tsx'
import { SqlNode } from './nodes/SqlNode.tsx'
import { nodeTypes } from './nodeTypes.ts'

// React Flow looks up a node's renderer by its `type` string in this map — a typo or a
// missing/extra key silently breaks rendering for that node kind (falls back to the
// default renderer instead of erroring), so pin the exact key set and each mapping.
describe('nodeTypes', () => {
  it('has exactly the five expected node-kind keys, no more, no fewer', () => {
    expect(Object.keys(nodeTypes).sort()).toEqual(['agent', 'input', 'mcp', 'repo', 'sql'])
  })

  it('maps each key to the matching imported component', () => {
    expect(nodeTypes.agent).toBe(AgentNode)
    expect(nodeTypes.input).toBe(InputNode)
    expect(nodeTypes.mcp).toBe(McpNode)
    expect(nodeTypes.repo).toBe(RepoNode)
    expect(nodeTypes.sql).toBe(SqlNode)
  })
})
