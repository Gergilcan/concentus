import type { Node } from '@xyflow/react'
import type {
  AgentNodeData,
  KnowledgeNodeData,
  McpNodeData,
  RepoNodeData,
  SqlNodeData,
} from '../api/types.ts'
import { AgentNode } from './nodes/AgentNode.tsx'
import { InputNode } from './nodes/InputNode.tsx'
import { McpNode } from './nodes/McpNode.tsx'
import { RepoNode } from './nodes/RepoNode.tsx'
import { ApiNode } from './nodes/ApiNode.tsx'
import { KnowledgeNode } from './nodes/KnowledgeNode.tsx'
import { SqlNode } from './nodes/SqlNode.tsx'

export type AgentRFNode = Node<AgentNodeData, 'agent'>
export type McpRFNode = Node<McpNodeData, 'mcp'>
export type RepoRFNode = Node<RepoNodeData, 'repo'>
export type SqlRFNode = Node<SqlNodeData, 'sql'>
export type KnowledgeRFNode = Node<KnowledgeNodeData, 'knowledge'>
export type ApiRFNode = Node<import('../api/types.ts').ApiNodeData, 'api'>

export const nodeTypes = {
  agent: AgentNode,
  input: InputNode,
  mcp: McpNode,
  repo: RepoNode,
  sql: SqlNode,
  knowledge: KnowledgeNode,
  api: ApiNode,
}
