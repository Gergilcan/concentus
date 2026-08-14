# Feature loop — autonomous work plan

This file drives an autonomous `/loop` session. Each loop tick: pick the **first unchecked
feature**, implement it completely, validate it, commit it, check it off here, and let the loop
schedule the next tick. Stop the loop when everything is checked.

**How to run me** (in a fresh Claude Code session at the repo root):

```
/loop Trabaja el siguiente item pendiente de FEATURES-LOOP.md. Un feature por tick: implementa, valida TODO en verde, commit, marca el checkbox, y sigue.
```

---

## Ground rules (non-negotiable)

- **Branch:** on the loop's first tick, create `features-loop` off `main` (reuse it if it
  already exists) and do ALL the work there — the endgame is one reviewed merge back to `main`.
- **One feature per commit.** Commit only when every gate below is green. Conventional message
  (`feat: …` / `fix: …`), body explains the why.
- **Validation gates, all of them, every feature:**
  - Frontend: `cd apps/frontend && pnpm typecheck && pnpm test`
  - Backend: `cd apps/backend && mvn -q clean test` (never skip; jar locking → close the app first)
  - E2E when the UI changed: `pnpm desktop:build` first (e2e runs the packaged jar), then
    `cd apps/frontend && pnpm test:e2e`
  - Rebuild for the user after backend/frontend changes: `pnpm desktop:build`
- **Never revert existing behavior.** The repo carries many recent features (themes, context
  tracking, plugins, run states). Extend, don't rewrite.
- **Comments explain WHY.** Match the codebase's comment style — design rationale, not narration.
- **Honesty.** If a feature needs a heuristic or has a limitation, document it in code and say it
  in the commit body. No silent gaps.
- **Danger zones** (read the surrounding comments before touching):
  - `model/FlowGraph.java` — Jackson record-constructor trap (`@JsonIgnore` ctors, `withId`).
  - `service/AgentRun.accrueUsage` — synchronized on purpose (fan-out races).
  - `service/LocalStreamEventHandler.applyContext` — context is a snapshot, never a sum.
  - Run states: `STARTING|RUNNING|IDLE|AWAITING_APPROVAL|AWAITING_ANSWER|COMPLETED|ERROR|TERMINATED`;
    `isInFlight`/`isTerminal` in `RunService` define cron blocking and eviction. Keep them coherent.
  - Theme system: CSS custom properties in `styles/global.scss` (3 palettes on `data-theme`),
    SCSS aliases in `styles/_theme.scss`. New colors go through tokens, never literals.
  - Buttons/spacing: `--control-h`, `--sp-1..5`. New UI uses them.

## Key map (orient fast, skip re-discovery)

- Backend Spring Boot: `apps/backend/src/main/java/com/concentus/`
  - `service/RunService.java` — run lifecycle, `runLocalTurn`, `hasActiveRun`, status classification.
  - `service/FlowCompiler.java` — canvas JSON → `AgentSpec`s.
  - `service/LocalClaudeExecutor.java` — CLI session, `buildArgs` (ordering is load-bearing, tested).
  - `service/FanoutExecutor.java` — worker processes; `service/CliProcess.java` — shared CLI runner.
  - `web/` — controllers; `web/McpJsonRpc.java` — shared MCP JSON-RPC plumbing.
  - `store/RunStore.java`, `store/FlowStore` (via `JsonStore`) — persistence.
  - `service/PricingTable.java` — per-model rates; `NotificationService` — failure webhook;
    `RemoteApprovalService` — Slack/Teams approvals (reaction-driven).
- Frontend React: `apps/frontend/src/`
  - `components/FlowsPage.tsx` + `flowsDashboard.ts` — dashboard; `FlowCard.tsx`.
  - `flow/FlowCanvas.tsx` + `state/store.ts` — canvas; `components/*Inspector.tsx` — node config.
  - `components/RunsPanel.tsx` + `Console.tsx` — executions; `CompareRunsModal.tsx` — golden compare.
  - `api/client.ts` + `api/types.ts` — backend contracts (mirror both sides).
  - `components/Spinner.tsx` — the loading indicator. No "Loading…" strings.
- Desktop shell: `apps/desktop/src/` (`main.ts`, `backend.ts` adopts a dev backend on 8734,
  `run-notifications.ts` native notifications, `updater.ts`).
