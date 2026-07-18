import type {
  AuthStatus,
  BackendFlow,
  DatabaseDef,
  LibraryAgent,
  FlowVersionInfo,
  McpDef,
  McpServerInfo,
  NodeExecReport,
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

const DEFAULT_TIMEOUT_MS = 30_000

async function req<T>(path: string, init?: RequestInit, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
      signal: controller.signal,
    })
  } catch (e) {
    if (controller.signal.aborted) {
      throw new Error(`Request to ${path} timed out after ${timeoutMs}ms`, { cause: e })
    }
    throw e
  } finally {
    clearTimeout(timer)
  }
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
  listFlowVersions: (id: string) => req<FlowVersionInfo[]>(`/flows/${id}/versions`),
  restoreFlowVersion: (id: string, version: number) =>
    req<BackendFlow>(`/flows/${id}/versions/${version}/restore`, { method: 'POST' }),

  // runs
  listRuns: () => req<RunSummary[]>('/runs'),
  getRun: (id: string) => req<RunDetail>(`/runs/${id}`),
  getRunNodes: (id: string) => req<NodeExecReport>(`/runs/${id}/nodes`),
  /** The flow snapshot this run executed (works for ad-hoc runs and edited/deleted flows). */
  getRunFlow: (id: string) => req<BackendFlow>(`/runs/${id}/flow`),
  startRun: (flow: BackendFlow) =>
    req<RunSummary>('/runs', { method: 'POST', body: JSON.stringify(flow) }),
  sendCommand: (runId: string, text: string) =>
    req<void>(`/runs/${runId}/commands`, { method: 'POST', body: JSON.stringify({ text }) }),
  stopRun: (runId: string) => req<void>(`/runs/${runId}/stop`, { method: 'POST' }),
  retryRun: (runId: string) => req<RunSummary>(`/runs/${runId}/retry`, { method: 'POST' }),

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

export type RunSocketStatus = 'connecting' | 'open' | 'reconnecting' | 'disconnected'

export interface RunSocketHandle {
  /** Closes the socket and cancels any pending reconnect attempt. */
  close(): void
}

const WS_RECONNECT_BASE_MS = 1_000
const WS_RECONNECT_MAX_MS = 30_000

/**
 * Opens a live output stream for a run.
 *
 * Reconnects automatically on drop/error using capped exponential backoff
 * (starts at 1s, doubles each attempt, capped at 30s, +/-25% jitter).
 * Reconnection stops once a terminal "status"/"terminated" event is observed
 * on the stream, or once `close()` is called (e.g. on consumer unmount).
 * `onStatus` is notified of connection lifecycle changes so callers can
 * surface reconnecting/disconnected state instead of hanging silently.
 */
export function openRunSocket(
  runId: string,
  onEvent: (e: RunEvent) => void,
  onStatus?: (status: RunSocketStatus) => void,
): RunSocketHandle {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/ws/runs?runId=${encodeURIComponent(runId)}`

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let attempt = 0
  let stopped = false
  let terminal = false

  const connect = () => {
    onStatus?.(attempt === 0 ? 'connecting' : 'reconnecting')
    const socket = new WebSocket(url)
    ws = socket

    socket.onopen = () => {
      attempt = 0
      onStatus?.('open')
    }

    socket.onmessage = (msg) => {
      try {
        const event = JSON.parse(msg.data) as RunEvent
        // A run also ends terminally on a reported error (backend emits `type: 'error'`
        // without a follow-up "status"/"terminated" event in that path) — treat both as
        // terminal, matching RunStatus treating ERROR and TERMINATED alike.
        if (event.type === 'error' || (event.type === 'status' && event.text === 'terminated')) {
          terminal = true
        }
        onEvent(event)
      } catch {
        /* ignore malformed frame */
      }
    }

    socket.onerror = () => {
      /* onclose fires right after; reconnect scheduling happens there */
    }

    socket.onclose = () => {
      if (stopped || terminal) {
        onStatus?.('disconnected')
        return
      }
      onStatus?.('reconnecting')
      const delay = Math.min(WS_RECONNECT_BASE_MS * 2 ** attempt, WS_RECONNECT_MAX_MS)
      const jitter = delay * (0.75 + Math.random() * 0.5)
      attempt += 1
      reconnectTimer = setTimeout(connect, jitter)
    }
  }

  connect()

  return {
    close() {
      stopped = true
      if (reconnectTimer) clearTimeout(reconnectTimer)
      ws?.close()
    },
  }
}
