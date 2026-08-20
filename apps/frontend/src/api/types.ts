// ---- Run + event contracts (mirror the backend records) ----

export type RunStatus =
  | 'STARTING'
  | 'RUNNING'
  /** Waiting for its FIRST instruction — nothing has run yet. */
  | 'IDLE'
  /** Stopped mid-run in approval mode: a plan is on screen and a human has to answer. */
  | 'AWAITING_APPROVAL'
  /** The final answer asked the user something; the session waits for the reply. */
  | 'AWAITING_ANSWER'
  /** Delivered its result. Still continuable while retained — terminal means "done", not "dead". */
  | 'COMPLETED'
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
  /** This run is its flow's golden reference — the known-good execution edits are compared against. */
  golden?: boolean
  /**
   * The flow revision this execution ran, 0 when the flow had no history at launch. The exact
   * graph is always the run's own snapshot (`getRunFlow`); this number just names it.
   */
  flowVersion?: number
}

/** One side of a golden comparison: headline numbers, per-node steps (priced), final answer. */
export interface RunComparisonSide {
  run: RunSummary
  nodes: NodeExec[]
  finalOutput?: string | null
}

/** The golden reference and a candidate run, side by side. Raw facts; no computed verdicts. */
export interface RunComparison {
  reference: RunComparisonSide
  candidate: RunComparisonSide
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
  /**
   * Context-window occupancy after this block's latest message — the whole prompt it read plus
   * what it wrote, like Claude Code's /context. A snapshot, not a running sum.
   */
  contextTokens?: number
  /**
   * The context this block STARTED with (system prompt, tools, its task). The difference with
   * `contextTokens` is what its own work added to the window.
   */
  contextStartTokens?: number
  /** The model's context-window size; absent when unknown (e.g. self-hosted models). */
  contextWindow?: number | null
  startedAt: number
  endedAt: number
  /** Extra process launches after a failed attempt (fan-out only). */
  retries?: number
  /**
   * The adversarial verifier's judgment on this worker's output. Separate from `status` on
   * purpose: a rejected worker DID finish — the verifier killing its output is a second fact.
   */
  verdict?: 'accepted' | 'rejected' | null
  /** The verifier's stated reason, when rejected. */
  verdictReason?: string | null
}

/**
 * Health of a fan-out run as a graph: how parallel it really was, how much retrying propped it
 * up, and how hard the verifier worked. Absent for runs that never fanned out.
 */
export interface GraphMetrics {
  workers: number
  workersFailed: number
  /** Outputs the verifier killed — they finished, and did not survive. */
  workersRejected: number
  retries: number
  /** Worker outputs judged by the verifier (0 = no verifier ran). */
  verdicts: number
  /** First worker start to last worker end — what the fan-out actually took. */
  wallMs: number
  /** The same work end to end — what it WOULD have taken sequentially. */
  sumWorkerMs: number
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
  /** Fan-out health; absent for runs that never fanned out. */
  graph?: GraphMetrics | null
}

// ---- Flow / node data (canvas) ----
// `type` aliases (not interfaces) so they satisfy React Flow's
// `Record<string, unknown>` node-data constraint.

export type NodeKind =
  | 'agent'
  | 'mcp'
  | 'repo'
  | 'sql'
  | 'knowledge'
  | 'api'
  | 'flow'
  | 'input'
  | 'merge'
  | 'verifier'
  | 'condition'
  | 'foreach'

