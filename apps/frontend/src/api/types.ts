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
  /** Who pressed Run. Absent for a schedule, a webhook or a flow started by another flow. */
  startedBy?: string | null
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

/** Files and lines of a unified diff, counted by the backend from the patch text. */
export interface PatchStats {
  files: number
  additions: number
  deletions: number
}

/**
 * What one agent did to one repository checkout — the diff a person reads before trusting the
 * pull request the report links to. One per (node, folder): a worker with two repositories has
 * two, and the merge step, which clones every repository again, has its own.
 */
export interface RunDiff {
  nodeId: string
  label: string
  /** The checkout's directory name inside the node's workspace. */
  folder: string
  repoUrl?: string | null
  /** The unified diff; null when nothing changed, or when nothing has been read yet. */
  patch?: string | null
  stats: PatchStats
  /** Why the patch is not what it could be — directory gone, store capped it; null otherwise. */
  note?: string | null
  /** When the patch was last read, epoch millis; 0 before the first read. */
  takenAt: number
}

// --- Replay: a run's recorded decisions walked against the flow as saved today ---------------

/** What happened to one block then, and what would happen now. Closed vocabularies, painted
 * as-is by the canvas: then ∈ ran|failed|skipped|absent, now ∈ would-run|would-skip|unknown|
 * capability|gate|gone. */
export interface ReplayNode {
  nodeId: string
  label: string
  type: string
  then: string
  now: string
  divergent: boolean
  reason?: string | null
}

export interface ReplayReport {
  runId: string
  divergences: number
  nodes: ReplayNode[]
}

// ---- Flow / node data (canvas) ----
// `type` aliases (not interfaces) so they satisfy React Flow's
// `Record<string, unknown>` node-data constraint.

export type NodeKind =
  | 'agent'
  | 'coordinator'
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
  | 'mail'
  | 'note'
  | 'group'

/** The four grounds a note or a frame can wear. Few on purpose: a colour is a category, not a palette. */
export type AnnotationColor = 'yellow' | 'blue' | 'green' | 'pink'

/**
 * A sticky note: what the author wanted the next reader to know, on the canvas where they will
 * read it. Never compiled, never run — the backend looks past it entirely.
 */
export type NoteNodeData = {
  kind: 'note'
  text: string
  color: AnnotationColor
}

/**
 * A labelled frame that blocks are dropped into and move with. Its size lives on the canvas node
 * (`width`/`height`), and its members carry its id as their React Flow `parentId`; on the wire
 * that becomes `_size` and `_parent` next to `_pos`. Purely visual, like the note.
 */
export type GroupNodeData = {
  kind: 'group'
  label: string
  color: AnnotationColor
}