- E2E: `apps/frontend/e2e/*.spec.ts` (per-worker jar + embedded Postgres; see `fixtures.ts`).

---

## Features (in order — first unchecked wins)

### 0. [x] Flow version history: authorship, a Versions tab, and run↔version linkage

**Goal:** Every save records WHO saved and the full snapshot; a proper tab lists the history and
restores any version (or jumps back to the latest); every execution is tied to the version it
ran, and opening an execution loads exactly that version's flow.
**Leverage — most of this EXISTS, extend it, do not rebuild:**
- Backend already snapshots every save: `FlowController.save` → `versions.snapshot(saved)`;
  `GET /flows/{id}/versions` (`FlowVersionInfo`), `POST /flows/{id}/versions/{v}/restore`
  (restore itself re-snapshots, so history never loses states).
- Runs already carry the exact flow they executed: `AgentRun.flowJson` +
  `GET /api/runs/{id}/flow`, and `openRun` (frontend `useFlowActions.ts`) already loads that
  snapshot onto the canvas. The linkage exists by VALUE; what's missing is by NUMBER.
- Frontend already has `VersionsModal` (`FlowModals.tsx`) + `api.listFlowVersions` /
  `api.restoreFlowVersion`.
**Sketch:**
1. *Authorship:* add `author` to the version snapshot — the signed-in email from the session
   (`OrgContext`/account) or `"local"` when auth is off. Backend: thread it through
   `versions.snapshot`; add to `FlowVersionInfo`. Show it in the list ("v12 · hace 2h ·
   gerard@…"). Danger: version rows are persisted JSON — additive field, old rows show "—".
2. *Version number on runs:* when a run starts, record the flow's current version number on the
   run (`AgentRun.flowVersion`, persisted in `RunStore` — additive column/JSON field). Show it
   in RunsPanel rows and the run header ("v12"). Opening a run keeps loading the stored
   `flowJson` (already exact); the number is the human-readable anchor between the two views.
3. *Versions tab:* promote the modal to a real tab — in the Studio's right inspector (a
   "Versions" tab next to Properties/Input/Output when no node is selected) or a top-level
   Studio side tab; each row: version, author, when, node/edge count, and two actions:
   **Restore this** (existing endpoint) and **Preview** (load read-only onto the canvas without
   saving, reusing the openRun snapshot-loading path). A "Back to latest" button appears
   whenever the canvas shows a non-latest version.
4. *Diff hint (cheap, honest):* per row show what changed vs previous (±nodes, ±edges, name) —
   computable from the stored JSONs server-side; no full visual diff in this feature.
**Tests:** backend — snapshot carries author; run records the version number; restore round-trip
keeps history append-only. Frontend — tab lists versions with author, restore updates canvas,
"Back to latest" appears only off-latest. E2E — save twice, restore v1, open a run and see its
version badge.
**Accept:** save → new version with author visible; restore any version and return to latest
without losing history; every run shows its version and opens as exactly that flow.

### 1. [x] MCP runtime doctor + add-server wizard (pipx/python, npm/pnpm/node)

*(Requested by Gerard on 2026-08-14, mid-loop — queued next.)*

**Goal:** An MCP server whose command needs a runtime the machine does not have must say so and
offer to install it with one button, exactly like the app already does for the `claude` CLI at
startup. And adding an MCP server should be a short wizard that installs what is missing and then
asks for the values that command actually needs (env vars, arguments) instead of dropping the user
into a raw JSON editor.
**Leverage — the pattern already exists, copy it:**
- `apps/desktop/src/claude-install.ts` — the "here is the exact command, press the button, watch
  every line of output" installer, with its security reasoning written down. `claude-cli.ts`
  probes `~/.local/bin` then PATH; `onboarding-page.ts` is the UI that offers it.
- Backend MCP stdio commands live on the MCP node / server definition (`McpInspector`,
  `McpJsonEditor`, the catalog in `McpCatalog.tsx` which already seeds `command`/`args`/`env`).
