// ---- Run + event contracts (mirror the backend records) ----

export type RunStatus =
  | 'STARTING'
  | 'RUNNING'
  | 'IDLE'
  /** Stopped mid-run in approval mode: a plan is on screen and a human has to answer. */
  | 'AWAITING_APPROVAL'
  | 'ERROR'
  | 'TERMINATED'

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

type RunEventType ='system' | 'status' | 'agent_message' | 'tool_use' | 'error'

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

/** An execution backend and whether it can run right now. */
interface BackendStatus {
  /** `local` (claude CLI on a subscription) or `cloud` (ANTHROPIC_API_KEY). */
  id: string
  name: string
  available: boolean
}

/**
 * What the designer knows about models before a run.
 *
 * There is no provider list to choose from: it is Claude, or a model on your own hardware. Which
 * Claude credential is present and whether a self-hosted server is answering is what `backends`
 * reports, and the model named on an agent decides which of them runs the flow.
 */
/** Whether MCP tool search will rank semantically, and what is missing when it will not. */
export interface ToolSearchStatus {
  enabled: boolean
  embeddingModel: string
  modelPresent: boolean
  vectorReady: boolean
  threshold: number
  detail: string
}

export interface ModelCatalog {
  toolSearch?: ToolSearchStatus
  /** Model ids the self-hosted server is serving right now. Discovered, not configured. */
  localModels?: string[]
  /** Why `localModels` is empty, when it is. */
  localError?: string | null
  /** Rates for models named in `pricing.models`, keyed by model id. */
  pricing: Record<string, ModelRate>
  /** Rate applied to any model not listed above. */
  fallback: ModelRate
  backends: BackendStatus[]
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

export type NodeKind = 'agent' | 'mcp' | 'repo' | 'sql' | 'knowledge' | 'input'

export type InputNodeData = {
  kind: 'input'
  mode: 'manual' | 'prompt' | 'cron' | 'webhook' | 'mail'
  prompt: string
  cron: string
  /** Secret issued by the provider; we verify against it, we never mint it. */
  secret: string
  /** Header (or query param) carrying the signature/token, e.g. `Linear-Signature`. */
  authParam: string
  /**
   * How much the claude CLI may do without asking, for runs of this flow.
   *
   * Blank means the deployment default. Per flow rather than per deployment because the answer
   * differs by what the flow does: one that edits a repository and opens a PR is not the same
   * risk as one that reads a mailbox and writes a draft.
   */
  permissionMode?: 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan'

  // --- mail mode (IMAP) ---------------------------------------------------
  // Folders, flags and read state live in the mail store, so this is IMAP rather than SMTP:
  // SMTP only ever hands over a message, with nowhere to look and nothing to move it into.
  // Flows saved before mail mode existed simply omit all of these.
  mailHost?: string
  mailPort?: number
  mailSsl?: boolean
  mailUsername?: string
  /**
   * The id of a stored credential — never the password.
   *
   * A reference rather than the value, even encrypted, because every flow save snapshots the
   * flow's JSON into version history and duplicating a flow copies its nodes: a secret here would
   * fan out into every revision and every copy.
   */
  mailCredentialId?: string
  /**
   * How the mailbox authenticates. Microsoft 365 refuses a password over IMAP outright — Basic
   * authentication is retired there — so the mode is chosen per node rather than guessed from the
   * host, which would silently break a self-hosted server that happens to be behind Exchange.
   */
  mailAuthMode?: 'password' | 'microsoft-oauth'
  mailTenantId?: string
  mailClientId?: string
  mailFolder?: string
  // Conditions. Blank/false means "don't filter on this".
  mailFrom?: string
  mailSubjectContains?: string
  mailBodyContains?: string
  mailUnseenOnly?: boolean
  mailFlaggedOnly?: boolean
  mailWithAttachmentsOnly?: boolean
  mailPollSeconds?: number
  mailMaxPerPoll?: number
  // What happens to a message once its run has started.
  mailMoveToFolder?: string
  mailMarkSeen?: boolean
  mailFlagAfter?: boolean
}

/** What a person has to do to finish a Microsoft mailbox sign-in, plus the handle we poll with. */
export interface MailDeviceCode {
  deviceCode: string
  userCode: string
  verificationUri: string
  message: string
  expiresIn: number
  /** Seconds Entra asks us to wait between polls. Polling faster earns a `slow_down`. */
  interval: number
}

export interface MailSignInResult {
  /** True while the person is still entering the code — normal, not a failure. */
  pending: boolean
  ok?: boolean
  error?: string
  credentialId?: string
  label?: string
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
  /** Tools this sub-agent may use — the one permission Claude Code enforces per agent. Empty = all. */
  tools?: string[]
  /**
   * How much the run may do without asking. **Coordinator only.**
   *
   * Not a per-agent setting even though it lives on an agent: a local run launches one `claude`
   * process for the whole flow, and `--permission-mode` applies to that process. The coordinator
   * is that process, so this is the one node where the control is not a lie — sub-agents
   * deliberately do not offer it rather than showing a switch that would silently do nothing.
   */
  permissionMode?: 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | ''
}

export type McpNodeData = {
  kind: "mcp"
  name: string
  url: string
  credentialId: string
  /**
   * Header the credential is sent in.
   *
   * Blank means `Authorization` with a `Bearer ` prefix, which most MCP servers expect. GitLab
   * reads its access tokens from `PRIVATE-TOKEN`, unprefixed, so this cannot be fixed globally.
   */
  authHeader?: string
  /**
   * Substrings selecting which of the server's tools an agent gets. Empty means all.
   *
   * A real MCP server can expose hundreds of tools — Holded's has 338 — and each one is a JSON
   * schema in the prompt. That overflows a self-hosted model's context before the conversation
   * starts, and the model server truncates in silence.
   */
  tools?: string[]
}

export type RepoNodeData = {
  kind: "repo"
  provider: "github" | "gitlab"
  url: string
  credentialId: string
  mountPath: string
  branch: string
  /** Self-hosted GitLab or GitHub Enterprise. Blank = the public instance. */
  baseUrl?: string
  /**
   * A GitHub organization or GitLab group path. When set, this one node stands for every
   * repository inside it, resolved when the run starts — so a repository added to the group next
   * week is picked up without editing the flow.
   */
  group?: string
  /** Restricts a group to these repositories, by full name. Empty means all of them. */
  only?: string[]
  /** Archived repositories are read-only, so they're excluded from a group by default. */
  includeArchived?: boolean
}

export type SqlNodeData = {
  kind: 'sql'
  label: string
  jdbcUrl: string
  username: string
  credentialId: string
  query: string
  maxRows: number
}

export type KnowledgeNodeData = {
  kind: 'knowledge'
  label: string
  /** Which knowledge base to retrieve from — created under Resources → Knowledge. */
  baseId: string
  /** How many passages to inject. */
  topK: number
}

export type AppNodeData = AgentNodeData | McpNodeData | RepoNodeData | SqlNodeData | KnowledgeNodeData | InputNodeData

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

interface BackendFlowEdge {
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

/**
 * Where Concentus keeps its own data — runs, credentials, flow history.
 *
 * Distinct from {@link DatabaseDef}, which is a database an *agent* queries for context. The names
 * are close enough to be worth stating: this one is the application's storage.
 */
export type StorageConfig = {
  /** What the next start will use. */
  mode: 'embedded' | 'external'
  url: string
  username: string
  /** The password is never returned; this only says whether one is stored. */
  hasPassword: boolean
  /** What this process is running on — differs from `mode` when a restart is pending. */
  activeMode: 'embedded' | 'external'
  restartRequired?: boolean
}

/** A pending edit. `password: null` means "leave the stored one alone". */
export type StorageDraft = {
  mode: 'embedded' | 'external'
  url: string
  username: string
  password: string | null
}

export type DatabaseDef = {
  id?: string
  label: string
  jdbcUrl: string
  username: string
  credentialId: string
}

export type McpDef = {
  id?: string
  name: string
  url: string
  credentialId: string
  authHeader?: string
}

export interface McpServerInfo {
  name: string
  url: string
  status: string
}

// --- Sign-in ---------------------------------------------------------------

export interface SignedInUser {
  userId: string
  email: string
  organizationId: string
  role: string
}

/**
 * Whether sign-in is required and, if so, who is signed in.
 *
 * `authEnabled: false` is the escape hatch for local development, in which case the API is open
 * and every request resolves to the default organization.
 */
export interface SessionInfo {
  authEnabled: boolean
  storeAvailable: boolean
  signedIn: boolean
  userId?: string
  email?: string
  organizationId?: string
  role?: string
}

// --- Stored credentials -----------------------------------------------------

/**
 * A credential as the app sees it: metadata only.
 *
 * There is no `value` field, and that is deliberate rather than an omission — the API has no
 * endpoint that returns a stored secret, so there is nowhere for one to arrive from. `hint` is a
 * masked fragment, enough to tell two apart and not enough to reconstruct either.
 */
export interface Credential {
  id: string
  label: string
  kind: string
  hint: string | null
  createdAt: number
  updatedAt: number
  lastUsedAt: number | null
}

export interface CredentialStatus {
  /** False when CONCENTUS_SECRET_KEY is unset, so nothing can be stored. */
  available: boolean
  hint: string
}

/** What the host can do about MCP authentication. */
export interface McpCapabilities {
  /** False in a container: the OAuth sign-in needs a terminal and a browser. */
  interactiveLogin: boolean
  hint: string
}

/** A repository as the group/organization browser lists it. */
export interface RemoteRepo {
  name: string
  fullName: string
  cloneUrl: string
  defaultBranch: string
  archived: boolean
  description: string | null
}

export interface RemoteRepoList {
  ok: boolean
  error?: string
  repos: RemoteRepo[]
}

/** What the mail poller last did for one flow — the answer to "is this trigger working?". */
export interface MailStatus {
  /** unknown | off | incomplete | paused | waiting | ok | error */
  state: string
  detail: string
  folder?: string
  host?: string
  pollSeconds?: number
  runsStarted?: number
  matched?: number
  at?: number
}

/**
 * The Entra app registration this deployment signs mailboxes in with.
 *
 * One registration serves every mailbox — it identifies the application, not the mailbox — so the
 * node only asks when the deployment has none. Neither id is a secret.
 */
export interface MailOAuthDefaults {
  tenantId: string
  clientId: string
  configured: boolean
}

/** Whether Concentus holds its own OAuth grant for an MCP server. */
export interface McpOAuthStatus {
  connected: boolean
  redirectUri?: string
}

export interface McpOAuthStart {
  ok: boolean
  authorizationUrl?: string
  redirectUri?: string
  error?: string
}

/** One tool a server offers, as the picker lists it. Schemas are omitted — the list is huge. */
export interface McpToolInfo {
  name: string
  description: string
}

export interface McpToolList {
  ok: boolean
  tools?: McpToolInfo[]
  error?: string
}

/** A named collection of documents agents retrieve from (Resources → Knowledge). */
export type KnowledgeDef = {
  id?: string
  name: string
  description?: string
}

export type KnowledgeDoc = {
  name: string
  chunks: number
  embedded: boolean
  createdAt: number
}

export type KnowledgeHit = {
  docName: string
  seq: number
  content: string
  score: number
}

/** The built-in, in-process embedding model — downloaded on demand from the app. */
export interface EmbedderStatus {
  state: 'NOT_DOWNLOADED' | 'DOWNLOADING' | 'READY' | 'ERROR'
  percent: number
  error: string
  sizeMb: number
  model: string
  /** Whether search ranks by meaning right now, through EITHER the built-in model or a server. */
  semantic: boolean
  detail: string
}