export type InputNodeData = {
  kind: 'input'
  /** 'subflow' = this flow is started by another flow, never on its own. */
  mode: 'manual' | 'prompt' | 'cron' | 'webhook' | 'mail' | 'subflow'
  /** Shadow: triggered runs plan and stop — watch what a trigger WOULD do before trusting it. */
  shadow?: boolean
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
  /** Installed skills assigned to this agent (Resources → Skills). */
  skillIds?: string[]
  /**
   * Claude Code plugin ids (name@marketplace) this agent runs with (Resources → Plugins).
   * Empty = the CLI's own defaults; non-empty = exactly these. Claude backend only.
   */
  plugins?: string[]
  /**
   * Escalation model. **Independent workers only, and only with a Verifier in the flow.** When the
   * verifier rejects this worker's output, it runs once more on this model and is judged again.
   * Blank = off. Without a verifier nothing escalates — there is no signal saying the cheap
   * answer was wrong, and re-running "in case" would spend more rather than less.
   */
  fallbackModelId?: string
  /**
   * How much the run may do without asking. **Coordinator only.**
   *
   * Not a per-agent setting even though it lives on an agent: a local run launches one `claude`
   * process for the whole flow, and `--permission-mode` applies to that process. The coordinator
   * is that process, so this is the one node where the control is not a lie — sub-agents
   * deliberately do not offer it rather than showing a switch that would silently do nothing.
   */
  permissionMode?: 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | ''
  /**
   * How the coordinator distributes work. **Coordinator only**, like `permissionMode`.
   *
   * Empty/absent = Claude Code subagents inside one CLI session (the behaviour every flow had
   * before this existed). `fanout` = one independent `claude` process per sub-agent: own
   * workspace, own instructions, own model, true parallelism — and, for now, no MCP servers and
   * no repository clones inside workers (the run console says so on every fan-out turn).
   */
  execution?: 'subagents' | 'fanout' | ''
  /**
   * Facade profile this agent runs behind as an independent worker (Resources → Facades).
   * Sub-agents only. Without one, a worker reaches the servers wired to its node with nothing
   * filtered — the same reach it has as a sub-agent of a shared session. Calls still go through
   * the facade endpoint, so assigning a profile later narrows a worker already running.
   */
  facadeProfileId?: string
  /**
   * What the fan-out coordinator's own process may do. **Coordinator only, fan-out only.**
   * Empty/absent = auto: read-only exactly when it has sub-agents wired (a coordinator with
   * workers distributes, a solo one works). 'read-only' / 'may-act' force either shape.
   * Delegation is denied in every case.
   */
  coordinatorAccess?: '' | 'read-only' | 'may-act'
}

