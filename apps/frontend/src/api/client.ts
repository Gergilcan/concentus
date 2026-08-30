import type {
  AuthStatus,
  Credential,
  CredentialStatus,
  OAuthCredentialConfig,
  OAuthCredentialStatus,
  OAuthSignInStart,
  MailDeviceCode,
  MailOAuthDefaults,
  McpOAuthStart,
  McpToolList,
  McpOAuthStatus,
  MailStatus,
  MailSignInResult,
  BackendFlow,
  DatabaseDef,
  DoctorFinding,
  EmbedderStatus,
  EvalCase,
  EvalRun,
  FlowEvalCase,
  FlowEvalCaseInput,
  FlowEvalResult,
  RerankerStatus,
  FlowMemoryView,
  KnowledgeDef,
  KnowledgeDoc,
  KnowledgeHit,
  LibraryAgent,
  LicenseStatus,
  OrgPolicy,
  OrgPolicyView,
  PublishApprovalView,
  FlowVersionInfo,
  GoldenStatus,
  McpDef,
  McpCapabilities,
  McpServerInfo,
  NodeExecReport,
  ModelCatalog,
  RemoteRepoList,
  RuntimeCheck,
  RuntimeInstallPlan,
  RunComparison,
  RunDiff,
  ReplayReport,
  RunEvent,
  RunSummary,
  StorageConfig,
  StorageDraft,
  SessionInfo,
  SignedInUser,
  SettingEntry,
  SignInProvidersList,
  SwitchableAccount,
  SqlPreview,
  ApiOperationView,
  AuditFilters,
  AuditPage,
  AuditStatus,
  AvailablePlugin,
  CreatedServiceAccount,
  FacadeProfile,
  Member,
  Organization,
  PluginsView,
  RetentionReport,
  ServiceAccount,
  ServiceAccountListing,
  SkillCatalogSkill,
  SkillInfo,
  SkillRepo,
  UsageSummary,
  Variable,
  MarketplaceInstallResult,
  MarketplaceItem,
  MarketplaceList,
  MarketplaceListFilters,
  MarketplacePublishBody,
  MarketplacePublishFromBody,
  MarketplaceStatus,
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
 * A request the server refused, carrying the status it refused with.
 *
 * The message alone is what most callers show, but a few need to tell "this does not exist" from
 * "this did not work": a screen polling a record that has been deleted has to stop asking, and it
 * cannot decide that by reading prose.
 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

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

/**
 * `?a=1&b=2` from the entries that have a value at all, so a route with optional parameters reads
 * as a list instead of a chain of `+` and inline conditionals.
 *
 * encodeURIComponent, NOT URLSearchParams: the latter spells a space `+`, which would silently
 * change every URL these routes already produce. Pass `x || undefined` for a parameter that
 * should be omitted when blank rather than sent empty.
 */
function query(params: Record<string, string | undefined>): string {
  const pairs: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) pairs.push(`${key}=${encodeURIComponent(value)}`)
  }
  return pairs.length ? `?${pairs.join('&')}` : ''
}

/** The error a refused response becomes: the backend's own sentence when the body carries one. */
async function refusal(res: Response): Promise<ApiError> {
  let message = `${res.status} ${res.statusText}`
  try {
    const body = (await res.json()) as { error?: string }
    if (body?.error) message = body.error
  } catch {
    /* non-JSON error body */
  }
  return new ApiError(message, res.status)
}

