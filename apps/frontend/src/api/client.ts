import type {
  AuthStatus,
  Credential,
  CredentialStatus,
  MailDeviceCode,
  MailOAuthDefaults,
  McpOAuthStart,
  McpToolList,
  McpOAuthStatus,
  MailStatus,
  MailSignInResult,
  BackendFlow,
  DatabaseDef,
  KnowledgeDef,
  KnowledgeDoc,
  KnowledgeHit,
  LibraryAgent,
  FlowVersionInfo,
  McpDef,
  McpCapabilities,
  McpServerInfo,
  NodeExecReport,
  ModelCatalog,
  RagStatus,
  RemoteRepoList,
  RunDetail,
  RunEvent,
  RunSummary,
  StorageConfig,
  StorageDraft,
  SessionInfo,
  SignedInUser,
  SqlPreview,
} from './types.ts'

export interface SqlSourceInput {
  label?: string
  jdbcUrl: string
  username?: string
  credentialId?: string
  query: string
  maxRows?: number
}

const DEFAULT_TIMEOUT_MS = 30_000

/**
 * Reads the CSRF token the backend sets as a readable cookie.
 *
 * Spring Security issues `XSRF-TOKEN` and expects it echoed in a header on every state-changing
 * request. A cookie alone would not prove intent — a third-party page can cause the browser to
 * send cookies, but cannot read them, so it cannot reproduce the header.
 */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function req<T>(path: string, init?: RequestInit, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((init?.headers as Record<string, string>) ?? {}),
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      ...init,
      headers,
      // The session lives in a cookie; without this a cross-origin dev server would drop it.
      credentials: 'same-origin',
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
  // Where the app keeps its own data — not to be confused with /databases below, which is the
  // databases an agent reads as RAG context.
  getStorage: () => req<StorageConfig>('/storage'),
  saveStorage: (s: StorageDraft) =>
    req<StorageConfig>('/storage', { method: 'PUT', body: JSON.stringify(s) }),
  testStorage: (s: StorageDraft) =>
    req<{ ok: boolean; detail: string }>('/storage/test', {
      method: 'POST',
      body: JSON.stringify(s),
    }),

  // Knowledge bases: document collections agents retrieve from. The upload is multipart, so it
  // bypasses req()'s JSON defaults.
  listKnowledge: () => req<KnowledgeDef[]>('/knowledge'),
  knowledgeStatus: () => req<{ semantic: boolean }>('/knowledge/status'),
  saveKnowledge: (k: KnowledgeDef) =>
    req<KnowledgeDef>('/knowledge', { method: 'POST', body: JSON.stringify(k) }),
  deleteKnowledge: (id: string) => req<void>(`/knowledge/${id}`, { method: 'DELETE' }),
  knowledgeDocs: (id: string) => req<KnowledgeDoc[]>(`/knowledge/${id}/documents`),
  uploadKnowledgeDoc: async (id: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    // Straight fetch rather than req(): multipart must NOT carry the JSON content type — the
    // browser sets the boundary itself — but the CSRF header is still required on a POST.
    const headers: Record<string, string> = {}
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
    const res = await fetch(`/api/knowledge/${id}/documents`, {
      method: 'POST',
      body: form,
      headers,
      credentials: 'same-origin',
    })
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`
      try {
        const body = (await res.json()) as { error?: string }
        if (body?.error) message = body.error
      } catch { /* non-JSON error body */ }
      throw new Error(message)
    }
    return (await res.json()) as { docName: string; chunks: number; embedded: boolean; detail: string }
  },
  deleteKnowledgeDoc: (id: string, docName: string) =>
    req<void>(`/knowledge/${id}/documents/${encodeURIComponent(docName)}`, { method: 'DELETE' }),
  searchKnowledge: (id: string, query: string, topK = 5) =>
    req<KnowledgeHit[]>(`/knowledge/${id}/search`, {
      method: 'POST',
      body: JSON.stringify({ query, topK }),
    }),

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
  mcpCapabilities: () => req<McpCapabilities>('/mcp/capabilities'),
  addMcpServer: (source: { name: string; url: string; credentialId?: string; authHeader?: string }) =>
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

  // auth (which Claude credentials the backend runs on — unrelated to signing in)
  authStatus: () => req<AuthStatus>('/auth/status'),

  // account / sign-in
  session: () => req<SessionInfo>('/account/session'),
  signIn: (email: string, password: string) =>
    req<SignedInUser>('/account/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  signOut: () => req<void>('/account/logout', { method: 'POST' }),
  changePassword: (currentPassword: string, newPassword: string) =>
    req<void>('/account/password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword }),
    }),

  /** Per-model rates for the cost estimate, plus which execution backends can run right now. */
  listModels: () => req<ModelCatalog>('/models'),

  // stored credentials (write-only: nothing here ever returns a secret)
  credentialStatus: () => req<CredentialStatus>('/credentials/status'),
  listCredentials: () => req<Credential[]>('/credentials'),
  createCredential: (label: string, kind: string, value: string) =>
    req<Credential>('/credentials', { method: 'POST', body: JSON.stringify({ label, kind, value }) }),
  /** A blank `value` leaves the stored secret untouched — that is what makes the masked field safe. */
  updateCredential: (id: string, label: string, value: string) =>
    req<Credential>(`/credentials/${id}`, { method: 'PUT', body: JSON.stringify({ label, value }) }),
  deleteCredential: (id: string) => req<void>(`/credentials/${id}`, { method: 'DELETE' }),

  // Mail trigger health. A poller succeeds by doing nothing most of the time, so it needs to be
  // asked rather than waited on.
  mailStatus: (flowId: string) => req<MailStatus>(`/mail/status/${flowId}`),
  pollMailNow: (flowId: string) => req<MailStatus>(`/mail/poll/${flowId}`, { method: 'POST' }),

  /** The deployment's Entra app registration, so a node need not ask for it. Not secrets. */
  mailSignInDefaults: () => req<MailOAuthDefaults>('/mail/oauth/microsoft/defaults'),

  // MCP servers that use OAuth. The claude CLI keeps its own authorizations, so this app needs
  // its own grant for any backend that is not the CLI.
  /** What a server can do, fetched with the same credential a run uses. */
  listMcpTools: (url: string, credentialId?: string) =>
    req<McpToolList>(
      '/mcp/tools?url=' +
        encodeURIComponent(url) +
        (credentialId ? '&credentialId=' + encodeURIComponent(credentialId) : ''),
    ),

  mcpOAuthStatus: (url: string) =>
    req<McpOAuthStatus>('/mcp/oauth/status?url=' + encodeURIComponent(url)),
  startMcpOAuth: (url: string) =>
    req<McpOAuthStart>('/mcp/oauth/start', { method: 'POST', body: JSON.stringify({ url }) }),
  disconnectMcpOAuth: (url: string) =>
    req<McpOAuthStatus>('/mcp/oauth/disconnect', { method: 'POST', body: JSON.stringify({ url }) }),

  // Microsoft 365 mailbox sign-in (OAuth2 device code). Two calls because the flow is
  // asynchronous: `start` yields a code for a person to enter, `complete` is polled until they do.
  startMailSignIn: (tenantId: string, clientId: string) =>
    req<MailDeviceCode>('/mail/oauth/microsoft/start', {
      method: 'POST',
      body: JSON.stringify({ tenantId, clientId }),
    }),
  completeMailSignIn: (body: {
    tenantId: string
    clientId: string
    deviceCode: string
    label?: string
    credentialId?: string
  }) =>
    req<MailSignInResult>('/mail/oauth/microsoft/complete', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  /** Lists a GitHub organization's or GitLab group's repositories. The token never crosses the wire. */
  listGroupRepos: (provider: string, group: string, credentialId?: string, baseUrl?: string) =>
    req<RemoteRepoList>(
      '/git/repos?provider=' +
        encodeURIComponent(provider) +
        '&group=' +
        encodeURIComponent(group) +
        (credentialId ? '&credentialId=' + encodeURIComponent(credentialId) : '') +
        (baseUrl ? '&baseUrl=' + encodeURIComponent(baseUrl) : ''),
    ),

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