export type McpNodeData = {
  kind: "mcp"
  name: string
  url: string
  credentialId: string
  /**
   * The stdio transport: a local process speaking MCP over stdin/stdout — the npx/python
   * invocation the server's README says to put in mcp.json. A node with a command ignores url.
   * An env VALUE of the form `credential:<id>` is resolved from the credential store when the
   * run's config is written, so tokens need not live in the flow.
   */
  command?: string
  args?: string[]
  env?: Record<string, string>
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

/**
 * Another flow, run from this one.
 *
 * One kind, two drawings. Wired to an agent it is a capability the agent may call and wait on;
 * left terminal it is a hand-off that fires when this run completes. Same fields either way —
 * which flow, and whether the caller waits — because that is genuinely all they differ on.
 */
export type FlowRunNodeData = {
  kind: 'flow'
  label: string
  /** Id of the flow to run. The inspector picks it from the saved flows. */
  flowId: string
  /**
   * Whether the caller waits for the child's answer.
   *
   * Ignored for a hand-off — by then the run is over and there is nobody left to hand a result to.
   * WHEN it runs is not a field: a node wired into an agent runs before it, one wired out of an
   * agent runs after. The drawing says it, so the node does not have to.
   */
  waitForResult: boolean
  /**
   * What a node saved by an older version says about when it runs. Never written any more, and
   * never editable — it is here so the canvas can show the truth about those nodes, because the
   * compiler still honours it rather than silently changing what somebody's flow does. Clearing
   * it (the inspector offers that) hands the decision to the wiring.
   */
  mode?: 'tool' | 'after'
}

export type KnowledgeNodeData = {
  kind: 'knowledge'
  label: string
  /** Which knowledge base to retrieve from — created under Resources → Knowledge. */
  baseId: string
  /** How many passages to inject. */
  topK: number
}

export type ApiNodeData = {
  kind: 'api'
  label: string
  /** URL of the OpenAPI 3.x document; or paste it into specInline when it is not fetchable. */
  specUrl: string
  specInline?: string
  /** Overrides the spec's own servers[0].url when set. */
  baseUrl?: string
  credentialId?: string
  authHeader?: string
  /** Operation keys ("GET /pets") the agent may call. Empty = none — allowing is explicit. */
  ops: string[]
  /**
   * `spec` reads an OpenAPI document; `endpoint` is one URL typed by hand and no document at all.
   * Absent means spec, so every API node saved before this existed is unchanged.
   */
  mode?: 'spec' | 'endpoint'
  /** endpoint mode: the URL to call, {placeholders} included — each becomes an argument. */
  url?: string
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** endpoint mode: what this endpoint does. With no spec, it is all the model gets to read. */
  description?: string
  /** endpoint mode: whether the agent may send a JSON body. */
  sendsBody?: boolean
}

/**
 * The merge step of a fan-out flow: one more `claude` process that runs after every worker,
 * reconciles their reports into the run's final answer, and — unlike the workers — may run
 * commands, because verifying the combined result (tests, diffs) is exactly one process's job.
 */
export type MergeNodeData = {
  kind: 'merge'
  name: string
  model: string
  /** Merge instructions: how to reconcile, what to verify, what the final report must contain. */
  systemPrompt: string
  maxTokens: number
  effort: string
  /**
   * Facade profile the merge step runs behind, exactly as a worker does. Absent means the servers
   * wired to this node with nothing filtered. It speaks last, so it is the step that sends the
   * report — which is why it reaches MCP at all.
   */
  facadeProfileId?: string
}

/**
 * The adversarial verifier of a fan-out flow: one more `claude` process that runs after every
 * worker and BEFORE the merge, whose objective is the workers' inverse — find the reason each
 * output should be rejected. Its verdict has teeth: a rejected output is withheld from the
 * merge. Read-only; its only tool is submitting the verdict.
 */
export type VerifierNodeData = {
  kind: 'verifier'
  name: string
  model: string
  /** Rejection criteria: what disqualifies an output in THIS flow, beyond the built-in rules. */
  systemPrompt: string
  maxTokens: number
  effort: string
}

/**
 * A gate on the way from an agent to what it hands off to: the branch runs only when the agent's
 * output passes the test.
 *
 * <p>The same rule written into a prompt is a request — an agent having a bad day sends the empty
 * report anyway. Measured afterwards, it is a rule, and it is visible on the canvas instead of
 * buried in the third paragraph of an instruction.
 */
export type ConditionNodeData = {
  kind: 'condition'
  label: string
  test: 'not_empty' | 'contains' | 'not_contains' | 'equals' | 'matches'
  /** What to look for. Ignored by `not_empty`; a regular expression for `matches`. */
  value: string
  caseSensitive: boolean
}

/**
 * A gate that runs the branch behind it once per item of the agent's answer, instead of once with
 * the whole answer.
 */
export type ForEachNodeData = {
  kind: 'foreach'
  label: string
  /** How to read the list: one item per line, or a JSON array anywhere in the output. */
  source: 'lines' | 'json'
  /** Ceiling on runs started at once — a list nobody expected to be long starts what it says. */
  limit: number
}

export type AppNodeData =
  | AgentNodeData
  | McpNodeData
  | RepoNodeData
  | SqlNodeData
  | KnowledgeNodeData
  | ApiNodeData
  | FlowRunNodeData
  | InputNodeData
  | MergeNodeData
  | VerifierNodeData
  | ConditionNodeData
  | ForEachNodeData

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
  /** Monthly spend ceiling in USD; at or past it, new runs are refused until next month. */
  budgetUsd?: number | null
  /**
   * This flow's {{NAME}} values — overrides of the organization's variables plus its own.
   * Saved with the flow: a flow remembers the values it runs with.
   */
  variables?: Record<string, string>
  /** Dashboard folder; blank/absent = root. The bundled starters live in "Samples". */
  folder?: string
  /**
   * Saving this flow immediately replays its golden reference against the new graph. Off unless
   * asked for: every check is a real agent run with a real bill.
   */
  goldenAutoRun?: boolean
  /**
   * Remote approval over Slack: a stored credential holding the bot token, and the channel the
   * request posts to. A ✅/❌ reaction on the posted message approves/rejects the waiting run —
   * outbound polling only, so it works without a public URL.
   */
  approvalSlackCredentialId?: string | null
  approvalSlackChannel?: string | null
  /** Teams incoming-webhook URL. Notification only — Teams cannot carry the answer back. */
  approvalTeamsWebhook?: string | null
}