async function req<T>(path: string, init?: RequestInit, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const method = (init?.method ?? 'GET').toUpperCase()
  // FormData must NOT carry a Content-Type here: the browser sets it with the multipart boundary,
  // and forcing application/json is why the upload used to bypass req() entirely — losing the
  // timeout and duplicating the CSRF and error-body handling at its call site.
  const headers: Record<string, string> = {
    ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
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
  if (!res.ok) throw await refusal(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/**
 * A file rather than JSON — a patch, a backup, an audit export — as a Blob for a download link.
 * No timeout, because these can be large. A refusal is surfaced exactly as req() surfaces one, so
 * a sentence worth reading (a tier gate, "nothing changed in this checkout") reaches the button
 * instead of a bare "403 Forbidden".
 */
async function blob(path: string): Promise<Blob> {
  const res = await fetch(`/api${path}`, { credentials: 'same-origin' })
  if (!res.ok) throw await refusal(res)
  return res.blob()
}

/**
 * The URL an external service must call to trigger a flow.
 *
 * Lives here because this file owns every other route: it was the one `/api/...` literal outside
 * this module, and it is the one users copy-paste into Linear or GitHub — a rename that missed the
 * component would have produced a plausible URL that 404s days later as "the webhook stopped
 * firing".
 */
export function webhookUrl(flowId: string): string {
  return `${location.origin}/api/webhooks/${flowId}`
}

/** The URL a caller POSTs to when a flow is published as an endpoint — same reasoning as above. */
export function publicRunUrl(flowId: string): string {
  return `${location.origin}/api/public/flows/${flowId}/run`
}

/** The demo chat page for a published flow. The token rides in the URL, which is why it is a demo. */
export function publicChatUrl(flowId: string, token: string): string {
  return `${location.origin}/api/public/flows/${flowId}/chat?token=${encodeURIComponent(token)}`
}

export const api = {
  // flows
  listFlows: () => req<BackendFlow[]>('/flows'),
  getFlow: (id: string) => req<BackendFlow>(`/flows/${id}`),
  saveFlow: (flow: BackendFlow) =>
    req<BackendFlow>('/flows', { method: 'POST', body: JSON.stringify(flow) }),
  /**
   * Saves a copy of a flow and returns it. Done on the backend, not by re-posting the graph with
   * its id removed: a copy has to arrive paused and without the original's webhook secret, and
   * both of those are rules about what a flow may do, not about what a form submits.
   */
  duplicateFlow: (id: string) => req<BackendFlow>(`/flows/${id}/duplicate`, { method: 'POST' }),
  deleteFlow: (id: string) => req<void>(`/flows/${id}`, { method: 'DELETE' }),
  runSavedFlow: (id: string) => req<RunSummary>(`/flows/${id}/run`, { method: 'POST' }),
  /**
   * A flow designed from a sentence. Returned unsaved (no id) — it lands on the canvas as a draft
   * and only exists once the user saves it.
   */
  generateFlow: (description: string) =>
    req<BackendFlow>('/flows/generate', { method: 'POST', body: JSON.stringify({ description }) }),
  /**
   * Which flows have a golden reference, and whether they changed since it ran. One call for the
   * whole dashboard — the answer is derived, so asking per card would be N requests for free data.
   */
  listGoldenStatus: () => req<GoldenStatus[]>('/flows/golden-status'),
  /** Everything that would fail at run time, before the run. Never blocks anything. */
  runFlowDoctor: (id: string) => req<DoctorFinding[]>(`/flows/${id}/doctor`),
  listFlowVersions: (id: string) => req<FlowVersionInfo[]>(`/flows/${id}/versions`),
  /** One revision as it was, for previewing it on the canvas. Changes nothing on the server. */
  getFlowVersion: (id: string, version: number) =>
    req<BackendFlow>(`/flows/${id}/versions/${version}`),
  restoreFlowVersion: (id: string, version: number) =>
    req<BackendFlow>(`/flows/${id}/versions/${version}/restore`, { method: 'POST' }),
  // Evaluations: a flow's dataset of cases, and the scores it produced per version.
  listEvalCases: (flowId: string) => req<FlowEvalCase[]>(`/flows/${flowId}/evals/cases`),
  saveEvalCase: (flowId: string, c: FlowEvalCaseInput) =>
    req<FlowEvalCase>(`/flows/${flowId}/evals/cases`, { method: 'POST', body: JSON.stringify(c) }),
  deleteEvalCase: (flowId: string, caseId: string) =>
    req<void>(`/flows/${flowId}/evals/cases/${caseId}`, { method: 'DELETE' }),
  /**
   * Starts an evaluation — one real run per case — and returns it at once, still running. The
   * work takes minutes; poll {@link getEvalResult} until its status is `done`.
   */
  runEvaluation: (flowId: string) =>
    req<FlowEvalResult>(`/flows/${flowId}/evals/run`, { method: 'POST' }),
  listEvalResults: (flowId: string) => req<FlowEvalResult[]>(`/flows/${flowId}/evals/results`),
  getEvalResult: (flowId: string, id: string) =>
    req<FlowEvalResult>(`/flows/${flowId}/evals/results/${id}`),
  // runtimes (what stdio MCP servers need in order to launch)
  /** What a configured MCP command needs, and whether this machine has it. */
  checkRuntime: (command: string, refresh = false) =>
    req<RuntimeCheck>(
      `/runtimes/check?command=${encodeURIComponent(command)}${refresh ? '&refresh=true' : ''}`,
    ),
  /** What installing a runtime would run here — shown next to the button that runs it. */
  runtimeInstallPlan: (runtime: string) =>
    req<RuntimeInstallPlan>(`/runtimes/${encodeURIComponent(runtime)}/install-plan`),
  /**
   * Installs a runtime and answers with the installer's own output. Desktop installs only; on a
   * server the backend refuses rather than touching the host.
   */
  installRuntime: (runtime: string) =>
    req<{ ok: boolean; command: string | null; output: string }>(
      `/runtimes/${encodeURIComponent(runtime)}/install`,
      { method: 'POST' },
    ),

  /** Notes this flow's agents left for future runs (memory_append), newest first. */
  getFlowMemory: (id: string) => req<FlowMemoryView>(`/flows/${id}/memory`),
  clearFlowMemory: (id: string) => req<void>(`/flows/${id}/memory`, { method: 'DELETE' }),

  // runs
  listRuns: () => req<RunSummary[]>('/runs'),
  getRunNodes: (id: string) => req<NodeExecReport>(`/runs/${id}/nodes`),
  /** What the agents did to the repositories: one diff per checkout, read from disk now. */
  getRunDiffs: (id: string) => req<RunDiff[]>(`/runs/${id}/diffs`),
  /** One checkout's diff as a file: the patch text, meant for saving, not for parsing. */
  fetchRunPatch: (runId: string, nodeId: string, folder: string) =>
    blob(`/runs/${runId}/diffs/${encodeURIComponent(nodeId)}/${encodeURIComponent(folder)}.patch`),
  /** The flow snapshot this run executed (works for ad-hoc runs and edited/deleted flows). */
  getRunFlow: (id: string) => req<BackendFlow>(`/runs/${id}/flow`),
  startRun: (flow: BackendFlow) =>
    req<RunSummary>('/runs', { method: 'POST', body: JSON.stringify(flow) }),
  sendCommand: (runId: string, text: string) =>
    req<void>(`/runs/${runId}/commands`, { method: 'POST', body: JSON.stringify({ text }) }),
  approveRun: (runId: string) => req<void>(`/runs/${runId}/approve`, { method: 'POST' }),
  rejectRun: (runId: string) => req<void>(`/runs/${runId}/reject`, { method: 'POST' }),
  stopRun: (runId: string) => req<void>(`/runs/${runId}/stop`, { method: 'POST' }),
  retryRun: (runId: string) => req<RunSummary>(`/runs/${runId}/retry`, { method: 'POST' }),
  resumeRun: (runId: string) => req<RunSummary>(`/runs/${runId}/resume`, { method: 'POST' }),
  /**
   * Runs ONE block of a finished run again, as a new run. An empty `input` means the input that
   * block actually received; `downstream` also carries the agents it delegates to, which is what
   * continuing a failed run from the block that broke means.
   */
  rerunBlock: (runId: string, nodeId: string, input: string, downstream: boolean, model?: string) =>
    req<RunSummary>(`/runs/${runId}/nodes/${nodeId}/rerun`, {
      method: 'POST',
      body: JSON.stringify({ input, downstream, model: model ?? '' }),
    }),
  /** Marks (or unmarks) a run as its flow's golden reference. One per flow. */
  setGoldenRun: (runId: string, golden: boolean) =>
    req<RunSummary>(`/runs/${runId}/golden`, { method: 'POST', body: JSON.stringify({ golden }) }),
  /** Replays the golden run's first input against the flow as saved NOW, as a new run. */
  goldenRerun: (runId: string) => req<RunSummary>(`/runs/${runId}/golden/rerun`, { method: 'POST' }),
  /** The golden reference and a candidate, side by side (numbers, steps, final outputs). */
  compareRuns: (referenceId: string, candidateId: string) =>
    req<RunComparison>(`/runs/${referenceId}/compare/${candidateId}`),
  /** Where this run's path would diverge against the flow as saved NOW. Routing only — see
   * the canvas legend; nothing is executed. */
  replayRun: (runId: string) => req<ReplayReport>(`/runs/${runId}/replay`),

  // agent library
  listAgents: () => req<LibraryAgent[]>('/agents'),
  saveAgent: (a: LibraryAgent) =>
    req<LibraryAgent>('/agents', { method: 'POST', body: JSON.stringify(a) }),
  deleteAgent: (id: string) => req<void>(`/agents/${id}`, { method: 'DELETE' }),

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
  /** What this installation holds, table by table — the estimate shown before a migration. */
  storageContents: (from: 'active' | 'embedded' = 'active') =>
    req<{ tables: Array<{ table: string; rows: number }>; totalRows: number }>(
      `/storage/contents?from=${from}`,
    ),
  /**
   * Copies everything into another PostgreSQL. Does not switch to it: the copy is repeatable and
   * changes nothing here, while switching needs a restart and deserves its own decision.
   *
   * A generous timeout, because this one really can take minutes — the default thirty seconds is
   * sized for a request, not for a year of run history crossing a VPN.
   */
  migrateStorage: (s: StorageDraft & { from?: 'active' | 'embedded'; skip?: string[] }) =>
    req<{
      ok: boolean
      copied: Array<{ table: string; rows: number }>
      warnings: string[]
      totalRows: number
    }>('/storage/migrate', { method: 'POST', body: JSON.stringify(s) }, 15 * 60_000),

  // Knowledge bases: document collections agents retrieve from.
  listKnowledge: () => req<KnowledgeDef[]>('/knowledge'),
  saveKnowledge: (k: KnowledgeDef) =>
    req<KnowledgeDef>('/knowledge', { method: 'POST', body: JSON.stringify(k) }),
  deleteKnowledge: (id: string) => req<void>(`/knowledge/${id}`, { method: 'DELETE' }),
  knowledgeDocs: (id: string) => req<KnowledgeDoc[]>(`/knowledge/${id}/documents`),
  uploadKnowledgeDoc: (id: string, file: File, name?: string) => {
    const form = new FormData()
    // The third argument overrides the filename the server sees — how a folder upload keeps its
    // relative path ("manuals/intro.pdf") instead of flattening to the basename.
    form.append('file', file, name ?? file.name)
    // 5 minutes, not the default 30s: a large PDF is extracted and embedded before the response.
    return req<{ docName: string; chunks: number; embedded: boolean; detail: string }>(
      `/knowledge/${id}/documents`,
      { method: 'POST', body: form },
      300_000,
    )
  },
  // name as a query param: folder-upload names contain slashes, and Tomcat rejects an encoded
  // slash inside a path segment.
  deleteKnowledgeDoc: (id: string, docName: string) =>
    req<void>(`/knowledge/${id}/documents${query({ name: docName })}`, { method: 'DELETE' }),
  /** Deletes a folder and every document under it; returns how many went. */
  deleteKnowledgeFolder: (id: string, path: string) =>
    req<{ deleted: number }>(`/knowledge/${id}/folders${query({ path })}`, {
      method: 'DELETE',
    }),
  /** Files a page into the base under its own address, so re-adding it replaces rather than duplicates. */
  addKnowledgeUrl: (id: string, url: string) =>
    req<{ docName: string; chunks: number; embedded: boolean; detail: string }>(
      `/knowledge/${id}/urls`,
      { method: 'POST', body: JSON.stringify({ url }) },
      120_000,
    ),
  /** Re-fetches every page in the base. Uploads are untouched — they have no address to go back to. */
  refreshKnowledgeUrls: (id: string) =>
    req<{ refreshed: Array<{ url: string; ok: boolean; chunks?: number; error?: string }> }>(
      `/knowledge/${id}/refresh`,
      { method: 'POST' },
      300_000,
    ),
  /** Extensions the backend can actually extract — the picker's source of truth. */
  knowledgeCapabilities: () => req<{ extensions: string[] }>('/knowledge/capabilities'),
  // The built-in embedding model: no server, no Docker — the backend runs it in-process.
  embedderStatus: () => req<EmbedderStatus>('/knowledge/embedder'),
  embedderDownload: () => req<void>('/knowledge/embedder/download', { method: 'POST' }),
  deleteEmbedder: () => req<void>('/knowledge/embedder', { method: 'DELETE' }),
  rerankerStatus: () => req<RerankerStatus>('/knowledge/reranker'),
  rerankerDownload: () => req<void>('/knowledge/reranker/download', { method: 'POST' }),
  deleteReranker: () => req<void>('/knowledge/reranker', { method: 'DELETE' }),
  // The golden set: questions with known answers, so a ranking change can be falsified.
  listEvals: (id: string) => req<EvalCase[]>(`/knowledge/${id}/evals`),
  addEval: (id: string, question: string, expectedDocs: string[]) =>
    req<EvalCase>(`/knowledge/${id}/evals`, {
      method: 'POST',
      body: JSON.stringify({ question, expectedDocs }),
    }),
  deleteEval: (id: string, caseId: string) =>
    req<void>(`/knowledge/${id}/evals/${caseId}`, { method: 'DELETE' }),
  /** Runs the golden set. 5 minutes: the reranker scores every candidate of every case. */
  runEvals: (id: string, topK = 5, rerank = true) =>
    req<EvalRun>(
      `/knowledge/${id}/evals/run`,
      { method: 'POST', body: JSON.stringify({ topK, rerank }) },
      300_000,
    ),
  searchKnowledge: (id: string, query: string, topK = 5) =>
    req<KnowledgeHit[]>(`/knowledge/${id}/search`, {
      method: 'POST',
      body: JSON.stringify({ query, topK }),
    }),

  /** Parses an OpenAPI spec (by URL or pasted) into the operations an API node could allow. */
  previewApiSpec: (specUrl: string, specText?: string) =>
    req<{ baseUrl: string; operations: ApiOperationView[] }>(
      '/api-nodes/preview',
      { method: 'POST', body: JSON.stringify({ specUrl, specText }) },
      60_000,
    ),
  // Agent Skills: the upload is multipart (a zipped skill folder).
  listSkills: () => req<SkillInfo[]>('/skills'),
  uploadSkill: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return req<SkillInfo>('/skills', { method: 'POST', body: form }, 120_000)
  },
  deleteSkill: (id: string) => req<void>(`/skills/${id}`, { method: 'DELETE' }),
  // The GitHub skill catalog. Long timeouts: listing a repo's skills downloads its archive.
  skillCatalog: () => req<SkillRepo[]>('/skills/catalog', {}, 30_000),
  skillCatalogRepo: (owner: string, repo: string) =>
    req<SkillCatalogSkill[]>(
      `/skills/catalog/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`, {}, 60_000),
  installCatalogSkill: (owner: string, repo: string, path: string) =>
    req<SkillInfo>('/skills/catalog/install',
      { method: 'POST', body: JSON.stringify({ owner, repo, path }) }, 120_000),
  // Claude Code plugins, via the `claude plugin` CLI. Installing clones a marketplace repo,
  // hence the long timeouts.
  listPlugins: () => req<PluginsView>('/plugins'),
  /** The whole marketplace catalog; the panel filters it client-side. Heavier read, cached 30s server-side. */
  listAvailablePlugins: () => req<AvailablePlugin[]>('/plugins/available', {}, 60_000),
  installPlugin: (id: string) =>
    req<{ status: string }>('/plugins/install', { method: 'POST', body: JSON.stringify({ id }) }, 120_000),
  uninstallPlugin: (id: string) =>
    req<{ status: string }>('/plugins/uninstall', { method: 'POST', body: JSON.stringify({ id }) }, 60_000),
  setPluginEnabled: (id: string, enabled: boolean) =>
    req<{ status: string }>('/plugins/enable',
      { method: 'POST', body: JSON.stringify({ id, enabled: String(enabled) }) }, 60_000),
  addPluginMarketplace: (source: string) =>
    req<{ status: string }>('/plugins/marketplaces', { method: 'POST', body: JSON.stringify({ source }) }, 180_000),
  removePluginMarketplace: (name: string) =>
    req<{ status: string }>('/plugins/marketplaces/remove', { method: 'POST', body: JSON.stringify({ name }) }, 60_000),
  /** Measured Claude consumption on this machine (CLI transcripts). Cached 30s server-side. */
  usageSummary: () => req<UsageSummary>('/usage'),

  // database definitions (what an agent queries for context; the app's own storage is above)
  listDatabases: () => req<DatabaseDef[]>('/databases'),
  saveDatabase: (d: DatabaseDef) =>
    req<DatabaseDef>('/databases', { method: 'POST', body: JSON.stringify(d) }),
  deleteDatabase: (id: string) => req<void>(`/databases/${id}`, { method: 'DELETE' }),

  // mcp server definitions
  listMcpDefs: () => req<McpDef[]>('/mcp-defs'),
  saveMcpDef: (d: McpDef) => req<McpDef>('/mcp-defs', { method: 'POST', body: JSON.stringify(d) }),
  deleteMcpDef: (id: string) => req<void>(`/mcp-defs/${id}`, { method: 'DELETE' }),
  // The whole configuration as one file, and back. Export returns the raw blob so the caller
  // can hand it to a download link; import takes the parsed document. `includeSecrets` asks for
  // credential values in the clear — admin only, and the file's name and header say so.
  exportBackup: (includeSecrets = false) =>
    blob(`/backup${includeSecrets ? '?includeSecrets=true' : ''}`),
  importBackup: (bundle: unknown) =>
    req<{ imported: Record<string, number>; credentialsToReenter: string[]; warnings: string[] }>(
      '/backup', { method: 'POST', body: JSON.stringify(bundle) }, 120_000),

  // Organization-level prompt variables ({{NAME}} in prompts). Flow overrides live on the flow.
  listVariables: () => req<Variable[]>('/variables'),
  saveVariable: (v: Variable) =>
    req<Variable>('/variables', { method: 'POST', body: JSON.stringify(v) }),
  deleteVariable: (id: string) => req<void>(`/variables/${id}`, { method: 'DELETE' }),

  // facade profiles (what an independent worker may reach through its MCP facade)
  listFacadeProfiles: () => req<FacadeProfile[]>('/facade-profiles'),
  saveFacadeProfile: (p: FacadeProfile) =>
    req<FacadeProfile>('/facade-profiles', {
      method: 'POST',
      body: JSON.stringify(p),
    }),
  deleteFacadeProfile: (id: string) => req<void>(`/facade-profiles/${id}`, { method: 'DELETE' }),

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

  // the audit trail (admin only; export is an Enterprise feature and 403s below it)
  auditStatus: () => req<AuditStatus>('/audit/status'),
  listAudit: (filters: AuditFilters, before?: number, limit = 100) =>
    req<AuditPage>(
      `/audit${query({
        actor: filters.actor || undefined,
        kind: filters.kind || undefined,
        from: filters.from || undefined,
        to: filters.to || undefined,
        before: before === undefined ? undefined : String(before),
        limit: String(limit),
      })}`,
    ),
  /** The trail as a file. A refusal here is the tier gate's sentence, which blob() keeps. */
  exportAudit: (format: 'csv' | 'json', filters: AuditFilters) =>
    blob(
      `/audit/export${query({
        format,
        actor: filters.actor || undefined,
        kind: filters.kind || undefined,
        from: filters.from || undefined,
        to: filters.to || undefined,
      })}`,
    ),
  /** Applies the retention policy now instead of at three in the morning. Admin only. */
  runRetentionNow: () =>
    req<RetentionReport>('/retention/run-now', { method: 'POST' }, 120_000),

  // license (what this installation is running under)
  getLicense: () => req<LicenseStatus>('/license'),
  installLicense: (token: string) =>
    req<LicenseStatus>('/license', { method: 'POST', body: JSON.stringify({ token }) }),

  // organization policies (Enterprise): the rules over every flow, and publish approvals
  getOrgPolicy: () => req<OrgPolicyView>('/org-policy'),
  saveOrgPolicy: (policy: OrgPolicy) =>
    req<OrgPolicyView>('/org-policy', { method: 'PUT', body: JSON.stringify(policy) }),
  publishApproval: (flowId: string) =>
    req<PublishApprovalView>(`/org-policy/publish/${encodeURIComponent(flowId)}`),
  approvePublish: (flowId: string) =>
    req<PublishApprovalView>(`/org-policy/publish/${encodeURIComponent(flowId)}/approve`, { method: 'POST' }),
  revokePublish: (flowId: string) =>
    req<PublishApprovalView>(`/org-policy/publish/${encodeURIComponent(flowId)}/approve`, { method: 'DELETE' }),

  // account / sign-in
  session: () => req<SessionInfo>('/account/session'),
  signIn: (email: string, password: string) =>
    req<SignedInUser>('/account/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  signOut: () => req<void>('/account/logout', { method: 'POST' }),
  /**
   * The accounts this browser has already signed into.
   *
   * Answered for the browser rather than for the session, so it survives signing out — coming
   * back to an account you left is the case it exists for.
   */
  switchableAccounts: () => req<SwitchableAccount[]>('/account/accounts'),
  /**
   * The directories people could sign in with, and the redirect URI to register.
   *
   * The redirect URI comes from the backend rather than being built here: it has to match what the
   * application will actually ask for, which depends on how the request arrived — localhost, a LAN
   * address, or a domain behind a proxy.
   */
  listSignInProviders: () => req<SignInProvidersList>('/account/providers'),
  /** Saves one registration. A blank secret leaves the stored one alone. Answers the new list. */
  saveSignInProvider: (update: {
    id: string
    enabled: boolean
    clientId: string
    clientSecret: string
    tenant: string
    issuer: string
    displayName: string
  }) =>
    req<SignInProvidersList>('/account/providers', {
      method: 'PUT',
      body: JSON.stringify(update),
    }),
  /** Every setting this installation offers, with what it is now and where that value came from. */
  listSettings: () => req<{ settings: SettingEntry[] }>('/settings'),
  /**
   * Saves overrides. A blank value clears one, which is what lets the deployment's own
   * configuration — or the built-in default — stand again.
   */
  saveSettings: (values: Record<string, string>) =>
    req<{ settings: SettingEntry[] }>('/settings', {
      method: 'PUT',
      body: JSON.stringify({ values }),
    }),
  /**
   * Creates the first account on an installation that has none, and signs it in.
   *
   * Refused the moment one exists, which is what makes it safe to reach without a session — and
   * the reason the setup screen is a state of the app rather than a page anybody can visit.
   */
  createFirstAccount: (email: string, password: string) =>
    req<SignedInUser>('/account/setup', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  /** Becomes one of them. Allowed only because this browser signed into it once, with its own password or provider. */
  useAccount: (userId: string) =>
    req<SignedInUser>(`/account/accounts/${userId}/use`, { method: 'POST' }),
  /** Drops an account from this browser. Signing into it again brings it back. */
  forgetAccount: (userId: string) => req<void>(`/account/accounts/${userId}`, { method: 'DELETE' }),

  // Tokens for machines. Admin only; the token is in the create answer and nowhere else.
  listServiceAccounts: () => req<ServiceAccountListing>('/service-accounts'),
  createServiceAccount: (name: string, role: string) =>
    req<CreatedServiceAccount>('/service-accounts', {
      method: 'POST',
      body: JSON.stringify({ name, role }),
    }),
  renameServiceAccount: (id: string, name: string) =>
    req<ServiceAccount>(`/service-accounts/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    }),
  revokeServiceAccount: (id: string) =>
    req<ServiceAccount>(`/service-accounts/${id}/revoke`, { method: 'POST' }),

  /** Everyone in the caller's own organization. Password hashes never leave the backend. */
  listMembers: () => req<Member[]>('/account/members'),
  addMember: (email: string, password: string, role: string) =>
    req<Member>('/account/members', {
      method: 'POST',
      body: JSON.stringify({ email, password, role }),
    }),
  /** Changes what one member may do. Admin only; the backend refuses the last admin's demotion. */
  changeMemberRole: (userId: string, role: string) =>
    req<Member>(`/account/members/${userId}/role`, {
      method: 'POST',
      body: JSON.stringify({ role }),
    }),

  // organizations (several on one deployment — creating a second one is Enterprise)
  /** The organizations this account is in, every role: a Viewer in two of them switches too. */
  listOrganizations: () => req<Organization[]>('/organizations'),
  /** Refused with the Enterprise sentence on any other tier; the panel shows it as it arrives. */
  createOrganization: (name: string) =>
    req<Organization>('/organizations', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),
  renameOrganization: (id: string, name: string) =>
    req<Organization>(`/organizations/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    }),
  /** Everyone in one organization the caller administers, with the role each holds there. */
  listOrganizationMembers: (id: string) =>
    req<Member[]>(`/organizations/${id}/members`),
  /** An existing address joins as is; a new one needs a temporary password and takes a seat. */
  inviteToOrganization: (id: string, email: string, password: string, role: string) =>
    req<Member>(`/organizations/${id}/members`, {
      method: 'POST',
      body: JSON.stringify({ email, password, role }),
    }),
  /** Starts working in another organization this account is in. The page reloads afterwards. */
  switchOrganization: (id: string) =>
    req<SignedInUser>(`/organizations/${id}/switch`, { method: 'POST' }),
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

  // Credentials the app signs in to. The tokens never reach this side: the browser comes back to
  // the backend, which stores the grant and hands its pieces to a run's MCP server. Status reports
  // ABOUT the grant — connected, scope, whether a refresh token arrived — and none of its secrets.
  createOAuthCredential: (body: OAuthCredentialConfig) =>
    req<{ id: string; label: string }>('/credentials/oauth', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateOAuthCredential: (id: string, body: OAuthCredentialConfig) =>
    req<{ ok: boolean; error?: string }>(`/credentials/${id}/oauth`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  oauthCredentialStatus: (id: string) => req<OAuthCredentialStatus>(`/credentials/${id}/oauth`),
  /** Returns the provider's URL; the caller opens it in the SYSTEM browser, never a webview. */
  startOAuthSignIn: (id: string) =>
    req<OAuthSignInStart>(`/credentials/${id}/oauth/start`, { method: 'POST' }),

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
    req<McpToolList>(`/mcp/tools${query({ url, credentialId: credentialId || undefined })}`),

  mcpOAuthStatus: (url: string) => req<McpOAuthStatus>(`/mcp/oauth/status${query({ url })}`),
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
      `/git/repos${query({
        provider,
        group,
        credentialId: credentialId || undefined,
        baseUrl: baseUrl || undefined,
      })}`,
    ),

  // rag
  ragPreview: (source: SqlSourceInput) =>
    req<SqlPreview>('/rag/preview', { method: 'POST', body: JSON.stringify(source) }),

  // marketplace. The server decides what the caller may SEE; the page filters what it got.
  listMarketplaceItems: (filters: MarketplaceListFilters = {}) =>
    req<MarketplaceList>(
      `/marketplace/items${query({
        q: filters.q || undefined,
        kind: filters.kind,
        scope: filters.scope,
        tag: filters.tag || undefined,
        status: filters.status,
        sort: filters.sort,
      })}`,
    ),
  getMarketplaceItem: (id: string) => req<MarketplaceItem>(`/marketplace/items/${id}`),
  publishMarketplaceItem: (body: MarketplacePublishBody) =>
    req<MarketplaceItem>('/marketplace/items', { method: 'POST', body: JSON.stringify(body) }),
  updateMarketplaceItem: (id: string, body: MarketplacePublishBody) =>
    req<MarketplaceItem>(`/marketplace/items/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteMarketplaceItem: (id: string) => req<void>(`/marketplace/items/${id}`, { method: 'DELETE' }),
  installMarketplaceItem: (id: string) =>
    req<MarketplaceInstallResult>(`/marketplace/items/${id}/install`, { method: 'POST' }),
  uninstallMarketplaceItem: (id: string) =>
    req<void>(`/marketplace/items/${id}/uninstall`, { method: 'POST' }),
  approveMarketplaceItem: (id: string) =>
    req<void>(`/marketplace/items/${id}/approve`, { method: 'POST' }),
  rejectMarketplaceItem: (id: string, reason: string) =>
    req<void>(`/marketplace/items/${id}/reject`, { method: 'POST', body: JSON.stringify({ reason }) }),
  /** Publishes a resource this organization already has; `stripped` names the credentials left behind. */
  publishMarketplaceFrom: (body: MarketplacePublishFromBody) =>
    req<MarketplaceItem & { stripped: string[] }>('/marketplace/publish-from', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  marketplaceStatus: () => req<MarketplaceStatus>('/marketplace/status'),
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
