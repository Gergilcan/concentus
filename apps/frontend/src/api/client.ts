import type {
  AuthStatus,
  BackendFlow,
  DatabaseDef,
  LibraryAgent,
  McpDef,
  McpServerInfo,
  RagStatus,
  RunDetail,
  RunEvent,
  RunSummary,
  SqlPreview,
} from './types.ts'

export interface SqlSourceInput {
  label?: string
  jdbcUrl: string
  username?: string
  passwordEnv?: string
  query: string
  maxRows?: number
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = (await res.json()) as { error?: string }
      if (body?.error) message = body.error
    } catch {
      /* non-JSON error body */
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  // flows
  listFlows: () => req<BackendFlow[]>('/flows'),
  getFlow: (id: string) => req<BackendFlow>(`/flows/${id}`),
  saveFlow: (flow: BackendFlow) =>
    req<BackendFlow>('/flows', { method: 'POST', body: JSON.stringify(flow) }),
  deleteFlow: (id: string) => req<void>(`/flows/${id}`, { method: 'DELETE' }),
  runSavedFlow: (id: string) => req<RunSummary>(`/flows/${id}/run`, { method: 'POST' }),

  // runs
  listRuns: () => req<RunSummary[]>('/runs'),
  getRun: (id: string) => req<RunDetail>(`/runs/${id}`),
  startRun: (flow: BackendFlow) =>
    req<RunSummary>('/runs', { method: 'POST', body: JSON.stringify(flow) }),
  sendCommand: (runId: string, text: string) =>
    req<void>(`/runs/${runId}/commands`, { method: 'POST', body: JSON.stringify({ text }) }),
  stopRun: (runId: string) => req<void>(`/runs/${runId}/stop`, { method: 'POST' }),

  // agent library
  listAgents: () => req<LibraryAgent[]>('/agents'),
  saveAgent: (a: LibraryAgent) =>
    req<LibraryAgent>('/agents', { method: 'POST', body: JSON.stringify(a) }),
  deleteAgent: (id: string) => req<void>(`/agents/${id}`, { method: 'DELETE' }),

  // database definitions
  listDatabases: () => req<DatabaseDef[]>('/databases'),
  saveDatabase: (d: DatabaseDef) =>
    req<DatabaseDef>('/databases', { method: 'POST', body: JSON.stringify(d) }),
  deleteDatabase: (id: string) => req<void>(`/databases/${id}`, { method: 'DELETE' }),

  // mcp server definitions
  listMcpDefs: () => req<McpDef[]>('/mcp-defs'),
  saveMcpDef: (d: McpDef) => req<McpDef>('/mcp-defs', { method: 'POST', body: JSON.stringify(d) }),
  deleteMcpDef: (id: string) => req<void>(`/mcp-defs/${id}`, { method: 'DELETE' }),

  // mcp servers (Claude Code list)
  listMcpServers: () => req<McpServerInfo[]>('/mcp/servers'),
  addMcpServer: (source: { name: string; url: string; tokenEnv?: string }) =>
    req<{ name: string; status: string }>('/mcp/servers', {
      method: 'POST',
      body: JSON.stringify(source),
    }),
  loginMcpServer: (name: string) =>
    req<{ name: string; status: string }>('/mcp/servers/login', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),
  removeMcpServer: (name: string) =>
    req<{ name: string; status: string }>('/mcp/servers/remove', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  // auth
  authStatus: () => req<AuthStatus>('/auth/status'),

  // rag
  ragStatus: () => req<RagStatus>('/rag/status'),
  ragPreview: (source: SqlSourceInput) =>
    req<SqlPreview>('/rag/preview', { method: 'POST', body: JSON.stringify(source) }),
}

/** Opens a live output stream for a run. Returns the socket so callers can close it. */
export function openRunSocket(runId: string, onEvent: (e: RunEvent) => void): WebSocket {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const ws = new WebSocket(`${proto}://${location.host}/ws/runs?runId=${encodeURIComponent(runId)}`)
  ws.onmessage = (msg) => {
    try {
      onEvent(JSON.parse(msg.data) as RunEvent)
    } catch {
      /* ignore malformed frame */
    }
  }
  return ws
}