export interface FlowVersionInfo {
  version: number
  name: string
  createdAt: number
  /** Who saved it: an email, `"local"` when auth is off, null for revisions saved before this. */
  author?: string | null
  nodeCount: number
  edgeCount: number
  /** Change against the previous revision; both 0 on the oldest one, which has nothing before it. */
  nodesDelta: number
  edgesDelta: number
  renamed: boolean
}

// --- Pre-run doctor ---------------------------------------------------------

/**
 * One thing that would go wrong at run time. Every finding carries its own fix — a check that only
 * says what is wrong has moved the problem rather than solved it.
 */
export interface DoctorFinding {
  /** "error" (this will fail) or "warn" (this may fail, or will work oddly). */
  level: 'error' | 'warn'
  /** graph | credential | mcp | plugin | trigger | budget | runtime | cli | variables */
  area: string
  message: string
  fix: string
  /** The node it is about, or null for the whole flow. */
  where?: string | null
}

// --- Golden reference status ------------------------------------------------

/**
 * Where a flow stands against its golden reference. `stale` is derived from the graph the golden
 * run actually executed, so it needs nothing to be recorded or kept in sync. Only flows that HAVE
 * a golden run appear at all.
 */
export interface GoldenStatus {
  flowId: string
  runId: string
  stale: boolean
  autoRun: boolean
}

// --- Runtimes stdio MCP servers need ----------------------------------------

/** Whether a runtime (node, npm, pnpm, python, pipx, uv) is installed on this machine. */
export interface RuntimeStatus {
  id: string
  label: string
  found: boolean
  /** The tool's own `--version` line, proof it ran. Empty when not found. */
  version: string
  neededFor: string
  docsUrl: string
}

/** What one configured MCP command needs. `runtime` is null when its launcher is not one we manage. */
export interface RuntimeCheck {
  command: string
  runtime: RuntimeStatus | null
  satisfied: boolean
}

/**
 * What installing a runtime would run on this machine. A null `command` is not a failure: it means
 * there is no unattended installer worth running here, and `reason` says why.
 */
export interface RuntimeInstallPlan {
  runtime: string
  command: string | null
  reason?: string | null
}

// --- Flow memory ------------------------------------------------------------

/** One note an agent left for future runs of its flow (memory_append). */
export interface FlowMemoryNote {
  id: number
  /** The run that wrote it — may no longer exist; the note deliberately outlives it. */
  runId?: string | null
  note: string
  createdAt: number
}

/** A flow's persistent memory as the settings dialog shows it: newest first. */
export interface FlowMemoryView {
  /** False when the database is unreachable — the notes may exist but cannot be read. */
  available: boolean
  count: number
  notes: FlowMemoryNote[]
}


export interface AuthStatus {
  mode: string
  source: 'api-key' | 'auth-token' | 'local' | 'none'
  authenticated: boolean
  detail?: string | null
  hint?: string | null
  /** Installed release version (from the desktop shell). Absent in dev / hand-run backends. */
  appVersion?: string | null
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
  /**
   * Absent on a stdio server — the backend stores no URL for one, and a definition that came back
   * from the registry document has it as null outright. Typed as present it read as a string
   * everywhere and threw on the first `.trim()`.
   */
  url?: string
  credentialId: string
  authHeader?: string
  /** stdio transport — see McpNodeData: command/args/env instead of a url. */
  command?: string
  args?: string[]
  env?: Record<string, string>
}

/**
 * What an independent worker may reach through its MCP facade. Enforced by the backend on every
 * call — unlike node-level tool lists on the Claude path, which only steer.
 */
