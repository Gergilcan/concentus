// ---- Run + event contracts (mirror the backend records) ----

export type RunStatus = 'STARTING' | 'RUNNING' | 'IDLE' | 'ERROR' | 'TERMINATED'

export interface RunSummary {
  id: string
  flowId: string | null
  flowName: string | null
  mode: string
  status: RunStatus
  createdAt: number
  sessionId?: string | null
  agentIds?: string[] | null
  error?: string | null
  trigger?: string | null
}

export type RunEventType = 'system' | 'status' | 'agent_message' | 'tool_use' | 'error'

export interface RunEvent {
  type: RunEventType
  text: string
  agent?: string | null
  ts: number
}

export interface RunDetail {
  run: RunSummary
  events: RunEvent[]
}

// ---- Flow / node data (canvas) ----
// `type` aliases (not interfaces) so they satisfy React Flow's
// `Record<string, unknown>` node-data constraint.

export type NodeKind = 'agent' | 'mcp' | 'repo' | 'sql' | 'input'

export type InputNodeData = {
  kind: 'input'
  mode: 'manual' | 'prompt' | 'cron' | 'webhook'
  prompt: string
  cron: string
  secret: string
}

export type AgentNodeData = {
  kind: 'agent'
  name: string
  role: 'coordinator' | 'subagent'
  model: string
  description?: string
  systemPrompt: string
  maxTokens: number
  effort: string
}

export type McpNodeData = {
  kind: 'mcp'
  name: string
  url: string
  tokenEnv: string
}

export type RepoNodeData = {
  kind: 'repo'
  provider: 'github' | 'gitlab'
  url: string
  tokenEnv: string
  mountPath: string
  branch: string
}

export type SqlNodeData = {
  kind: 'sql'
  label: string
  jdbcUrl: string
  username: string
  passwordEnv: string
  query: string
  maxRows: number
}

export type AppNodeData = AgentNodeData | McpNodeData | RepoNodeData | SqlNodeData | InputNodeData

export interface SqlPreview {
  columns: string[]
  rows: string[][]
  rowCount: number
  truncated: boolean
}

// ---- Backend flow shape (persisted) ----

export interface BackendFlowNode {
  id: string
  type: NodeKind
  role?: string | null
  data: Record<string, unknown>
}

export interface BackendFlowEdge {
  id: string
  source: string
  target: string
}

export interface BackendFlow {
  id?: string
  name: string
  mode: 'managed' | 'local'
  nodes: BackendFlowNode[]
  edges: BackendFlowEdge[]
}

export interface RagStatus {
  enabled: boolean
  message: string
  sources: unknown[]
}

export interface AuthStatus {
  mode: string
  source: 'api-key' | 'auth-token' | 'local' | 'none'
  authenticated: boolean
  detail?: string | null
  hint?: string | null
}

// `type` aliases (not interfaces) so they satisfy the CrudPanel `Record<string, unknown>` constraint.
export type LibraryAgent = {
  id?: string
  name: string
  model: string
  effort: string
  maxTokens: number
  description?: string
  systemPrompt: string
}

export type DatabaseDef = {
  id?: string
  label: string
  jdbcUrl: string
  username: string
  passwordEnv: string
}

export type McpDef = {
  id?: string
  name: string
  url: string
  tokenEnv: string
}

export interface McpServerInfo {
  name: string
  url: string
  status: string
}