- `LocalClaudeSupport.command()` is the existing "is this tool on this machine" probe to mirror.
**Sketch:**
1. *Detect:* a small runtime probe (backend, so the same answer serves the UI and a run) for the
   runtimes stdio MCP servers actually ask for: `python`/`python3` + `pipx`/`uvx`, and
   `node` + `npm`/`pnpm`/`npx`. Report `{runtime, found, version, path}`. Derive the runtime a
   given server needs from the first token of its command — honest heuristic, document it.
2. *Offer:* when a server's runtime is missing, the MCP inspector and the catalog card show
   "X is not installed — install it" with the exact command shown before it runs, output streamed,
   same discipline as `claude-install.ts`. **Cross-platform from day one** (Windows, macOS, Linux):
   per-platform commands (winget/official installer/`npm i -g`, brew/apt where sane), and where
   there is no safe automatic path, say so and link the instructions rather than guessing.
3. *Wizard:* "Add MCP server" becomes stepped — pick from catalog or custom → runtime check with
   the install button → the parameters that server needs (env vars, args), each with its label and
   whether it is a secret (secrets go through `CredentialField`, never a raw value in the flow) →
   save. Existing JSON editing stays as the escape hatch; the wizard is the default path.
**Tests:** backend unit for the probe (stubbed process runner) and for the command→runtime
heuristic; frontend tests for the missing-runtime banner and the wizard filling env/args.
**Accept:** adding a `pipx`/`npx`-based MCP server on a clean machine walks the user from "missing
runtime" to a working, configured server without a terminal; the install command is always visible
before it runs; nothing is installed without an explicit press.

### 2. [x] Describe-your-flow: natural language → generated flow on the canvas

**Goal:** A text box ("Describe what you want automated…") that generates a complete, editable
flow — nodes, edges, agent prompts, trigger — from one sentence.
**Leverage:** flows are plain JSON (`BackendFlow` in `api/types.ts`, `FlowGraph.java`);
`normalizeImportedFlow` (`flowsDashboard.ts`) already sanitizes imported graphs; the backend
already drives the `claude` CLI (`LocalClaudeSupport`/`CliProcess`) for one-shot prompts.
**Sketch:** backend endpoint `POST /api/flows/generate {description}` → runs a one-shot CLI
prompt (`claude -p`, JSON-only output) with a system prompt that (a) documents the flow JSON
schema and node kinds with 2 real examples from `FlowLibrarySeeder` samples, (b) demands strict
JSON. Validate the result by compiling it (`FlowCompiler.compile` dry pass) before returning;
one retry on invalid JSON with the error appended. Frontend: entry point on the Flows page
header ("✨ Describe a flow") → modal with textarea + Spinner → opens the generated flow in
Studio (NOT saved until the user saves). Tests: backend unit for prompt-build + validation-retry
logic (stub the CLI runner); frontend test for the modal flow with a mocked api.
**Accept:** a sentence produces an editable flow in Studio; invalid model output surfaces a clear
error, never a broken canvas; nothing is persisted without the user saving.

### 3. [x] Golden regression on save (CI for flows)

**Goal:** Editing a flow whose golden reference exists offers/executes an automatic golden re-run
and flags regressions — "edit without fear".
**Leverage:** `RunController.startGoldenCheck` + `goldenRerun` already re-run the golden input
against the current flow; `CompareRunsModal` + `compareRuns.ts` (`pctDelta`, `peakContext`)
already diff two runs; run status classification is fresh.
**Sketch:** on flow save (`FlowController.save`), when a golden run exists for that flow and the
graph actually changed (compare node/edge JSON), record a `goldenStale=true` marker (in-memory
map or a field on the summary). Frontend: FlowCard shows a quiet "golden outdated — test now"
chip; clicking fires the existing golden re-run and opens the comparison when it finishes.
Optional setting per flow: "auto-run golden after each save" (flow field, like `budgetUsd`).
Tests: backend unit (stale marker set only on real graph change); frontend test for the chip.
**Accept:** editing a golden-covered flow surfaces the check without the user remembering it;
comparison opens with the existing modal; no auto-run unless opted in.

### 4. [x] Answer the agent from Slack (AWAITING_ANSWER remote)

