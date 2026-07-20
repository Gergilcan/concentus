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
  totalInputTokens?: number
  totalOutputTokens?: number
  estimatedCostUsd?: number
}

export type RunEventType = 'system' | 'status' | 'agent_message' | 'tool_use' | 'error'

export interface RunEvent {
  type: RunEventType
  text: string
  /** Display name of the agent that produced this line. */
  agent?: string | null
  /** Canvas node id of that agent — unique even when two agents share a name. */
  agentId?: string | null
  ts: number
}

export interface RunDetail {
  run: RunSummary
  events: RunEvent[]
}

export type NodeExecStatus = 'pending' | 'running' | 'passed' | 'failed'

export interface NodeExec {
  nodeId: string
  kind: string
  label: string
  status: NodeExecStatus
  input?: string | null
  output?: string | null
  format?: 'text' | 'markdown' | 'table'
  columns?: string[] | null
  rows?: string[][] | null
  error?: string | null
  /** Fresh (uncached) input tokens. */
  inputTokens: number
  outputTokens: number
  /** Prompt tokens served from cache (~0.1x price) / written to it (~1.25x). */
  cacheReadTokens?: number
  cacheWriteTokens?: number
  /** USD estimate for this block, priced at its own model's rate. */
  estimatedCostUsd?: number
  /** Model this block ran on. */
  model?: string | null
  startedAt: number
  endedAt: number
}

/** USD per million tokens. */
export interface ModelRate {
  input: number
  output: number
}

export interface ProvidersResponse {
  /** Providers with a credential configured, so usable right now. */
  configured: string[]
  /** Rates for models named in `pricing.models`, keyed by model id. */
  pricing: Record<string, ModelRate>
  /** Rate applied to any model not listed above. */
  fallback: ModelRate
}

export interface NodeExecReport {
  nodes: NodeExec[]
  totalInputTokens: number
  totalOutputTokens: number
  /** Sum of the per-block estimates. */
  totalCostUsd?: number
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
  /** Secret issued by the provider; we verify against it, we never mint it. */
  secret: string
  /** Header (or query param) carrying the signature/token, e.g. `Linear-Signature`. */
  authParam: string
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
  // Optional: flows saved before these existed simply omit them.
  /** Host folders this agent should treat as its source of truth (CLI `--add-dir`). */
  contextFolders?: string[]
  /** Path to an existing CLAUDE.md, or a folder containing one, to load as context. */
  claudeMdPath?: string
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
  /** false pauses scheduled (cron) execution without removing the trigger */
  enabled?: boolean
  tags?: string[]
  favorite?: boolean
  /** URL POSTed when a run of this flow fails (Slack-compatible payload) */
  notifyWebhook?: string
}

export interface FlowVersionInfo {
  version: number
  name: string
  createdAt: number
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