export type InputNodeData = {
  kind: 'input'
  /**
   * 'subflow' = this flow is started by another flow, never on its own.
   * 'watch' = files appearing or changing in a host folder start it.
   */
  mode: 'manual' | 'prompt' | 'cron' | 'webhook' | 'mail' | 'subflow' | 'watch'
  /** Shadow: triggered runs plan and stop — watch what a trigger WOULD do before trusting it. */
  shadow?: boolean
  prompt: string
  cron: string
  /** Secret issued by the provider; we verify against it, we never mint it. */
  secret: string
  /** Header (or query param) carrying the signature/token, e.g. `Linear-Signature`. */
  authParam: string

  // --- watch mode (a host folder) ------------------------------------------
  /**
   * The folder to watch. Must sit under the deployment's context roots — the same allowlist that
   * guards what agents may read — which the backend enforces and the doctor reports.
   */
  watchPath?: string
  /** Which files count: a glob such as `*.pdf`; blank means every file. */
  watchGlob?: string
  /** Seconds the folder must stay quiet before a batch of changes becomes one run. */
  watchDebounceSeconds?: number

  // --- published endpoint (any mode) ---------------------------------------
  /**
   * Whether a POST to /api/public/flows/{id}/run may start this flow. Orthogonal to the mode:
   * publishing adds a door, it does not move the existing one.
   */
  published?: boolean
  /**
   * The bearer token that door expects. Minted here, in the browser, when publishing is turned
   * on — the backend only ever compares it — and regenerated on request; the old one stops
   * working the moment the flow is saved.
   */
  publishToken?: string
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

/**
 * An agent block. Two kinds share the shape: the `coordinator` (exactly one per flow — the agent
 * the trigger addresses, that delegates and writes the final answer) and the `agent` it may
 * delegate to. Saved as `type: 'agent'` plus a `role`, the wire format every stored flow, the MCP
 * flow tools and the generator already speak — the split is a canvas fact, not a schema change.
 */
export type AgentNodeData = {
  kind: 'agent' | 'coordinator'
  /** The author turned the block's `on error` output on. Absent: hidden until a wire leaves it. */
  errorOutput?: boolean
  name: string
  model: string
  description?: string
  systemPrompt: string
  maxTokens: number
  effort: string
  /**
   * The library agent this block is a live reference to. When set, `name`, `model`, `effort`,
   * `maxTokens`, `systemPrompt` and `description` are read from the library at compile time —
   * the copies on the block are what the card and the inspector show, never what runs. Everything
   * else on the block (tools, skills, plugins, folders, retries, facade, escalation…) is the flow's
   * own. Unlinking keeps the copies and forgets the id.
   */
  libraryAgentId?: string
  /**
   * The library agent's version whose copy this block carries. The doctor warns when the library
   * is past it; "Take the current version" refreshes the copies and re-stamps.
   */
  libraryVersion?: number
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
   * Extra launches after a process failure, independent-workers path only. Blank = the
   * deployment's default; 0 = one attempt. Timeouts are never retried; a verifier rejection is
   * not a failure this counts (see the escalation model).
   */
  retries?: number
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
   * workspace, own instructions, own model, true parallelism. Repositories wired to a worker are cloned into its
   * workspace; its changes reach the merge step as patches, and the merge commits and opens the
   * pull request.
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
  /**
   * What a run does when the subscription's weekly allowance for non-interactive use is spent
   * (the meter says so at start, or the CLI refuses mid-run). **Coordinator only.** Blank: the
   * run is told and tries anyway. `api-key`: the hosted API with the machine's key, billed per
   * token. `local-model`: every agent of the flow runs on `allowanceFallbackModel`.
   */
  allowanceFallback?: '' | 'api-key' | 'local-model'
  allowanceFallbackModel?: string
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
  /** The author turned the block's `on error` output on. Absent: hidden until a wire leaves it. */
  errorOutput?: boolean
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

/**
 * A mail sent when the run finishes — or when the block it hangs off failed, or when the verifier
 * rejected, depending on which output the wire left from.
 *
 * Only ever a target: a mailbox has nothing to hand an agent. There is no body field, because the
 * body is whatever arrived on the wire — the run's final answer, the failing block's error and
 * log, or the verification report. The SMTP account lives here and only the password in a stored
 * credential, the split the IMAP trigger settled on: every save snapshots the flow's JSON, and a
 * secret on the node would fan out into every revision.
 */
export type MailNodeData = {
  kind: 'mail'
  label: string
  /** Comma-separated recipients. */
  to: string
  /** May carry {{flow}} and {{status}}, filled in when the mail is sent. */
  subject: string
  smtpHost: string
  smtpPort: number
  /** On: STARTTLS on 587. Off: implicit TLS on 465. Never plain, whichever is chosen. */
  smtpStarttls: boolean
  /** The login; blank signs in as the From address, which for nearly every provider is the same mailbox. */
  smtpUsername: string
  from: string
  /** Id of the stored mailbox password, never the password itself. */
  credentialId: string
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
  /** The author turned the block's `on error` output on. Absent: hidden until a wire leaves it. */
  errorOutput?: boolean
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
  /**
   * Extra launches after a process failure, independent-workers path only. Blank = the
   * deployment's default; 0 = one attempt. Timeouts are never retried; a verifier rejection is
   * not a failure this counts (see the escalation model).
   */
  retries?: number
  kind: 'merge'
  /** The author turned the block's `on error` output on. Absent: hidden until a wire leaves it. */
  errorOutput?: boolean
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
  /**
   * Extra launches after a process failure, independent-workers path only. Blank = the
   * deployment's default; 0 = one attempt. Timeouts are never retried; a verifier rejection is
   * not a failure this counts (see the escalation model).
   */
  retries?: number
  kind: 'verifier'
  /** The author turned the block's `on error` output on. Absent: hidden until a wire leaves it. */
  errorOutput?: boolean
  /** The author turned the verifier's `on rejected` output on. Absent: hidden until a wire leaves it. */
  rejectedOutput?: boolean
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
  | MailNodeData
  | NoteNodeData
  | GroupNodeData

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
  /**
   * Which of the source block's outputs this wire leaves from, or null for the main one.
   *
   * A block has two: what it produced, and the other way things can go — it failed, or a
   * condition did not hold. Null means the main one, which is what every wire drawn before this
   * existed meant.
   */
  sourceHandle?: string | null
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

/**
 * What this installation is running under — mirrors the backend record field for field.
 *
 * No license installed (or an unverifiable one) is every field null/false except `problem`, which
 * always names the fix rather than just the failure.
 */
/**
 * The rules an organization sets over every flow in it (Resources → Policies). Enterprise only:
 * on Team the record may exist but nothing in it is enforced, and the panel is read-only.
 */
export interface OrgPolicy {
  /** The organization id — set by the backend from the principal, never from this form. */
  id?: string | null
  /** Facade profile an independent worker runs behind when its node names none; '' = no default. */
  defaultFacadeProfileId?: string | null
  /** A worker with MCP servers wired, no profile and no default is refused at compile time. */
  requireFacade: boolean
  /** The most a run may do without asking: plan < default < acceptEdits < bypassPermissions; '' = none. */
  maxPermissionMode?: string | null
  /** A ceiling on what every flow of the organization spends together in a month; null = none. */
  monthlyBudgetUsd?: number | null
  /** A published flow's endpoint answers only once an admin approved its current token. */
  publishRequiresApproval: boolean
}

/** The policy as the panel reads it: the record, whether it applies, why not, and who may edit. */
export interface OrgPolicyView {
  policy: OrgPolicy
  /** The Enterprise gate: false on Team, where the panel is read-only and nothing is enforced. */
  enforced: boolean
  /** The gate's own sentence when not enforced; null when it is. */
  refusal: string | null
  /** Enforced, and the caller is an admin. */
  canEdit: boolean
}

/**
 * One flow's publish approval as the Input node needs it. The approval names a token, so a
 * regenerated token is simply not approved — nothing is cleared; the two just stop matching.
 */
export interface PublishApprovalView {
  /** Whether the organization requires an admin's approval at all. */
  required: boolean
  /** The token the SAVED flow carries — to tell an unsaved regeneration from a token nobody looked at. */
  savedToken: string | null
  approvedToken: string | null
  approvedBy: string | null
  approvedAt: number | null
}

export interface LicenseStatus {
  tier: string | null
  licensee: string | null
  /** Enterprise only; null on an individual (seatless) license. */
  seats: number | null
  /** ISO date string, or null on a perpetual (individual) license. */
  expires: string | null
  /** null unless the license has expired; the days left in the grace window otherwise. */
  graceDaysLeft: number | null
  valid: boolean
  problem: string | null
  /** True on the 14-day trial the website issues — a team license the panel counts down instead of naming. */
  trial: boolean
  /**
   * Every Enterprise feature, in the backend enum's order, with whether this license has it. The
   * same list whatever the tier, so the panel can show a free or Team install what the next tier
   * unlocks rather than a blank where the locks would be.
   */
  features: LicenseFeature[]
}

/** One line of "what this license unlocks": the feature's own label, and a tick or a lock. */
export interface LicenseFeature {
  key: string
  label: string
  allowed: boolean
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
  /**
   * Counts saves, from 1; the store assigns it. A block that links this agent stamps the version
   * it took, and is warned when the library has moved past it. Absent on a record from before
   * versions existed, which reads as 1.
   */
  version?: number
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
 * Who is signed in, and what to show if nobody is.
 *
 * Three states, not two. Signed in; not signed in; and an installation with no accounts at all,
 * which cannot ask anybody to sign in because there is nobody to be — that one gets the setup
 * screen.
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

/** A token for a machine: a name, a role no higher than MEMBER, and no seat. */
export interface ServiceAccount {
  id: string
  organizationId: string
  name: string
  /** VIEWER | OPERATOR | MEMBER — never ADMIN, so a token cannot mint tokens. */
  role: string
  /** The admin who minted it, when known. */
  createdBy: string | null
  createdAt: number
  /** The last request that presented the token, at minute resolution; null until the first. */
  lastUsedAt: number | null
  /** Set once revoked; the row stays as the audit of what could act as this organization. */
  revokedAt: number | null
}

/** The list, with what the create button needs to be honest about the Team cap. */
export interface ServiceAccountListing {
  accounts: ServiceAccount[]
  /** How many tokens still work. */
  active: number
  /** The cap in force (Team), or null where nothing caps it (free, Enterprise). */
  limit: number | null
  /** Why one more cannot be minted right now, or null while it can. */
  refusal: string | null
}

/** The one answer that carries the token: it is shown once and never stored. */
export interface CreatedServiceAccount {
  account: ServiceAccount
  token: string
}

/** One identity provider a person may sign in with, or could once it is registered. */
export interface SignInProvider {
  id: string
  /** What to call it on the button — "Microsoft", "Google", or whatever was configured. */
  name: string
  /**
   * Whether this installation has a registration for it.
   *
   * False is still shown: the button explains what it needs instead of going nowhere. An absent
   * button answers "can I sign in with my work account" with silence, which reads as no.
   */
  configured?: boolean
}

/**
 * A directory people could sign in with, and whether it is set up.
 *
 * `hasSecret` rather than the secret: what the screen needs is whether there is one, so it can
 * show a filled field without the value ever being readable back out of the API.
 */
export interface SignInProviderConfig {
  id: string
  name: string
  enabled: boolean
  clientId: string
  hasSecret: boolean
  tenant: string
  issuer: string
  displayName: string
  /** Microsoft restricts by directory; nothing else does, so nothing else asks. */
  wantsTenant: boolean
  /** Only a provider with no preset needs to be told where it lives. */
  wantsIssuer: boolean
  /**
   * Why this license does not offer the provider — a custom issuer on Team — or null. The row is
   * still here, with its client id, so a registration made under another license is not lost;
   * the screen shows it inactive and says which tier has it.
   */
  refusal: string | null
}

/** What the providers screen reads: the registrations, the address to register, and who they admit. */
export interface SignInProvidersList {
  providers: SignInProviderConfig[]
  redirectUri: string
  live: Array<{ id: string; name: string }>
  /** The domains whose people get an account on first sign-in, comma-joined; blank is any. */
  allowedDomains: string
  /** Set on Team, where automatic accounts are withheld: the refusal the field shows instead. */
  domainJitRefusal: string | null
}

/**
 * One thing about this installation somebody may change.
 *
 * The key is the configuration key it overrides, verbatim — one vocabulary for the thing however
 * it is set. `source` is what makes the screen honest: the same number means different things
 * depending on whether a person chose it, the deployment was started with it, or it is simply what
 * the code does.
 */
export interface SettingEntry {
  key: string
  group: string
  label: string
  help: string
  type: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'LIST' | 'SECRET' | 'CHOICE'
  restartRequired: boolean
  options: string[]
  source: 'STORED' | 'CONFIGURED' | 'DEFAULT'
  /** Empty for a secret, always: what it holds is never read back out of the API. */
  value: string
  hasValue: boolean
  /** Secrets only: stored, but sealed under a key this installation does not have. Enter it again. */
  locked?: boolean
  /**
   * Why a Team license holds this setting back, or null wherever it works. The row is shown
   * disabled with the sentence, so flipping "Send traces" does not save a switch nothing reads.
   */
  refusal?: string | null
}

/**
 * One account this browser can return to without signing in again.
 *
 * It got here by being signed into on this browser — with its own password or its own provider —
 * which is the whole of what switching back is allowed to rely on.
 */
export interface SwitchableAccount {
  userId: string
  email: string
  role: string
  /** The one this window is currently working as. */
  current: boolean
}

export interface SessionInfo {
  /** No accounts exist yet: the first launch, which asks for one instead of asking to sign in. */
  setupRequired?: boolean
  /** Every provider configured here. Empty on a deployment that signs in with passwords only. */
  providers?: SignInProvider[]
  /** Whether any provider is configured. Kept from when a deployment could have only one. */
  ssoEnabled?: boolean
  /** What to call the first one. Kept from when a deployment could have only one. */
  ssoName?: string
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
  /**
   * Sealed under a key this installation does not have — a reinstall that lost its keyring, a
   * second machine on a shared database. Still a credential: it keeps its id and every node
   * pointing at it; entering the value again is the whole repair.
   */
  locked: boolean
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
  /** False when the credentials table could not be reached. */
  available: boolean
  /**
   * True when this installation has a key and seals values before writing them; false when it
   * stores them as typed. The screen says which, because "encrypted" is only true of one.
   */
  encrypted: boolean
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
  /**
   * The lowest role that may read this base. Blank means everybody, which is what every base
   * created before this field existed meant and must go on meaning.
   */
  minRole?: string
}

export type KnowledgeDoc = {
  name: string
  /** Where it came from, when it came from a page. Absent for an upload, which has no way back. */
  sourceUrl?: string | null
  /** Who added it, kept as an email so the answer survives the account being deleted. */
  ingestedBy?: string | null
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

/** One question with a known answer, kept so a retrieval change can be falsified rather than felt. */
export interface EvalCase {
  id: string
  question: string
  expectedDocs: string[]
  createdAt: number
}

/** What retrieval did with one case. `rank` is 1-based; 0 means the answer never came back. */
export interface EvalCaseResult {
  id: string
  question: string
  expectedDocs: string[]
  rank: number
  found: string[]
  returned: string[]
}

/** A whole pass over the golden set, and the three numbers it produced. */
export interface EvalRun {
  topK: number
  reranked: boolean
  cases: number
  /** Share of questions where an expected document made the top k. */
  hitRate: number
  /** Share of all expected documents retrieved — lower than hitRate on a half-answered case. */
  recall: number
  /** Mean reciprocal rank: 1 for an answer at the top, 0.5 for second, 0 for a miss. */
  mrr: number
  results: EvalCaseResult[]
}

// --- Flow evaluations -------------------------------------------------------

/**
 * How a case decides. Three are free string checks; `llm` asks a model whether the answer
 * satisfies the expectation — the only judge that can grade meaning, and the only one that costs
 * a model call per case on top of the run itself.
 */
export type FlowEvalJudge = 'contains' | 'regex' | 'exact' | 'llm'

/** One case of a flow's dataset: what to run the flow with, and how to tell whether the answer is right. */
export interface FlowEvalCase {
  id: string
  flowId: string
  name: string
  input: string
  expected: string
  judge: FlowEvalJudge
  createdAt: number
}

/** What the form sends: the server owns the flow id and the creation time. */
export interface FlowEvalCaseInput {
  id?: string
  name: string
  input: string
  expected: string
  judge: FlowEvalJudge
}

/** One case in one evaluation. `runId` is null when no run could be started; `why` says so. */
export interface FlowEvalCaseResult {
  caseId: string
  name: string
  runId: string | null
  passed: boolean
  why: string
  /** An excerpt of the run's final answer; the whole answer is on the run. */
  output?: string | null
}

/**
 * One evaluation of a flow: every case run against one revision and judged. `cases` grows while
 * it is `running`; `flowVersion` is the headline, because a score is only worth something next to
 * the one from the previous version.
 */
export interface FlowEvalResult {
  id: string
  flowId: string
  flowVersion: number
  startedAt: number
  finishedAt?: number | null
  status: 'running' | 'done'
  cases: FlowEvalCaseResult[]
  passed: number
  total: number
}

/** The cross-encoder that reorders results by reading the question and the passage together. */
export interface RerankerStatus {
  state: 'NOT_DOWNLOADED' | 'DOWNLOADING' | 'READY' | 'ERROR'
  percent: number
  error: string
  sizeMb: number
  model: string
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
/** One calendar day in the machine's own zone. Present with zeros when nothing ran. */
export interface UsageDay extends UsageWindow {
  date: string
}

/**
 * Where this machine's runs stand against the plan's weekly allowance for non-interactive use.
 * Present only when the allowance is configured (Settings → Usage).
 */
export interface UsageAllowance {
  allowanceUsd: number
  /** Concentus runs on the subscription, last 7 days — the part of the allowance this app spent. */
  runsUsd: number
  /** Every Claude Code session on this machine in the same window, for scale. */
  machineUsd: number
  remainingUsd: number
  /** Whole percent of the allowance the runs used; may exceed 100. */
  percent: number
  state: 'ok' | 'warn' | 'exhausted'
}

export interface UsageSummary {
  allowance?: UsageAllowance
  available: boolean
  windows: { last5h: UsageWindow; today: UsageWindow; week: UsageWindow }
  models: Array<{ model: string } & Omit<UsageWindow, 'messages'>>
  /** The last seven days, oldest first — what turns three totals into a shape. */
  days?: UsageDay[]
}

/** One row of the audit trail — who did what to which subject, and when. */
export interface AuditEvent {
  /** The paging cursor: newest largest. */
  id: number
  /** Epoch milliseconds. */
  at: number
  /** A signed-in address, or `system:<trigger>` for an action nobody was signed in for. */
  actorEmail: string | null
  /** The role the actor held at the time; null for the system. */
  actorRole: string | null
  /** `subject.verb`, past tense: "flow.saved", "member.role_changed". */
  kind: string
  subjectType: string | null
  subjectId: string | null
  /** The subject's name as a person knows it; survives the subject's deletion. */
  subjectLabel: string | null
  /** A JSON object with whatever else the action kept — never a secret value. */
  detail: string | null
}

export interface AuditPage {
  events: AuditEvent[]
  /** A full page may have a next one; the client asks with `before` and finds out. */
  hasMore: boolean
  nextBefore: number | null
}

/** What the Audit panel needs before it draws: filters, the export gate, the retention in force. */
export interface AuditStatus {
  available: boolean
  kinds: string[]
  /** Null when export is allowed; otherwise the sentence to print on the disabled button. */
  exportRefusal: string | null
  /** Null means kept without limit. */
  retentionDays: number | null
  retentionReason: string
}

export interface AuditFilters {
  actor?: string
  kind?: string
  /** yyyy-mm-dd, inclusive. */
  from?: string
  to?: string
}

/** What one retention purge removed. */
export interface RetentionReport {
  days: number | null
  runs: number
  versions: number
  auditEvents: number
}