export type FacadeProfile = {
  id?: string
  name: string
  description?: string
  /** Case-insensitive substrings selecting the exposed tools; empty = all the node wired. */
  tools?: string[]
  /** Write-shaped tools are not exposed and not callable at all. */
  readOnly?: boolean
  /** Write-shaped tools answer "DRY RUN" instead of executing. Absent means ON (fail closed). */
  dryRun?: boolean
  /**
   * Tools this profile declares to be reads, whatever their name suggests.
   *
   * Read or write is guessed from the verb a name starts with, erring toward "write" because the
   * other direction executes something. That leaves real reads outside — `run_gaql_query` and
   * `run_report` are the most useful reads Google Ads and Analytics have, and read-only hides
   * both. Naming them here beats the alternative people actually take, which is to abandon
   * read-only for that worker.
   */
  readAlso?: string[]
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
/** One account in the organization, as the members screen reads it. Never carries a hash. */
export interface Member {
  id: string
  organizationId: string
  email: string
  /** VIEWER | OPERATOR | MEMBER | ADMIN — the ladder enforced on every request. */
  role: string
  enabled: boolean
  createdAt: number
}

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

/**
 * A credential the app signs in to, rather than one somebody pastes.
 *
 * The endpoints and the client are typed in from the provider's console — Google and the rest
 * support neither MCP-style discovery nor dynamic registration — and the tokens arrive from the
 * sign-in itself.
 */
export interface OAuthCredentialConfig {
  label: string
  authorizationUrl: string
  tokenUrl: string
  clientId: string
  clientSecret: string
  scope: string
  /** Provider quirks, appended to the authorization URL. Google needs access_type=offline. */
  authParams: string
}

export interface OAuthCredentialStatus {
  connected: boolean
  /** The address to register in the provider's console; where every first attempt fails. */
  redirectUri: string
  clientId?: string
  authorizationUrl?: string
  tokenUrl?: string
  scope?: string
  authParams?: string
  /** Separate from connected: without one, the grant dies when the access token expires. */
  hasRefreshToken: boolean
  expiresAt?: string | null
  error?: string
}

export interface OAuthSignInStart {
  ok: boolean
  redirectUri: string
  authorizationUrl?: string
  error?: string
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

/** One installed Claude Code plugin, as `claude plugin list` reports it. */
export interface PluginInfo {
  /** name@marketplace */
  id: string
  version?: string
  scope?: string
  enabled: boolean
}

export interface PluginMarketplace {
  name: string
  source?: string
}

export interface PluginsView {
  plugins: PluginInfo[]
  marketplaces: PluginMarketplace[]
}

/** One plugin a configured marketplace offers, as the install search shows it. */
export interface AvailablePlugin {
  /** name@marketplace */
  id: string
  name: string
  description?: string
  marketplace?: string
  installCount?: number
}

export interface McpToolList {
  ok: boolean
  tools?: McpToolInfo[]
  error?: string
  /** The server answered 401: a sign-in (the app's own OAuth) would fix it, so the UI offers one. */
  needsAuth?: boolean
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

/** One operation from a parsed OpenAPI spec, as the API node inspector lists it. */
export interface ApiOperationView {
  key: string
  method: string
  path: string
  description: string
  paramCount: number
  hasBody: boolean
  /** Anything that is not GET/HEAD — ticked individually, never in bulk. */
  write: boolean
}

/** An installed Agent Skill, as listed (contents stay server-side). */
export interface SkillInfo {
  id: string
  name: string
  description: string
  fileCount: number
}

/** An organization-level prompt variable: {{NAME}} in any flow's prompts becomes its value. */
// A type, not an interface: CrudPanel's generic wants Record<string, unknown>, which only
// structural type aliases satisfy implicitly.
export type Variable = {
  id?: string
  name: string
  value: string
  description?: string
}

/** A skill repository in the GitHub catalog. stars is -1 when GitHub could not be asked. */
export interface SkillRepo {
  fullName: string
  description: string
  stars: number
  pinned: boolean
}

/** An installable skill inside a catalog repository; path "" means the repo IS the skill. */
export interface SkillCatalogSkill {
  path: string
  name: string
  description: string
}

/** Measured Claude consumption on this machine, from the CLI's transcripts. */
export interface UsageWindow {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimatedUsd: number
  messages: number
}
export interface UsageSummary {
  available: boolean
  windows: { last5h: UsageWindow; today: UsageWindow; week: UsageWindow }
  models: Array<{ model: string } & Omit<UsageWindow, 'messages'>>
}