**Goal:** When a run ends `AWAITING_ANSWER`, the question is posted to the flow's Slack channel;
a threaded reply becomes the run's next command. Full human loop from the phone.
**Leverage:** `RemoteApprovalService` already posts to Slack (stored bot credential + channel),
polls reactions, and settles approvals; `sendCommand` already continues a turn-based run;
`AWAITING_ANSWER` classification exists in `RunService.runLocalTurn`.
**Sketch:** extend `RemoteApprovalService` (or sibling `RemoteAnswerService` sharing its
transport) with `runAwaitingAnswer(run, question)`: post the question, then poll
`conversations.replies` for the first threaded reply; on reply → `sendCommand(run.id, reply)`
and post a ✔ confirmation in the thread. Same TTL/cleanup discipline as approvals. Wire it in
`runLocalTurn`'s finally, guarded once per question like `approvalRemoteNotified` (new flag,
reset when a new turn starts). Teams stays notification-only (documented, same as approvals).
Tests: unit with the fake transport pattern from `RemoteApprovalServiceTest`.
**Accept:** question arrives in Slack; a reply resumes the run; a second question posts again;
no double-posting for the same question.

### 5. [x] Pre-run doctor

**Goal:** One check surfacing everything that would fail at runtime, before running: missing
credential references, MCP servers without auth, un-installed plugins, invalid cron, exhausted
budget, missing claude CLI.
**Leverage:** every individual check already exists somewhere (`AgentSpec.validate`,
`resolveCredentialForLookup`, `McpOAuthStore.accessToken`, `PluginRegistry.list`,
`ScheduleService.normalize`, `enforceBudget`, `LocalClaudeSupport.command`).
**Sketch:** backend `GET /api/flows/{id}/doctor` → list of findings `{level: error|warn, area,
message, fix}` aggregating those checks (read-only, fast, no CLI spawns beyond cached ones).
Frontend: stethoscope/✓ button on the Studio toolbar + on FlowCard menu; results in a modal —
each finding names the fix ("Sign in to this server on the MCP node", …). Run is never blocked;
the doctor informs. Tests: backend unit per check with stubs; one frontend modal test.
**Accept:** a flow with a missing credential/plugin shows actionable findings in <1s; a healthy
flow says so plainly.

### 6. [x] Run timeline (Gantt)

**Goal:** A timeline view of a run: one bar per node (startedAt→endedAt), colored by status,
showing real parallelism and dead gaps. Killer view for fan-out.
**Leverage:** `NodeExec.startedAt/endedAt/status` already persisted and served by
`/api/runs/{id}/nodes`; `GraphMetrics.wallMs` exists; agent hues via `utils/hueOf.ts`.
**Sketch:** frontend-only. New tab or toggle in the Console/RunsPanel area ("Timeline"): pure
SVG/div bars over a time axis, node label + duration, hue per agent, tooltip with exact times
and tokens. Reduced-motion safe (no animation needed). Tests: a pure layout helper
(`timelineRows(nodes) → rows with x/width %`) unit-tested; component render test.
**Accept:** a fan-out run shows overlapping worker bars; a sequential run shows the chain; times
match the node inspector.

### 7. [x] Cost router (cheap model first, escalate on rejection)

**Goal:** Per agent option: try a cheaper/local model first; if the verifier rejects the output,
retry with the stronger model. "Guaranteed quality at minimum cost."
**Leverage:** per-model pricing (`PricingTable`), per-node model (`ModelField`), fan-out retries
+ verifier verdicts (`FanoutExecutor`, `verdict` on `NodeExec`) all exist.
**Sketch:** `AgentSpec.fallbackModelId` (empty = off). In `FanoutExecutor`'s retry path: first
attempt uses the primary (cheap) model; a verifier rejection (not a process failure) triggers
one retry with `fallbackModelId`, recorded on the node (`retries`, and a note in the verdict
reason). Scope honestly: fan-out workers only (the one path with a verifier); document that.
Inspector: "Escalation model" select in FineTuning, visible only on workers.
Tests: FanoutExecutor unit with stub starter — rejected → re-run with fallback model in args.
**Accept:** a rejected worker re-runs on the fallback model exactly once; costs show both
attempts; no escalation without a verifier.

### 8. [x] Headless CLI: `concentus run <flow.json>`

