import type { Node } from '@xyflow/react'
import type {
  AgentNodeData,
  KnowledgeNodeData,
  McpNodeData,
  MergeNodeData,
  RepoNodeData,
  SqlNodeData,
  VerifierNodeData,
} from '../api/types.ts'
import { AgentNode } from './nodes/AgentNode.tsx'
import { InputNode } from './nodes/InputNode.tsx'
import { McpNode } from './nodes/McpNode.tsx'
import { MergeNode } from './nodes/MergeNode.tsx'
import { RepoNode } from './nodes/RepoNode.tsx'
import { ApiNode } from './nodes/ApiNode.tsx'
import { KnowledgeNode } from './nodes/KnowledgeNode.tsx'
import { FlowRunNode } from './nodes/FlowRunNode.tsx'
import { SqlNode } from './nodes/SqlNode.tsx'
import { VerifierNode } from './nodes/VerifierNode.tsx'
import { ConditionNode } from './nodes/ConditionNode.tsx'
import { ForEachNode } from './nodes/ForEachNode.tsx'
import { WorkerNode } from './nodes/WorkerNode.tsx'
import { NoteNode } from './nodes/NoteNode.tsx'
import { GroupNode } from './nodes/GroupNode.tsx'

export type AgentRFNode = Node<AgentNodeData, 'agent' | 'coordinator'>
export type McpRFNode = Node<McpNodeData, 'mcp'>
export type RepoRFNode = Node<RepoNodeData, 'repo'>
export type SqlRFNode = Node<SqlNodeData, 'sql'>
export type KnowledgeRFNode = Node<KnowledgeNodeData, 'knowledge'>
export type ApiRFNode = Node<import('../api/types.ts').ApiNodeData, 'api'>
export type FlowRunRFNode = Node<import('../api/types.ts').FlowRunNodeData, 'flow'>
export type MergeRFNode = Node<MergeNodeData, 'merge'>
export type VerifierRFNode = Node<VerifierNodeData, 'verifier'>
export type ConditionRFNode = Node<import('../api/types.ts').ConditionNodeData, 'condition'>
export type ForEachRFNode = Node<import('../api/types.ts').ForEachNodeData, 'foreach'>
export type NoteRFNode = Node<import('../api/types.ts').NoteNodeData, 'note'>
export type GroupRFNode = Node<import('../api/types.ts').GroupNodeData, 'group'>

export const nodeTypes = {
  agent: AgentNode,
  // The same card, drawn as the flow's lead: one component, because the two differ in what they
  // are for and not in what they show.
  coordinator: AgentNode,
  input: InputNode,
  mcp: McpNode,
  repo: RepoNode,
  sql: SqlNode,
  knowledge: KnowledgeNode,
  api: ApiNode,
  flow: FlowRunNode,
  merge: MergeNode,
  verifier: VerifierNode,
  condition: ConditionNode,
  foreach: ForEachNode,
  // Annotations: drawn for the people reading the canvas, invisible to the run.
  note: NoteNode,
  group: GroupNode,
  // Not a palette kind: worker boxes are synthesized from the run report (plan-born workers).
  worker: WorkerNode,
}