**Goal:** Run a flow from a terminal/CI without the desktop app: start backend, run, stream
events to stdout, exit with the run's status code.
**Leverage:** the backend jar is self-contained (embedded Postgres); flows are JSON
(export/import); `POST /api/flows` + `POST /api/runs` + WS events exist.
**Sketch:** a Spring Boot picocli-style entry (`--run flow.json [--input "text"]`) or a small
`scripts/concentus-run.mjs` that boots the jar on an ephemeral port, imports the flow, starts a
run with the input, tails `/ws/runs`, prints events, exits 0 on COMPLETED / 1 on ERROR /
2 on AWAITING_* (undecidable headless). Document in README. Tests: reuse the e2e backend boot
helper for one integration test.
**Accept:** `node scripts/concentus-run.mjs samples/docs-from-code.json --input "go"` works from
a clean checkout with the jar built; exit codes honest.

### 9. [ ] Outcome recipes (2-question wizard over Samples)

**Goal:** The Samples gallery reframed by outcome, each with a tiny wizard that asks only for
the missing pieces (mailbox credential, output channel…) and saves a ready flow.
**Leverage:** `FlowLibrarySeeder` samples; `SettingsModal` fields; `CredentialField`.
**Sketch:** per-sample manifest of "holes" (JSON: nodeId+field+question+control type); wizard
modal fills them into a copy of the sample. Start with 2 samples (mailbox assistant, daily
briefing). Tests: wizard fills holes → saved flow carries the answers.
**Accept:** a new user goes from empty install to a configured, runnable flow in 2 questions.

### 10. [ ] Ctrl+K command palette

**Goal:** Global palette: jump to flow/run/tab, run a flow, switch theme.
**Sketch:** frontend-only; overlay listing indexed actions (flows from store, views, theme);
fuzzy filter; keyboard navigation; `Ctrl+K`/`Cmd+K`. Respect focus (not inside inputs… actually
Ctrl+K in inputs is fine, it's not a typing key). Tests: opens, filters, invokes action.
**Accept:** everything reachable in ≤3 keystrokes + Enter; focus returns where it was on close.

### 11. [ ] Native notification for AWAITING_ANSWER

**Goal:** The desktop shell notifies "The agent asked you something" when a run enters
`AWAITING_ANSWER` (clicking opens the app on that run).
**Leverage:** `apps/desktop/src/run-notifications.ts` already polls runs and notifies on
completion states — add the new status to its trigger set with its own copy.
**Accept:** question → native toast within one poll interval; no repeat for the same question.

### 12. [ ] One-click sandbox (dry-run duplicate)

**Goal:** "Duplicate as sandbox": copy of the flow where every worker facade forces dry-run and
every write-capable MCP node gets the dry-run profile — try it without touching anything.
**Leverage:** facades with `dryRunEnabled` exist; duplicate exists.
**Sketch:** dashboard card menu action; the copy is tagged `sandbox` and its settings marked.
Honest limitation to document: only facade-mediated tools are simulated; direct CLI tools are
not — say so in the confirmation dialog.
**Accept:** sandbox copy runs; writes through facades are simulated; the limitation is stated.

---

## Loop bookkeeping

- After finishing a feature: mark `[x]`, commit (feature + this file's checkbox in the same
  commit), then continue or schedule the next tick.
- If a feature turns out to need a user decision (product call, external account), mark it
  `[?]` with one line explaining what's needed, skip to the next, and tell the user at the end
  of the tick.

## Endgame — when every item is [x] (or the rest are [?])

1. **Full validation once more, on the branch:** frontend typecheck + unit, backend
   `mvn -q clean test`, `pnpm desktop:build`, full e2e (`pnpm test:e2e`). All green or no merge.
2. **Merge:** `git checkout main && git pull`, merge `features-loop` (resolve conflicts on the
   branch first if main moved), push `main`.
3. **Release:** read the latest tag (`git tag --sort=-creatordate`), increment the rc number
   (`v0.1.0-rcN` → `v0.1.0-rc(N+1)`), tag the merge commit and push the tag — the Release
   workflow builds the installers and publishes the prerelease (feeds + blockmaps for
   auto-update) on its own.
4. **Summarize and stop the loop:** features shipped, anything left `[?]`, the tag that was cut,
   and the Actions URL to watch (https://github.com/Gergilcan/concentus/actions).
