# Concentus

A **desktop application** for designing, running, and steering Claude agents — visually.

**[Download for Windows or Linux →](https://github.com/Gergilcan/concentus/releases/latest)**

Install it and open it. There is no server to deploy, no database to provision, no API key to
paste, and nothing to configure — the app carries its own Java runtime and its own PostgreSQL, and
runs your flows on the Claude Code login already on your machine. The first launch asks you to
create an account, and that is the whole of the setup.

The same is now true of the jar on its own: `java -jar concentus-backend.jar`, with no environment
at all, starts its own database, generates its own encryption key, and asks for that first account.

### Why a desktop app and not a web service

Because the interesting half of this product only works on your own machine. Concentus runs flows
by driving the `claude` CLI against **your Claude subscription** — no API key, and no per-token
bill for what fits in your plan's allowance for non-interactive use (see [Costs](#costs)).
A hosted deployment cannot do that: the CLI would run in the server's container, which has no
Claude Code login, no access to your repositories, and no sight of the folders you want agents to
read. The same goes for pointing an agent at a local model on `localhost`, or at a directory on
your disk. Shipping it as an app you install is what makes those features real rather than
theoretical.

The trade-off is honest: flows run while the app is running. A scheduled or mail-triggered flow
fires when your machine is on and Concentus is open, not at 3am on a server.

### What is inside

- **`apps/desktop`** — the Electron shell. It owns the backend as a child process: picks a port,
  finds your `claude` CLI, keeps the credential key in your OS keyring, starts the runtime, waits
  for it to be ready, and only then shows a window.
- **`apps/backend`** — Spring Boot (Java 25). Compiles no-code **flows** into multi-agent sessions,
  launches them, streams output over WebSocket, and routes commands to running sessions. Serves the
  UI from inside its own jar. Also ships the original single-agent **YAML CLI**.
- **`apps/frontend`** — Vite + React 19 + TypeScript + SASS. The React Flow canvas, runs panel and
  streaming console. Built into the backend jar rather than deployed anywhere.
- **`apps/website`** — the marketing site, deployed separately.

```
concentus/
├── package.json            # pnpm workspace root
├── pnpm-workspace.yaml
├── apps/
│   ├── desktop/            # Electron shell (TypeScript)
│   │   ├── src/            # main process: backend supervisor, CLI + key resolution, windows
│   │   ├── build/          # icon source + rendered icon.png / icon.ico
│   │   ├── scripts/        # jlink runtime + payload staging
│   │   └── electron-builder.yml
│   ├── backend/            # Spring Boot API + YAML CLI (Maven, Java 25)
│   │   └── src/main/java/com/concentus/
│   │       ├── ConcentusApplication.java   # Spring Boot entry
│   │       ├── web/         # REST controllers + WebSocket handler
│   │       ├── service/     # FlowCompiler, RunService, ManagedFlowLauncher
│   │       ├── store/       # flow store + embedded PostgreSQL
│   │       ├── model/       # Flow/Run DTOs
│   │       ├── config/      # AgentSpec (YAML schema) + ConfigLoader
│   │       ├── runner/      # CLI runners (managed / self-hosted)
│   │       └── Main.java     # CLI entry point
│   └── frontend/           # Vite React TS SASS
│       └── src/
│           ├── flow/        # React Flow canvas + custom nodes
│           ├── components/  # Toolbar, Palette, Inspector, RunsPanel, Console, KnowledgePanel
│           ├── state/       # zustand store (canvas <-> backend flow)
│           ├── api/         # REST + WebSocket client, shared types
│           └── styles/      # SASS theme + globals
```

## Concepts

- A **flow** is a multi-agent orchestration graph. a **Coordinator** node (exactly one — the agent the trigger addresses) and the **Agent** nodes it delegates to,
  plus **MCP**, **Repository**, **SQL**, **Knowledge** and **API (OpenAPI)** capability nodes — an
  API node turns any REST API into typed tools from its OpenAPI spec, with each operation
  allowed explicitly — a **Run another flow** node, which is either a tool an agent may call and
  wait on (wire it to the agent) or a hand-off that fires when the run completes (leave it
  unconnected) — a **Send mail** node, wired out of a block's output, which mails whatever that
  output carries when the run ends — and, for fan-out flows, a **Merge** node. Connect a capability to
  an agent to grant access — and drag a node from the palette to place it wherever you want. Each sub-agent's *Delegate when…* description is what its delegator uses to route,
  and it receives only its own slice of the plan.
- **Delegation chains** — an agent delegates to the agents wired *behind* it, so hierarchies work,
  not just a flat roster under the coordinator:

  ```
  Tech Lead ──► Backend Engineer  ──► Backend Reviewer
            └─► Frontend Engineer ──► Frontend Reviewer
  ```

  Here each reviewer reviews only its own engineer's work. Wiring both reviewers to the coordinator
  instead would make them general-purpose peers reviewing everything.

  The hierarchy is worked out by walking outwards from the coordinator, so **edge direction doesn't
  matter** — whichever agent reaches another first becomes its delegator. Agents not reachable from
  the coordinator are left out of the run entirely. Two nodes may share a display name (two
  *Code Reviewer*s); they're registered under distinct names (`code-reviewer`, `code-reviewer-2`)
  so their definitions and logs stay separate.

  Scoping is guidance, not enforcement — *on this path*: every agent in the flow is registered
  with the CLI, so an agent is *told* which agents are its own to call, but nothing physically
  prevents it reaching another. If a delegation targets an agent that isn't in the flow, the
  console says so instead of failing silently. When you need real per-agent boundaries, that is
  what [independent workers](#independent-workers-fan-out-execution) exist for.
- An **Input / trigger** node sets how a flow starts:
  - **Manual** — run starts idle; you type the first message.
  - **Prompt** — pressing Run auto-sends a fixed prompt.
  - **Automatic (cron)** — saved flows run on a schedule.
  - **Webhook** — an external POST (e.g. a Linear issue/comment) starts a run with the event
    payload as input. Authentication is provider-agnostic: you name the **validation parameter**
    the provider sends its proof in, and paste the **secret** the provider issued (we never mint
    one). See [Webhook authentication](#webhook-authentication).
  - **Another flow** — the flow only runs when another one calls it, through a *Run another flow*
    node there. The child runs with its own budget and its own permission mode; a flow already
    running further up the chain is refused, and chains stop at three deep.
  - **Mail (IMAP)** — a run starts for each message matching the node's conditions: folder, from,
    subject, body, unread, flagged, has-attachments. The message can then be moved, flagged or
    marked read. See [Mail-triggered flows](#mail-triggered-flows-imap).
- **Executions** are the runs a flow produces (manual, prompt, cron, or webhook), listed with their
  trigger in the bottom panel. Outcomes are colour-coded: green succeeded, red failed, blue running,
  and **grey stopped** — a run you stopped by hand is neither a success nor a failure, so it is
  excluded from the success-rate figures rather than counted either way.
- **Editing the canvas** — `Ctrl/⌘+C` / `Ctrl/⌘+V` copy and paste the selected blocks (shift-drag to
  select several), and `Ctrl/⌘+D` duplicates them in place; the Inspector also has a **Duplicate**
  button. Connections *between* copied blocks are kept, and the clipboard survives switching flows so
  you can lift blocks from one flow into another. Copies get a fresh identity — a duplicated
  coordinator becomes an agent (a flow may only have one coordinator, and the palette refuses a second), names are made unique, and
  a copied webhook node starts with an empty secret. Flows themselves duplicate from the ⧉ button on
  their dashboard card. **✎ Notes** and **▭ Groups** annotate the drawing for the next reader — a
  sticky note in one of four colours, and a resizable labelled frame that blocks are dropped into
  and move with; both are saved with the flow, duplicated with their contents, and ignored by the
  run, the pre-run check and the replay alike.
- **Undo, tidy, guides** — every canvas change is undoable (`Ctrl+Z` / `Ctrl+Y`, or the ↶ ↷
  buttons), including deleting a block, which used to be irreversible short of reloading without
  saving. **⌗ Tidy** lays a hand-grown flow out automatically — the chain left to right,
  capabilities hung under their agents — and is itself one `Ctrl+Z` from undone. Dragging a block
  snaps it to its neighbours' edges and centres with Figma-style alignment guides. Wires are
  tinted by the output they leave from — the on-error path is red and dashed, the else path amber —
  so the failure path reads at a distance, not only at the handle. A focused block opens with
  `Enter`. `Ctrl/⌘+K` lists every block of the open flow as **Go to block: name (kind)** and
  centres the canvas on the one you pick; a theme-coloured **minimap** sits in the corner, toggled
  from the ▦ button next to the zoom controls (on by default on windows 900px and wider, and your
  choice is remembered).
- **Run** — how a flow executes depends on your credential:
  - **Local (subscription):** the `claude` CLI runs the coordinator + sub-agents (mapped to Claude
    Code subagents via `--agents`/`.claude/agents`) on your machine. Each command is a turn in the
    same Claude session.
  - **Cloud (API key):** Anthropic hosts a Managed-Agents session — one agent per node, a coordinator
    with a `multiagent` roster, a sandbox mounting the GitHub repos.
  - Either way output streams live and you send explicit commands to the running session.
  - **Per-agent tracking** — every console line is labelled with the agent that produced it, and
    can be filtered to one agent. Each block carries its own **Input** (the instruction it was
    delegated), **Output** (what it handed back), status and token count, so a sub-agent's work is
    never folded into the coordinator's. Work that can't be traced to a specific agent is left
    unattributed rather than blamed on the coordinator.
- **Agent library** — reusable agents, MCP servers, database connections and knowledge bases are
  managed under **Resources** and stored in the app's database (legacy YAMLs from
  `apps/backend/data/agents/` are migrated in on first launch). An agent block can **link to a
  library agent**: the block keeps the id and the version it took, and at every run the name,
  model, effort, max tokens, system prompt and routing text are read from the library — so an edit
  under Resources → Agents reaches every flow that links it, and twenty flows with "the same
  reviewer" are one reviewer rather than twenty copies drifting apart. Tools, skills, plugins,
  folders, retries, facade and escalation stay the block's own. Every save of a library agent is a
  new version; the doctor warns when a block is stamped behind it, the block shows the field-by-field
  diff and **Take the current version** re-stamps it, **Unlink (keep a copy)** turns the fields back
  into local values, and a deleted library agent is a compile error naming the block. **Copy fields
  once** is the old one-off copy. The MCP tab also has a **one-click
  catalog** — GitHub, Linear, Notion, Sentry, Stripe and more — each entry saying how it
  authenticates, because that is the question that actually stops people.
- **RAG** — two kinds of context, injected at run start:
  - **SQL source** node: its query runs (generic JDBC; PostgreSQL bundled, add other drivers to the
    backend pom) and the rows are injected into the connected agent's context. Test the query from
    the node inspector before running.
  - **Knowledge** node: pick a knowledge base (Resources → Knowledge — upload PDFs, Word, Excel,
    text, or whole folders with per-folder exclusions), and the passages most relevant to the run's
    prompt are injected into the connected agent, numbered and cited, recorded per node. The agent
    also gets a `search_knowledge` tool, so it can go back and ask a second question the preload
    could not have anticipated. See [Retrieval](#retrieval-how-a-passage-is-found).
- **Every executing block has a second output, off until you want it.** The main output carries
  what the block produced. The second is for the other way things can go, and stays off the card
  until you turn it on (hover the block, click the `+ on error` chip) or a wire already leaves it.
  On an agent, a sub-flow, an API call, a merge or a verifier it is **on error** — the branch wired
  there runs when *that block* failed (even if the rest of the flow carried the run to completion),
  and is handed the failure named by the block plus the block's own console log. A failure nobody
  pinned on a block — "every worker failed" — fires the coordinator's. A run whose failure was
  handled this way completes: somebody drew what should happen when it goes wrong, and it
  happened. With nothing wired there, a failure behaves exactly as before. The **verifier** has one
  more: **on rejected**, which fires once when its final word on any worker was a rejection and
  hands the branch a full verification report — every worker, rejected and accepted, with the
  verdict, the reason, its output and its console log — so a rejection can be mailed, filed or
  acted on instead of only read on a box afterwards (a condition after it filters by worker
  name). A **Send mail** node on any of these outputs mails what the wire carries — the final
  answer, the block's failure and log, or the verification report — with `{{flow}}` and
  `{{status}}` available in the subject, so "email me the report when the verifier rejects the
  Ads agents" is one box and one wire, with no agent asked to remember to send it. On a
  **condition** the second output is **else**, always drawn: the main branch runs when
  the test holds, the else branch when it does not — one test read from both sides, so the two
  branches cannot drift apart and no input can fall between them. After a for-each, the else
  branch receives the rejected items rather than dropping them.
- **Workers talk to each other** — each independent worker gets `share_finding` and
  `read_findings`. They are separate processes with separate context windows, which is what makes
  them independent and also what makes five of them research the same thing five times. A worker
  that establishes something the others would have to establish themselves says so; the others read
  it before starting anything that sounds done. Notes are scratch — they belong to this attempt,
  not to the flow — and the merge step reads them alongside the reports, because a note is often
  the reason a report says what it says.
- **A loop is interrupted, not waited out** — an agent calling the same tool with the same
  arguments for the same answer three times running is refused once, and told what it has been
  doing. Same tool, same arguments *and* same result: a status poll whose answer changes is never
  touched, which is the difference between waiting and looping. It resets after refusing, so an
  agent genuinely waiting is delayed rather than stopped.
- **Permissions** — set on the coordinator, because one CLI process runs the whole flow. Modes:
  bypass (default, the only one that works unattended), plan-only, and **"Ask me to approve the
  plan, then act"** — the run stops after planning, raises a desktop notification, and the console
  offers Approve/Reject; approving resumes the same session with permission to act. Sub-agents get
  the one permission Claude Code enforces per agent: a **tool allowlist** (`Read, Grep, Glob` on a
  reviewer means it cannot edit or run commands, whatever the flow's mode allows).
- **Remote approvals** — an approval-mode flow can also ask in **Slack**: the request posts to a
  channel and a ✅ / ❌ reaction decides it, no public URL anywhere (the app polls; rejection wins a
  tie, requests expire after 48h). **Teams** gets notified with an Adaptive Card but cannot answer
  back — receiving a reply would need a publicly reachable bot. See
  [docs/remote-approvals.md](docs/remote-approvals.md).
- **Shadow mode** — a triggered flow can run in shadow for its first days: it plans what it
  *would* have done but changes nothing, so you can watch a cron or webhook trigger act before
  trusting it. Manual runs stay real — you are present for those.
- **Golden runs** — mark one execution as a flow's known-good reference, then replay its exact
  input against the **edited** flow and diff the two runs side by side. The deliberate opposite of
  retry, which replays against the flow as it *was*.
- **Replay vs current** — golden runs compare outputs; this compares **decisions**. From any
  execution, walk its recorded per-block outputs through the flow as saved *today* — without
  running anything — and the canvas paints where the path would diverge: a block that ran and
  would now be skipped (a condition someone edited), a skipped branch the edited gate would now
  fire, recorded work whose block was deleted. Honest about its one limit, on the banner itself:
  it replays routing, not agents — where a decision needs output that was never recorded, it says
  "cannot be decided" rather than guessing.
- **Flow memory** — every saved flow keeps short notes that survive between runs
  (`memory_read` / `memory_append` tools): decisions taken, state reached, approaches that failed.
  Agents are told to read it before starting, so run N+1 stops redoing what run N learned.
- **Budgets & usage** — a flow can carry a monthly USD ceiling; at or past it, new runs are
  refused until next month (a run in flight always finishes). The **Usage** page shows measured
  Claude consumption on the machine, and every run prices each block at its own model's rate —
  shown **on the block itself** during and after a run (tokens and estimated cost on the status
  badge), so the canvas is the cost report when something comes out expensive.
- **The interface speaks English, Spanish and Catalan** — chosen next to the theme under
  Resources → Settings, with **Auto** (the default) following your system language, so most
  people never have to find the control.
- **Skills** — upload Agent Skills (a zip with a `SKILL.md`) under Resources → Skills and assign
  them per agent; they are installed into the run's workspace and the agent is told they are its
  own.
- **Copy as template** — share a flow's *shape* without sharing yourself: credentials, accounts,
  hosts and local paths are deleted (not blanked) from the JSON, while prompts, wiring and public
  endpoints stay. See [docs/templates.md](docs/templates.md).
- **MCP isolation** — each run sees **only the MCP servers wired into its flow**, passed to the CLI
  via `--strict-mcp-config`. Your personal Claude Code MCP list stays yours; a flow with no MCP
  nodes reaches none. `LOCAL_STRICT_MCP=false` restores the old inherit-everything behaviour.
- **Templates** — six starter flows come installed: a PR review crew, a cron daily briefing, a
  mailbox assistant, webhook issue triage, a docs writer, and the original quote-to-Holded flow.
  The ones needing configuration ship disabled and say on themselves what to set.

## Independent workers (fan-out execution)

The single shared session above has one structural limit: everything in it is *steered*, not
*enforced* — one CLI process runs the whole flow, so folders, MCP servers and rosters are shared
by construction. Setting the coordinator's **Execution** to **Independent workers** trades that
model for real boundaries: **one `claude` process per worker**, each with its own workspace, its
own instructions (never the whole flow's context), its own `--add-dir` grants, its own model —
and true parallelism, where subagents in a shared session run one at a time. Workers cannot
delegate (no Task tool) and cannot run shell commands; the fan-out is one level deep by
construction.

One ceiling holds over all of it: **`execution.max-processes`** (Resources → Settings, default
10) caps the total `claude` processes on the machine, whichever run or fan-out started them. The
per-pool limits multiply — eight concurrent runs of four workers would be thirty-two processes,
each hundreds of MB — and this is the cap on the product. A worker that does not fit waits for a
slot, with a line in its run saying exactly that; raising the setting frees queued work without a
restart.

- **The drawn sub-agents are the plan.** Each runs the turn's instruction as its own process, and
  the run ends with a combined report that names failed workers instead of hiding them.
- **No sub-agents drawn? The coordinator plans.** It runs first as its own process and must
  submit the work items through a `plan_submit` tool: unique ids, a self-contained prompt per
  item, at most `workers.max-items` (default 8), and **no two items may declare the same file** —
  parallel processes writing one file is silent corruption, so overlapping plans are rejected
  with the items and the path named, and the coordinator repartitions. Plan-born workers appear
  on the canvas as dashed boxes with live status, tokens and cost; they are part of the run, not
  the drawn flow. By default the planner is read-only exactly when it has workers wired to it —
  a coordinator with a crew distributes, a solo one works — and the node can force either shape.
- **Facade profiles** (Resources → Facades) are how workers reach MCP — and the first per-agent
  tool boundary Concentus actually *enforces*, on every listing and every call, rather than
  writes into a prompt. A profile is an allowlist plus two dials: **read-only** (write-shaped
  tools are not exposed and refuse to run) and **dry-run** (writes are simulated — the worker
  gets back "DRY RUN — nothing was executed" and reports the action as proposed). Dry-run is ON
  unless deliberately cleared, and what counts as a write fails closed: any tool name that is not
  clearly a read is treated as one. A worker with MCP nodes but no profile gets **no** MCP tools
  at all. The real MCP URLs and credentials never enter a worker's process: everything goes
  through a per-worker endpoint with its own token.
- **The Verifier node** runs after every worker and *before* the merge, with the workers'
  objective inverted: not "find the strongest answer" but "find the reason this one should be
  rejected". Worker and judge sharing an objective is how plausible-but-wrong output sails
  through — the opposition is the point. It reads the workers' real workspaces (judge what they
  produced, not what they claimed) but cannot edit or run commands; its only tool is
  `verdict_submit`, which must cover **every** output — accept, or reject with the reason — and
  the verdict has teeth: a rejected output never reaches the merge. The kill and its reason land
  on the worker's box, and a verifier that errors or never submits stops the run as UNVERIFIED
  rather than passing outputs along as judged. Its **on rejected** output hands a flow the full
  verification report when anything was rejected — the way to be told *why*, with the log, instead
  of finding out on a box. At most one per flow.
- **The Merge node** runs after every worker: one more process that receives all their reports
  (failures included), reads their workspaces, and reconciles them into the run's answer. It is
  the only fan-out process with shell access — running the tests belongs to the one step whose
  job is verifying the combined result, not to N unattended workers.
- **The run console reports the graph, not just the transcript**: a fan-out run shows how many
  workers ran and failed, how much retrying propped the run up, the verifier's kill rate, and
  the wall-clock versus sequential time — the parallelism the fan-out actually bought.

**Repositories in workers.** A repository node wired to a worker is cloned into that worker's own
workspace — one clone per worker, so two workers can never write one working tree at the same
time. Workers have no shell, so they edit files and cannot commit: when a worker finishes,
Concentus takes everything it changed in each checkout as a patch and hands the patches to the
merge step, which has its own fresh clones and a shell. The merge applies them (`git apply
--3way`), runs the checks, and commits, pushes on a branch and opens the pull request — exactly
the hand-back the shared session performs, done once and after verification instead of N times
unverified. Plan-born workers get every repository on the canvas.

**Items that wait for others.** A plan item may list `dependsOn`: it starts once those items have
finished and receives their reports appended to its prompt; a dependency that failed fails it
without a launch, and the box says which. The plan check refuses unknown ids and loops, and two
items ordered this way may declare the same file — the rule against shared files exists to stop
concurrent writes, and ordered steps never write concurrently. The planner is told to prefer
parallel items and to reach for `dependsOn` only when a step genuinely needs another's result.

One current limit, said where you can plan around it: workers run against your Claude login —
pointing them at a local model gateway is future work.

## Install

Download the installer for your platform from
**[the latest release](https://github.com/Gergilcan/concentus/releases/latest)**:

| Platform | File | Notes |
|---|---|---|
| **Windows** | `Concentus Setup <version>.exe` | Per-user install; no admin rights. SmartScreen warning is normal (unsigned) — "More info → Run anyway", or install via scoop/winget below |
| **macOS** | `Concentus-<version>-arm64.dmg` (Apple Silicon) or `-x64.dmg` (Intel) | Signed and notarized; drag into Applications |
| **Linux** | `Concentus-<version>.AppImage` | `chmod +x` and run it — nothing to install |
| **Linux (Debian/Ubuntu)** | `concentus_<version>_amd64.deb` | `sudo apt install ./concentus_<version>_amd64.deb` |

There are no prerequisites. Java and PostgreSQL are inside the installer — the app does **not** use
or interfere with any Java or PostgreSQL you already have.

### First run

To run flows on your Claude subscription, sign in to Claude Code once, in a terminal:

```bash
claude          # then /login
```

**The app tells you if this is missing rather than letting you find out later.** On launch it
checks for the CLI and for a login, and if either is absent it opens a page saying so, with a
**Check again** button and a **Locate claude…** picker for the case where it is installed somewhere
discovery does not reach. It is a prompt, not a gate — the canvas works without Claude, so
**Continue without it** is a real choice, and "Don't check on future launches" makes it stop asking.

Concentus finds the CLI itself, including the case a desktop launcher usually gets wrong: an app
started from a launcher does not inherit your shell's `PATH`, so a perfectly working `claude`
becomes invisible to it. The app resolves it through a login shell instead.

To use the cloud API rather than your subscription, set `ANTHROPIC_API_KEY` in the environment the
app starts in — the first-run check then stays out of your way, since a local login is not needed.

The first launch takes about ten seconds longer than later ones: it unpacks the database and
initialises it. After that, start-up is a couple of seconds.

### Running in the background

Closing the window can keep Concentus alive in the system tray, so cron, webhook and mail triggers
keep firing — enable **Run in background** from the tray icon's menu, and optionally **Start at
login**. The app checks GitHub Releases for updates every few hours and installs them on quit;
desktop notifications tell you when a background run finishes, fails, or is waiting for your
approval.

### Where your data lives

Everything is under one folder, which is also all an uninstall needs to remove:

| Platform | Folder |
|---|---|
| Windows | `%APPDATA%\Concentus` |
| Linux | `~/.config/Concentus` |

It holds the `pgdata` database directory — flows, executions, the agent library, MCP definitions,
knowledge bases and credentials all live in that database — the downloaded embedding model, and
`logs/` — `desktop.log` from the shell and `backend.log` from the backend. Those two logs are the
first place to look if something fails to start; the failure window shows the tail of them and has
a button to open the folder.

The key that encrypts stored credentials is **not** kept there. It is generated on first launch
into your OS keyring (DPAPI on Windows, libsecret on Linux), so a copied app-data folder does not
carry your mailbox passwords and API tokens with it.

## Develop

```bash
pnpm install          # once, from the repo root
pnpm desktop          # build frontend + jar, then launch the app
```

`pnpm desktop` is the full path — it builds the frontend, packages it into the backend jar, and
starts Electron against `apps/backend/target/concentus-backend.jar`. Rebuild after changing backend
or frontend code.

For a faster loop on the UI, with the changes appearing **inside the real desktop app**:

```bash
pnpm desktop:dev      # Vite (hot reload) + the Electron shell, together
```

The shell starts its backend as always, detects the Vite dev server, and loads the window from it
instead of the jar's baked UI — frontend edits appear on save, in the actual app. Vite proxies
`/api` and `/ws` to the shell's backend (8734), so cookies and websockets behave exactly as
packaged. Backend (Java) changes still need `pnpm desktop:build` — and so does the first run, so
there is a jar for the shell to start. Without Vite running, the same shell falls back to the
baked UI, which is as old as the last build — that fallback is why "why don't my changes show?"
used to be the whole experience.

There is also a browser-only loop, no Electron involved:

```bash
pnpm dev              # backend (spring-boot:run) + Vite dev server :5173, in a browser
```

Requirements for development (not for using the app): **JDK 25** and Maven, **Node 24+** and pnpm.
A backend started by hand does not activate the `desktop` profile, so it has no embedded database —
point `PERSIST_DB_*` at a PostgreSQL of your own, or accept that stored credentials and mail
triggers report themselves unavailable. See [.env.example](.env.example).

### Tests

```bash
cd apps/backend && mvn -B clean test    # backend (JUnit; store tests start a real embedded PostgreSQL)
pnpm --filter frontend test             # frontend units (vitest)
pnpm --filter frontend test:e2e         # UI end-to-end (Playwright, once: pnpm exec playwright install chromium)
```

The UI suite drives the real thing, in parallel: each Playwright worker boots its own copy of the
packaged jar (`e2e/backend.ts`) with its own embedded PostgreSQL on a scratch data directory —
isolation by construction, so workers can create and delete flows without racing each other. The
specs walk the four views — dashboard, Studio canvas, every Resources tab, Usage — through the
actual API. It needs the jar built first, frontend included: `pnpm --filter frontend build`, then
`mvn -B clean package -DskipTests` in `apps/backend`. CI runs it on every push.

## Building the installers

```bash
pnpm --filter frontend build          # UI, copied into the jar as static resources
cd apps/backend && mvn clean package  # the jar
cd ../desktop && pnpm package         # jlink runtime + installer
```

`pnpm package` runs [scripts/prepare-payload.mjs](apps/desktop/scripts/prepare-payload.mjs), which
`jlink`s a Java runtime into `resources/jre` and stages the jar beside it, then hands both to
`electron-builder`. Output lands in `apps/desktop/release`.

**Build on the platform you are targeting.** Both the Java runtime and the PostgreSQL binaries
inside the jar are native code, and the jar's binaries are chosen by an OS-activated Maven profile,
so a jar built on Windows is only valid on Windows. This is why
[.github/workflows/release.yml](.github/workflows/release.yml) runs the whole build once per
platform rather than building a jar once and packaging it twice.

Pushing a `v*` tag builds both installers and publishes them to a GitHub release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Running the workflow manually builds the same installers and leaves them as artifacts without
publishing, which is how to test a packaging change without spending a version number.

Expect roughly a 290MB installer: Electron, a Java runtime, PostgreSQL and the backend jar are each
a large fraction of it. The runtime is built with `ALL-MODULE-PATH` rather than a `jdeps`-derived
module list, deliberately — this application resolves most of its classes reflectively (Spring's
component scan, JDBC driver registration, JNA, the crypto providers TLS selects at runtime), and a
module missed that way does not fail the build, it fails later on a user's machine.

## Models

**The model named on an agent decides where that agent runs.** Not a global setting, not a
credential — the model. So one flow can put its coordinator on Claude and a summarising sub-agent
on a model on your own GPU, and neither knows about the other.

| Model named | Backend | How it runs |
|---|---|---|
| a Claude id, with a Claude Code sign-in | **Local** | the `claude` CLI on your subscription, on this machine |
| a Claude id, with `ANTHROPIC_API_KEY` | **API** | Anthropic's hosted Managed-Agents session |
| an id your own model server reports | **Self-hosted** | your GPU, via OpenAI-compatible HTTP |

`ANTHROPIC_AUTH_MODE` picks between the two Claude paths: `auto` (default — key set means API,
otherwise local), `local`, or `api-key`. The toolbar badge shows which is active, and
`GET /api/auth/status` reports it. With neither credential the designer still runs, and a flow on a
self-hosted model runs too — **no Claude credential is needed for that path at all**.

A flow names a model (`claude-opus-4-8`, `qwen3:14b`, …). The field is free text, so a model
released after this list still works — the picker is a shortcut, not a whitelist.

### Running on your own hardware

Any server speaking OpenAI's `/chat/completions` works: **Ollama**, llama.cpp's `llama-server`,
**vLLM**, LM Studio. A 14B model on a 16 GB card is a realistic target at 4-bit.

Install one and point Concentus at it. Since the app runs on your machine, `localhost` means your
machine — there is no container boundary to cross:

```properties
LOCAL_MODEL_BASE_URL=http://localhost:11434/v1
```

Mind the `/v1`: Ollama's OpenAI-compatible surface is there, not at the root. Set it in the
environment the app starts in.

**There is no separate embedding server.** Ollama serves both the chat and embedding models on one
URL; only the model name differs per request — `/v1/chat/completions` with `qwen3:14b`,
`/v1/embeddings` with `bge-m3`. That is the whole wiring, and `LOCAL_MODEL_BASE_URL` is the only
address involved. The MCP node shows which ranking you are actually getting, so you can see it
working rather than assume it.

Two things worth knowing. Ollama serves a **2048-token context by default** whatever the model
supports, and truncates past it without saying so — start it with
`OLLAMA_CONTEXT_LENGTH=32768 ollama serve`. And pointing the `claude` CLI itself at a local model
needs an Anthropic-format gateway such as LiteLLM in front of Ollama; be clear-eyed about what that
means, because Claude Code's agent loop driving a 14B model works, but tool-calling discipline is
the first thing to suffer.

Two honest caveats about the merged stack. The **reranker is included but unused** — Concentus's
tool search ranks by embedding distance and stops there; it is in the file because it is the
cheapest quality win for a *document* RAG pipeline, and it costs no VRAM. And **LiteLLM is a second,
separate path**: Concentus's self-hosted backend talks OpenAI-format to Ollama directly and never
goes through it. Point `ANTHROPIC_BASE_URL` at `http://litellm:4000` only if you want Claude Code's
own agent loop driving a 14B model — it works, and tool-calling discipline is the first casualty.

That's the whole setup. The picker then lists **whatever the server reports it has loaded**, under
*On your hardware* — asked rather than configured, because what you can run is whatever you have
pulled, and a hand-written list would offer a model that would 404 at launch. Pull something new
and it appears within a minute.

Two things that cost an afternoon if missed:

- **The `/v1` suffix.** Ollama's OpenAI-compatible surface is at `:11434/v1`, not at the root.
- **Ollama's context window defaults to 2k** regardless of what the model supports, and truncates
  past it *silently*. A system prompt plus tool definitions passes that immediately, and the symptom
  is a model that appears to ignore its instructions rather than any error. Raise it on the server:
  `OLLAMA_CONTEXT_LENGTH=32768 ollama serve`.

**What works here:** delegation between agents (as tool calls), file tools scoped to the agent's
context folders, SQL/RAG context, MCP servers, and every trigger — mail, cron, webhook.

#### Large MCP servers: the agent searches instead of being handed everything

A tool definition is a JSON schema in the prompt. Holded's MCP server exposes **338** of them —
roughly fifty thousand tokens before the conversation starts, which no self-hosted context will
hold. The model server truncates without saying so, and the model then reports having only the few
that survived, which reads as a confused model rather than a starved one.

So above `LOCAL_MODEL_TOOL_SEARCH_THRESHOLD` tools (default 25) the agent is given **one** tool,
`search_<server>_tools`, instead of the catalogue. It describes what it needs — *"find a customer
by tax id"* — gets back the few matching definitions with their full parameters, and calls the tool
by name. **Every tool stays callable**; only the listing stops being carried in every prompt.

Ranking is semantic when it can be. The tool corpus is embedded once per server and stored in
pgvector, which is what connects *"who are my customers"* to `list_contacts` — a phrase sharing not
one word with the tool's name.

```bash
ollama pull bge-m3      # an EMBEDDING model; a chat model 404s or returns nonsense
```

**The embedded database does not ship pgvector**, so in the desktop app this half is simply not
available. Without the extension, or without the embedding model, ranking **falls back to word
overlap** and the run says which it used — worse results, still useful, and nothing breaks. Re-indexing is keyed on a hash of the server's tool list,
so it happens when the server's tools change and not merely because a run started.

Picking tools by hand on the node always wins over search: an explicit selection was a decision, and
for eight tools a search round-trip is pure overhead.

#### MCP servers that use OAuth

A server like Holded's authenticates with **OAuth**, not a pasted token — and the `claude` CLI keeps
its own authorization for it. So the same server works on the Claude backends and answers **401**
here, which reads as a broken flow rather than a missing sign-in. It isn't: the grant simply belongs
to the CLI, not to this application.

Press **Sign in to this server** on the MCP node. Concentus then does the flow itself — discovers
the authorization server from the `WWW-Authenticate` challenge, registers as a client dynamically
(there is no console to create an app in), and runs authorization code with PKCE. Approve it once in
the tab that opens; the grant is stored encrypted and renews itself.

The browser is redirected back to `MCP_OAUTH_REDIRECT_BASE`. **In the app you never set this** —
the shell passes the port it actually bound, and deliberately keeps that port stable between
launches, because this URL is registered with the authorization server and a port that moved would
invalidate every MCP sign-in you had already done.

It only needs setting when you run the backend yourself, where it must be the address your browser
actually uses, minus the path:

| How you run it | Set it to |
|---|---|
| The desktop app | handled for you — `http://127.0.0.1:8734` |
| Vite dev server | `http://localhost:5173` |
| backend opened directly | `http://localhost:8080` |

Get it wrong and the sign-in dies at the very last step with `ERR_CONNECTION_REFUSED`, after the
code has already been issued and spent — the authorization server sent the browser somewhere
nothing is listening. The Sign in button checks for that mismatch before it opens anything and says
what to set, but only the running backend's value goes on the wire, so it still needs restarting
after a change.

Servers that take a plain token need none of this: fill in the token field and they work headlessly.

**What does not: bash, and therefore repository nodes.** Cloning, committing and opening a PR all
run through git on a shell. Flows can be triggered by public webhooks, so model-generated shell
commands on the host is a remote-code-execution path; Claude Code carries its own permission model
and a trust boundary you accepted by installing it, and none of that transfers when this app spawns
a process itself. Doing it safely needs an isolation boundary, which is a design decision rather
than a default. A flow that edits repositories belongs on Claude; the run says so out loud rather
than quietly skipping the node.

One more caveat worth stating plainly: a 14B model is not a frontier model, and the gap shows up as
**tool-calling discipline** rather than prose quality — repeated calls, malformed arguments,
answering in text where it should have called something. `LOCAL_MODEL_MAX_TURNS` bounds that, and
hitting the cap is reported rather than passed off as an answer.

### Costs

The model picker shows each model's rate, and a run reports estimated cost per block and in total —
all read from `pricing.models` (`id:inputPerMTok:outputPerMTok`), so the number you see while
choosing is the one the estimate uses.

Claude rates ship configured, taken from Anthropic's published pricing. **They change** — re-check
rather than trusting them indefinitely, and add anything you use that isn't listed:

```properties
PRICING_MODELS=...,my-model:<inputPerMTok>:<outputPerMTok>
```

A cache read is weighted at 0.1× input, which is Anthropic's cache-read rate.

An unlisted model falls back to the flat `pricing.input-usd-per-mtok` / `output-usd-per-mtok` pair,
and the picker says so rather than showing a figure that isn't real. On the **API** the figure
approximates the real charge. On a Claude **subscription** it is an equivalent-usage estimate for
comparing runs, not a charge — but not "free" either: since mid-2026 Anthropic meters
non-interactive Claude Code use (which is how Concentus runs the CLI) in its own allowance, separate
from your interactive sessions, and past it runs stop until the window resets. The exact shape of
that allowance has changed more than once; the Usage page in the app measures what this machine has
consumed so you can see it coming, and a flow can fall back to an API key or a self-hosted model
when the allowance is spent — set it on the Coordinator (**When the weekly allowance is spent**):
the run then starts on the fallback when the meter says spent, and a run the CLI refuses mid-way
for the allowance ends as a failure and continues as a new run on the fallback, which says where
it came from.

## Persistence (PostgreSQL)

Runs, their events, node outputs and session ids are stored in PostgreSQL so they survive a
restart and can be continued. The backend **creates its own schema on startup**
(`create table if not exists`), so an empty database is all it ever needs — no migrations, no
seed scripts.

**The app ships its own.** Real PostgreSQL binaries are inside the installer, started as a child
process of the backend against `pgdata` in your app-data folder and stopped with it. Not an
emulation — the same server, so `jsonb`, upserts and partial indexes work exactly as they do
anywhere else.

There is nothing to install, nothing to provision, and **no database user or password to choose**,
including on first run. The server listens on a loopback port picked at startup and accepts local
connections without one, which is what an instance started, used and stopped by a single process
should do. A password stored beside the data it protects, on the same disk, readable by the same
user, would add a setup step and no security.

There is also no seed file and no migration step. Every store issues `create table if not exists`
on startup and the default organization row is created with them, so an empty directory becomes a
working database on its own — and first run and upgrade stay one code path rather than two that
can drift apart.

Notes:

- **Persistence is always on and is not configurable.** It used to be, and the switch was a trap:
  it only ever covered run history, while the credential, account and mail stores needed a database
  regardless — so turning it off produced a build where stored secrets silently did not work.
- **pgvector is not included.** These binaries do not ship it, so MCP tool search ranks by word
  overlap rather than semantically. The app detects this and says so rather than pretending.
- **Running the backend by hand gets the same embedded database.** It used to be behind the
  `desktop` profile, so `java -jar` pointed at `localhost:5432` and refused to start without a
  PostgreSQL already there — an installation step before anything could be seen at all. Name a
  database and it is believed: `PERSIST_DB_URL` in the environment, or an external URL under
  *Resources → Storage*, and the embedded server is never started.
- An unreachable database never crash-loops the app (`initialization-fail-timeout=-1`); each store
  logs and reports itself unavailable instead.

### Moving to a company database

Pointing Concentus at a shared PostgreSQL always worked; arriving there with an empty one is what
did not. *Resources → Storage* copies everything across — table by table, over the connection the
app already has, no `pg_dump` and nothing to install, which matters on a desktop install where the
source database is embedded inside the application and is not a thing you can dump.

**Nothing is ever deleted.** Rows go in with `on conflict do nothing`, so a copy that stops
halfway is repeated rather than unpicked, and a database that already holds some of this merges
instead of losing it. Copying and switching stay two decisions: the copy changes nothing here, so
it can be done while still working on the embedded database and repeated for whatever was built in
between. Only switching costs a restart.

It goes both ways. Somebody who switched, restarted and found an empty screen gets the opposite
direction — the app opens the idle data directory, copies out of it, and stops it again.

### Zero configuration

With nothing set at all, `java -jar concentus-backend.jar` starts its own PostgreSQL, generates its
own encryption key, and asks for the first account. Two things used to be mandatory and are not any
more:

- **The database**, as above.
- **`CONCENTUS_SECRET_KEY`**, which protects every stored credential. Unset, one is generated on
  first run and kept at `<data-dir>/secret.key`. Said plainly, because it is a real trade: a key in
  a file beside the database it decrypts is weaker than one held apart — anyone who can read the
  folder can read the credentials. That is the right trade on one machine, where the alternative
  was no encryption at all because the application never ran, and the wrong one for a deployment
  several people can reach. Set it there; on the desktop the shell supplies it from the OS keyring.

## Context folders

Local runs execute in a scratch workspace (`<APP_DATA_DIR>/local/<runId>`), **not** in your project.
An agent therefore can't see your code unless you tell it where to look — and given only names to go
on it will guess, happily treating one checkout as another.

Each Agent node has two fields for this:

| Field | Meaning |
|---|---|
| **Context folders** | Host directories this agent should treat as its source of truth. Passed to the CLI as `--add-dir`, and listed in the agent's own instructions so it knows which folder is *its* one. |
| **CLAUDE.md path** | An existing `CLAUDE.md`, or a folder containing one. Its contents are inlined into the run's context — discovery can't find it on its own, since the CLI's cwd is the scratch workspace. |

Both are gated by an allowlist you must configure:

```properties
local.context-roots=C:\Users\me\code        # or LOCAL_CONTEXT_ROOTS=/home/me/code
```

- **The allowlist is required.** While `local.context-roots` is empty, every context folder is
  rejected. A flow is editable over HTTP and can be fired by a public webhook, so an unguarded path
  list would let anyone reachable read the host filesystem.
- Paths are checked after resolving `..` and symlinks, so neither can escape a root.
- A rejected folder is skipped with the reason shown in the run console; the rest of the run
  continues rather than failing outright.

Two limits worth knowing:

- **Local (subscription) runs only.** Cloud runs execute in Anthropic's sandbox with no access to
  your machine; use a Repository node there instead.
- **Per-agent folders are guidance, not isolation.** Local mode runs one CLI process for the whole
  flow, so `--add-dir` grants the union of every agent's folders to the session. Each agent is told
  which folders are its own, which steers it, but a determined agent can still read the others.

## Webhook authentication

A webhook Input node has two fields, and the same rule serves every provider — there is no
per-provider code path:

| Field | Meaning |
|---|---|
| **Validation parameter** | Name of the header (or query parameter) carrying the proof. E.g. `Linear-Signature`, `X-Hub-Signature-256` (GitHub), or `token` for a plain shared token. |
| **Secret** | The secret **the provider issued**. Concentus never generates one. |

The parameter is read from the request headers, falling back to the query string, and the request is
accepted if its value is **either**:

- a hex HMAC-SHA256 of the **raw request body** signed with the secret — bare hex, or the
  `sha256=<hex>` form some providers use; **or**
- the secret itself, for providers that just echo a static token back.

Notes:

- **Linear** — Settings → API → Webhooks → New webhook. Paste `/api/webhooks/{flowId}` as the URL,
  then copy the **signing secret** Linear shows on the webhook's detail page into the Secret field
  and leave the parameter as `Linear-Signature`.
  ([Linear docs](https://linear.app/developers/webhooks#securing-webhooks))
- **Replay protection** — a signature stays valid forever, so payloads carrying a `webhookTimestamp`
  are rejected if it is more than 60s from the server's clock. Payloads without one still pass.
- **A blank secret rejects every delivery with `401`.** This endpoint starts agent runs, so it is
  never left unauthenticated.
- Comparisons are constant-time, and the HMAC covers the exact bytes received (the body is never
  re-encoded before verification).

## Sign-in and organizations

**There is no mode without accounts.** There used to be one, for the desktop install — one person,
a socket bound to loopback, and a password to reach a port only they can open buys nothing. It
stopped being right the moment that install could point at a database a team shares: then who may
change a flow is a real question, and a roles screen enforcing nothing is worse than no roles.

Sessions are the servlet container's own — no extra dependency — and every state-changing request
carries the CSRF token Spring Security sets as the readable `XSRF-TOKEN` cookie (the SPA's
`req()` helper does this for you).

### The first account

An installation with no accounts shows a **setup screen** rather than a sign-in screen: there is
nobody to sign in as. You choose the address and the password, and you are signed in on the spot —
somebody who picked a password seconds ago has already proved what a login form would ask them to
prove again. Or you use a work account: **the first identity to arrive through a provider
administers the installation**, for the same reason.

`POST /api/account/setup` is the one endpoint reachable without a session, and it refuses the
moment an account exists. That window is the whole of what makes it safe to leave open — an
installation with accounts cannot be claimed through it, and one without them has no other way in.

Nothing is ever invented for you. The backend used to create an administrator and print a
generated password into the startup log; on a desktop install nobody reads that log, so the
"recoverable" half of the trade never happened and what remained was a live credential in plain
text.

For a deployment nobody sits in front of — a container started by a pipeline — set
`CONCENTUS_ADMIN_EMAIL` and `CONCENTUS_ADMIN_PASSWORD`. Both or neither: a blank password creates
nothing. The account is provisioned whenever *that email* has no account yet, not only on an empty
database, so setting it after the first boot still works. An existing password is never overwritten
from configuration — that would let anyone able to edit an environment variable take over a live
account — and `CONCENTUS_ADMIN_PASSWORD_RESET=true` is the explicit one-run opt-in for a lost one.

### Signing in with a directory

> Step by step, including the redirect URI mistake everybody makes on the first attempt:
> **[docs/sign-in-providers.md](docs/sign-in-providers.md)**.

Microsoft, Google and Discord, or anything else that speaks OpenID Connect. **Registered from
inside the application**, under *Resources → Members → Sign-in providers*: paste a client id and a
secret, and the button appears without a restart. It used to be six environment variables and a
restart, which on a desktop install is not something anybody can do — the feature worked and
nobody could turn it on.

The screen's first row is the **redirect URI**, with a button to copy it. Every registration fails
the same way the first time — the address in the directory not matching the one the application
asks for — so it is computed from the request rather than described in documentation you would
have to find. Registering `http://127.0.0.1:8734/…` with Entra needs the application manifest
(`web.redirectUris`); the portal's own field refuses `http` with a loopback address.

The sign-in screen offers **every** provider, registered or not. Showing only the configured ones
answered "can I sign in with my work account here?" with an absence, which reads as no. An
unregistered one is not a link: it says what it needs, rather than sending somebody to a provider
that will refuse them after they have typed their password.

Somebody arriving through a directory for the first time gets `VIEWER` — arriving with a valid
company account proves who they are, not what they should be allowed to change. An address that
already has an account is linked to it rather than duplicated, whichever way they arrive; people
are matched by the provider's own immutable id, never by their address, because addresses are
reassigned when people leave.

### Roles

Four rungs, each keeping what the one below it has: **Viewer** reads, **Operator** also runs,
**Member** also edits, **Admin** also manages who is in the organization. Enforced by method and
path in one place in `SecurityConfig`, so "what can a Viewer do?" has an answer that fits on a
screen. Members and roles are managed under *Resources → Members*.

### Several accounts on one machine

Checking what an operator sees and then going back to being an admin to change it is the loop
anybody setting up permissions is in, and it used to cost a sign-out and two passwords each way.
The header is a face: behind it, the accounts this browser has signed into, with their roles, and
one click to become one of them.

**An account gets on that list only by being signed into.** Nothing there grants access — the rows
record what this browser already proved. What the browser holds is one opaque device id in an
HttpOnly cookie; the accounts attached to it live in the database, so no second credential ends up
anywhere a page can read. It is not impersonation: an admin cannot become a colleague, only return
to an account they signed into themselves.

One at a time, because a session is a cookie and two accounts cannot share one browsing context.
The desktop shell can open a **second window with its own cookie jar**, which is the only honest
way to have two roles on screen at once.

### Isolation

**Every integration table is partitioned by `organization_id`**, and the id always comes from the
authenticated principal, never from a request parameter — so no request can address another
tenant's mail events, subscriptions or estimates. Sign-in providers are the deliberate exception:
they are installation-wide, because the screen that offers them has nobody signed in to scope them
by.

**Accounts fail closed.** Unlike run persistence, which degrades to memory when the database is
unavailable, sign-in refuses rather than degrading — a server nobody can authenticate against is
safer than one that authenticates nobody.

Pre-existing resources (flows, agents, MCP definitions, databases) are behind sign-in but are still
shared across the deployment rather than partitioned per organization; repartitioning them would be
a data migration that breaks existing installs.

> The same material laid out to be looked things up in rather than read through is on the site,
> at **/docs** — the source is [apps/website/docs/index.html](apps/website/docs/index.html).

## Installing

The installer for your platform is under **Releases** — or through a package manager, which also
sidesteps the SmartScreen warning an unsigned download gets from a browser:

```bash
# Windows — scoop (works today: the bucket lives in this repo)
scoop bucket add concentus https://github.com/Gergilcan/concentus
scoop install concentus          # update later with: scoop update concentus

# Windows — winget (after the first winget-pkgs approval; see packaging/winget/README.md)
winget install concentus

# macOS — Homebrew (after the first macOS release lands in the tap; see packaging/macos.md)
brew tap gergilcan/concentus && brew install --cask concentus

# Any platform with Node — downloads the right installer from Releases and runs it
npx concentus
```

> **License** — free for personal and other noncommercial use under
> [PolyForm Noncommercial 1.0.0](LICENSE.md). Commercial use needs a license from the author.

The app itself asks for none of that at runtime: it runs at a one-seat limit with no license at
all, and a free individual license — same limit, just your name on it — is a form away at
[concentus-ai.com/#license](https://www.concentus-ai.com/#license). A shared database, members
beyond the first, and SSO are the enterprise features, priced per seat and billed monthly or
annually. See [the licensing docs](https://www.concentus-ai.com/docs#licensing) for the details.

## Updates

The desktop app checks every four hours, downloads in the background, and installs when you quit.
The state sits in the top right of the window: silent while there is nothing to say, a dot when a
version is downloaded and waiting.

Pressing install runs the installer **silently** — no wizard, no licence page, and no question
about where to put it, because it goes where the installation already is. The app reopens when it
is done. Before the installer starts, Concentus ends its own backend and waits for it to be gone,
taking the whole process tree if it has to: the bundled Java runtime lives inside the installation
directory, and an installer that starts while the backend still holds those files stops and reports
that the application is still open.

A **prerelease** build follows both prereleases and finals, whichever is newer. A **final** build
follows finals only.

> Prereleases are tagged `-beta.N`, dotted, and that is load-bearing. electron-updater takes the
> update channel from the prerelease identifier and only offers a final release to builds on null,
> `alpha` or `beta` — those three names are hard-coded in it. On any other identifier, `rc`
> included, a build can never reach a stable version. Dotted matters too: `0.1.3-beta.1` puts every
> release in the train on one channel, while `0.1.3-beta1` gives each release its own and nobody is
> offered the next one. The release workflow refuses both mistakes.

## Paying for runs

The setup screen asks once, before anything is installed, because the answer decides whether
anything needs to be.

**Your Claude subscription** is the default and the cheaper one: flows run through Claude Code on
this machine, with no second bill for what fits in the plan's non-interactive allowance, and no key
to look after. One button runs Anthropic's official
installer, puts the binary **on your PATH**, and opens a terminal on `claude auth login` — the
sign-in itself, not a prompt where the next thing to know is that you type `/login`.

On Windows the PATH entry is written straight into the per-user registry value, never through
`setx`: that truncates a long PATH at 1024 characters, silently and permanently, and a developer's
PATH is routinely longer than that. On Linux and macOS it is one line in whichever startup files
exist, marked so a second install does not add a second copy.

**An Anthropic API key** is the other answer: nothing installed, no sign-in, right for a machine
nobody sits at. It is kept in the operating system's keyring; where there is none — a server with
no desktop session — it is a file readable by that account alone, and the screen says so rather
than pretending otherwise.

Either can be changed later: the setup screen is under **Setup…** on the tray icon. A key already
in the environment as `ANTHROPIC_API_KEY` still works, and one saved in the app takes precedence
over it.

## Retrieval: how a passage is found

Every query runs **two searches**, and they are fused rather than chosen between.

A vector search cannot pick out an exact token. A document reference, an error code, a filename
sits in embedding space beside all of its neighbours, so asking for a policy by its number returns
twelve other policies. A keyword search finds it first try. The mirror case is just as common: no
keyword search matches "what happens when somebody leaves" against a document titled *Offboarding*,
because they share no words. Running both is not hedging — it is the only way to answer both
questions.

Fusion is by **rank, not by score**. A cosine similarity and a PostgreSQL text rank are not
comparable quantities, and weighting them against each other would mean inventing an exchange rate
and then tuning it forever. Reciprocal Rank Fusion asks only how near the top of its own list each
branch put a passage, which is comparable by construction — and a passage only one branch found
still surfaces, which is the entire point.

A **reranker** then reorders the survivors. It reads the question and a passage *together* and
scores the pair, which is what lets it notice that a passage describes the right process for the
wrong department — a distinction two separately-computed embeddings cannot express. Too slow for a
whole base, exactly right for the fifty candidates a search already found.

**Both models are optional and both run inside the app** — no Ollama, no Docker, no server. Under
Resources → Knowledge: one button downloads the embedding model (~130 MB, multilingual-e5-small),
another the reranker (~282 MB, bge-reranker-base, multilingual). A model server serving `bge-m3` is
picked up automatically if you have one. With neither, keyword search still works and the panel
says what is missing rather than pretending.

What reaches the agent is assembled, not pasted: adjacent passages are **merged** so the overlap
between chunks is not repeated, ordered **by document and position** rather than by score — a
document is read forwards — trimmed to a character budget (`knowledge.context-chars`, or Resources → Settings), and
**numbered with a citation key**, with the prompt asking for those markers back. An answer nobody
can check against its sources is the failure this subsystem exists to avoid.

Traces carry it: a `concentus.retrieval` span per search, with candidate counts per branch and
whether a rerank happened. Identifiers and counts, never the text.

### Who may read a base

Every other resource here is reachable by anybody who may edit flows, and for an agent definition
or a webhook that is right — they are configuration. A knowledge base is not configuration; it is
**the documents themselves**. Salary bands, an incident post-mortem, a contract: material where
"anybody who can edit a flow" is plainly the wrong audience, and where the leak does not look like
a leak. It looks like an agent answering a question well.

So a base can name the lowest role that may read it. **Absent means everybody** — which is what
every base created before this existed meant, and an upgrade that silently locked people out of
their own documents would be a worse failure than the one this prevents.

Enforced twice. Once at the API, and once when a run injects a base, against the role of **whoever
started the run** rather than whoever drew the flow. A flow outlives its author’s session, and
inheriting the author’s reach would make every restricted base readable by anybody who could press
Run on the right flow.

### Measuring it, rather than feeling it

Every change to ranking sounds like an improvement, and the usual evidence is three queries typed
after the change. Under that regime a base gets quietly worse while everyone agrees it feels
sharper.

So a base can keep a **golden set** — questions somebody really asks, each with the document that
answers it — under *Resources → Knowledge → Measure this base*. Running it gives three numbers:

| | What it means |
|---|---|
| **Answered** | Share of questions where an expected document made the top 5. How often somebody would have got an answer at all. |
| **Documents found** | Share of every expected document retrieved. Lower than *Answered* whenever a question needs two documents and one came back — a half answer the headline would hide. |
| **Rank quality** | Mean reciprocal rank: 1.00 when answers come first, 0.50 when second, 0 when never. A passage at rank 5 spends the context budget of four wrong ones getting there. |

It runs the same questions **with and without the reranker**, which is the only honest way to
answer "is it worth 282 MB on my documents" — a benchmark on somebody else's corpus cannot. And a
miss shows what came back instead, because that is the explanation, not just the verdict.

What is measured is retrieval, not the answer. Judging generated text needs a model grading a
model — a second thing to be wrong and a bill per run — and it answers a different question: a
wrong answer built on the right passage is a prompt problem, while a right-sounding answer built
on the wrong passage is this one, and it is the one that ships without anybody noticing.

## Settings

Almost everything adjustable here used to be an environment variable. On a server that means
editing a container's environment and redeploying to change a queue length; on the desktop it
means nothing at all, because the shell computes the environment and there is no file a person can
edit. They were not settings, they were constants with a comment.

*Resources → Settings* is a table, a resolver and a screen. A value is looked up in three places,
in order:

1. what somebody set in the application,
2. what the deployment was started with,
3. the built-in default.

That order is what keeps both audiences working — a container started by a pipeline still takes
its environment, and the person in front of the app can change the same thing from a form.
Clearing a field removes the override rather than storing an empty one, which is the only reading
that leaves a way back. Secrets are sealed with the same key that protects stored credentials and
are never read back out of the API.

The screen says **where each value came from**, because "8" means three different things and only
one of them is yours to clear, and **whether the change waits for a restart** — most do, because
they size a thread pool or a policy when the application starts.

Four things cannot be settings, because they are what has to be known before there is anywhere to
keep one: the database the table lives in, the key that decrypts it, the data directory and the
port, and the bootstrap administrator for a deployment nobody sits in front of.

The catalogue only lists settings whose consumers actually read through it. A field that saves a
row nothing looks at is worse than no field, because it looks like it worked — so it grows as
consumers are converted rather than being written out in full first.

## Traces and metrics (OpenTelemetry)

The framework already instruments what a framework can see: an HTTP request, a scheduled task, a
WebSocket session. None of that answers the question anybody actually has here, which is about a
**run** — why it took eleven minutes, which block was slow, which tool call hung, what the third
worker spent.

So there are five span names and one vocabulary of attributes, in one place (`Telemetry`):
`concentus.run`, `concentus.node`, `concentus.worker`, `concentus.tool`, `concentus.model`.
Scattering `spanBuilder` calls through the services would have produced five spellings of
`flow.id` inside a month, and a dashboard is only as good as the agreement between the things it
groups.

Spans wrap each stretch of a run the machine is actually busy for, each fan-out worker, and each
MCP tool call — refusals included, since a tool call rejected for a bad token is exactly what
somebody goes to a trace for. Not one span per run: a run starts on one thread, waits for a
person, and continues on another, and a span covering that would measure how long somebody took to
answer.

**No prompts, no outputs, no tool arguments.** Attributes carry identifiers, counts and outcomes. A
trace ends up in somebody else's system, usually with a longer retention than anything here and
read by people who were never given access to the flows. What is useful for debugging and what is
safe to export are different sets, and where they differ the smaller one wins.

Off unless a collector is named, and off by a switch rather than by a blank address: an empty
endpoint is not "nowhere" to the OTLP exporter, it is an invalid URL, and it refuses to start
rather than staying quiet. The spans are created either way, so a run behaves identically on a
machine that exports and one that does not. Configure it under *Resources → Settings*, or with
`OTLP_ENABLED` and `OTLP_ENDPOINT`.

## Background jobs

Work that must outlive the request that accepted it goes through a PostgreSQL-backed queue
(`jobs`), claimed with `for update skip locked` so several workers — and several processes — drain
it safely. Retries use exponential backoff, honour a provider's `Retry-After` when one was sent,
and park the job after `max_attempts`. A job whose worker died is returned to the queue by a stale
sweep.

Add a job type by implementing `JobHandler` and exposing it as a bean; `JobWorkers` picks it up by
`type()`, the same way `ProviderRegistry` and `ExecutionBackends` assemble their implementations.

Unlike run persistence, the queue does **not** degrade to memory: accepting a webhook we cannot
durably record would lose the work, so `enqueue` throws and the endpoint answers `503` so the
provider redelivers.

## Mail-triggered flows (IMAP)

An Input node in **mail** mode polls an IMAP folder and starts a run for each message matching its
conditions — folder, from, subject, body, unread, flagged, has-attachments — and can move, flag or
mark the message read once the run has started.

IMAP rather than SMTP, and the distinction is load-bearing: SMTP is a transport that hands a
message over and forgets it, while folders, flags and read state live in the mail *store*. "When a
flagged mail lands in Presupuestos" is only expressible over IMAP, and so is moving it to
Procesados afterwards.

- **Conditions become an IMAP `SEARCH`**, so the server filters and only matches cross the network.
  *Has attachments* and the already-processed exclusion are applied locally — the protocol can't
  express them.
- **The password is never in the flow.** It is entered under **Resources → Credentials**,
  encrypted with AES-256-GCM, and the node stores only its id — see [Stored
  credentials](#stored-credentials).
- **Microsoft 365 needs a sign-in, not a password.** Basic authentication for IMAP is retired in
  Exchange Online, so a correct password still returns `AUTHENTICATE failed`. Set the node's
  **Authentication** to *Microsoft 365 sign-in* and press **Connect Microsoft account**: the
  backend runs the OAuth2 **device code** flow, shows a short code, and you enter it at
  `microsoft.com/devicelogin` on any device. No redirect URI, no client secret and no browser on
  the server — which is exactly why it works from inside the container. What gets stored is a
  refresh token, encrypted like every other credential, and it renews itself; rotated tokens are
  written back, because keeping a stale one works right up until it doesn't. The app registration
  needs delegated `IMAP.AccessAsUser.All` + `offline_access` and *Allow public client flows* = Yes;
  its tenant and client ids go on the node, since they are identifiers rather than secrets.
- **A message starts at most one run.** Its `Message-ID` is claimed in `processed_mail` — an insert
  against a unique index, not a read-then-write — *before* the run starts. Keyed on `Message-ID`,
  not the IMAP UID, because the UID doesn't survive the folder move.
- **The mail reaches the agent as untrusted data**: body and attachment text fenced with an
  unguessable per-run marker, with sender/subject/date stated outside the fence as verified.
  Attachments are routed by magic bytes, never by the sender-supplied filename or MIME type.
- Polling, not IMAP IDLE — IDLE needs a held connection per folder and careful re-establishment;
  for work measured in minutes, polling degrades to "slightly late" rather than "silently stopped".

**[Quote requests by email → Holded](docs/flows/mail-to-holded.md)** is the worked example, and
ships as a starter flow: a mail Input node, an agent carrying the rules in its system prompt, and
an MCP node for Holded. It creates **draft** estimates only — never sends, accepts, invoices or
deletes.

## Stored credentials

Mailbox passwords, MCP bearer tokens, Git provider tokens and database passwords are all entered in
the app under **Resources → Credentials**, not in the environment. They are encrypted with AES-256-GCM (fresh random IV per value, authenticated, so
a tampered row fails to decrypt rather than yielding different plaintext) under the master key in
`CONCENTUS_SECRET_KEY`.

**The field is write-only.** No endpoint returns a stored secret — not to a user, not to an
administrator. A credential comes back as a label, a kind and a masked hint. Editing shows an empty
value box, and leaving it empty keeps the stored secret, so "rename and save" cannot overwrite a
password with a mask.

**Every node type uses this** — mail, MCP, repository and SQL. **Nodes hold an id, never a value.** Every flow save snapshots the flow JSON into version history
and duplicating a flow copies its nodes, so a secret on a node — even encrypted — would fan out
into every revision and every copy. Deleting a credential therefore really removes it.

**Be precise about what encryption at rest buys.** It protects a leaked database backup, a
database-only compromise, or a read-only SQL injection. It does **not** protect against someone who
compromises the application or the host, because the key has to be reachable by the process to be
usable. The secret does not disappear — it collapses to one key, which is then the thing to guard.

**In the app there is nothing to do.** The shell generates the key on first launch and stores it in
your OS keyring — DPAPI on Windows, libsecret on Linux — so the ciphertext in the database is
readable only by you, on this machine. Where no keyring is available the key falls back to a
permission-protected file and the log says so, because a Linux box without a keyring daemon is a
real configuration rather than a broken one.

Running the backend by hand, `CONCENTUS_SECRET_KEY` is **required** and it refuses to start without
one. Every credential it holds depends on that key, so starting anyway would leave a half-working
install — credential fields that silently cannot save, mail triggers quietly not polling. Changing
the key makes existing credentials unreadable; they have to be re-entered.

### Git providers, and why OAuth is the wrong route here

The MCP registration is a header, not a browser flow — `claude mcp add --transport http … -H "<header>"`.
So any credential that works as a header works without a browser, which is what makes this usable
from a cron or mail trigger at all: an interactive OAuth sign-in needs someone present, and a flow
that fires at 7am does not have one. `GET /api/mcp/capabilities` reports that, and the UI offers the token route instead of a
button that would start a sign-in it can never finish.

| Provider | Credential | Header |
|---|---|---|
| GitHub | fine-grained PAT, or a GitHub App installation token | `Authorization: Bearer …` (default) |
| GitLab | project or group access token (not tied to a person), or a PAT | `PRIVATE-TOKEN: …` |

The header is per-node because it is not universal: GitLab reads its access tokens from
`PRIVATE-TOKEN` with **no** `Bearer` prefix, so sending one there makes an otherwise-correct token
wrong. Existing flows carry no header and keep the default, so nothing changes for them.

GitLab repositories are reached through their MCP server rather than mounted natively — only
GitHub repos are mounted in the cloud backend, and a non-GitHub repo is logged and skipped rather
than failing the run.

### Working on repositories, and opening PRs / MRs

A **repository node** is cloned into the run.s own working directory — which is already the CLI.s
cwd, so the checkout is simply there, needing no `--add-dir` grant and no entry in
`local.context-roots`. One clone per run, because two runs of the same mail-triggered flow can
overlap and a shared working tree would have them writing over each other.s branch.

The agent then edits, commits, pushes a branch and opens the pull request (GitHub) or merge request
(GitLab) through that provider.s MCP server. On GitLab it can instead push with
`-o merge_request.create`, which opens the MR from the push itself. Those instructions are written
into the run.s `CLAUDE.md`, including the rules that matter: branch, never the default; never
force-push; and stop rather than open a PR you are not sure about.

**One repository, or a whole group.** A node's **Scope** is either a single repository or an entire
GitHub organization / GitLab group. In group mode the node has no URL: it names the group, and
every repository inside it is cloned. That set is resolved **when the run starts**, not when the
flow is saved, so a repository added to the group next week is picked up without anyone reopening
the canvas — which is the point of pointing at a group rather than pasting twelve URLs.

- **Selecting a subset stays possible.** Press *Select specific repositories* and tick the ones you
  want; leave them all unticked and it means all of them.
- **Archived repositories are excluded by default** — they are read-only, so a flow that exists to
  open PRs can only fail on them. There is a checkbox if you want them anyway.
- **Each repository keeps its own default branch**, unless the node sets one explicitly. A group's
  repositories rarely share a branch name, and forcing `main` on all of them would simply fail the
  clone of every repository that uses something else.
- **The expansion is capped** by `GIT_MAX_GROUP_REPOS` (default 25). Going over is logged and told
  to the agent — a run that quietly worked on a prefix of the group would look like success.
- In single-repository mode you can still browse a group and fill the node in from the pick. The
  token is named by credential id and resolved server-side — it never crosses the wire in either
  direction.

**Where the push credential lives.** On the CLI process environment, never in `.git/config` and
never in a remote URL. That keeps it off disk and out of `git remote -v`, so it cannot be copied
into a commit message or a PR body by accident.

> It does **not** hide the token from the agent, which runs with `bypassPermissions` and can read
> its own environment. That is inherent to letting the agent push. Scope the credential to these
> repositories — a fine-grained PAT (GitHub) or a project/group access token (GitLab) — and do not
> reuse a credential that has any other reach, particularly not the one a mail trigger uses.

In the cloud backend only GitHub repositories are mounted natively; a GitLab repo is logged and
skipped there, and is reached through its MCP server instead.

### Bundled starter flows

Flows under `apps/backend/src/main/resources/library-flows/*.json` are installed into the flow
store on startup, the way `library-agents` seeds the agent library. Installation is recorded in
`<data-dir>/flows/.seeded-flows`, so a starter you delete stays deleted and one you edit is never
overwritten.

### Webhook body credentials

The webhook accepts its configured parameter from a header, the query string, **or the JSON body**
— at the top level, or inside a `value` array of batched notifications. Some providers carry their
shared secret inside the payload rather than as a header (Microsoft Graph's `clientState` is the
common example), which header-and-query-only lookup can never authenticate. In a batch, every entry
must present the same value, so a caller who learned the secret cannot staple foreign notifications
onto a valid delivery.

## API surface (backend)

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/flows`, `/api/flows/{id}` | list / load / save / delete flows |
| POST | `/api/flows/{id}/run` | launch a saved flow |
| GET | `/api/runs`, `/api/runs/{id}` | list runs / run detail + buffered output |
| POST | `/api/runs` | launch an ad-hoc (unsaved) flow |
| POST | `/api/runs/{id}/commands` | send an instruction to a running session |
| POST | `/api/runs/{id}/stop` | stop a run |
| WS | `/ws/runs?runId=...` | live output stream (replays buffer, then live) |
| GET | `/api/auth/status` | active backend (Local subscription / API key / none) |
| GET/POST | `/api/agents`, `/api/databases`, `/api/mcp-defs` | reusable resource definitions (Resources page) |
| GET/POST | `/api/mcp/servers` | list / register MCP servers in Claude Code |
| POST | `/api/mcp/servers/login`, `/api/mcp/servers/remove` | launch OAuth sign-in / remove a server |

> **MCP name validation** — `/api/mcp/servers/login` spawns a terminal window, so the server `name`
> is restricted to letters, digits, spaces, dots, dashes and underscores (1–64 chars); anything else
> is rejected with `400`. That admits every real server name (`Linear`, `claude.ai Google Drive`)
> while excluding the shell/batch metacharacters an injected command would need. The name is passed
> to the spawned console as a discrete argument (`%~1` on Windows, a data file on macOS) and never
> interpolated into script text. `remove` is not charset-restricted — it goes straight to the CLI
> as argv, so servers registered under any name stay removable.
| POST | `/api/mcp/studio` | Concentus **as** an MCP server — design, validate, run and steer flows from an outside agent ([details](#concentus-as-an-mcp-server)) |
| POST | `/api/webhooks/{flowId}` | inbound webhook that starts a run with the event payload ([auth](#webhook-authentication)) |
| GET | `/api/rag/status` · POST `/api/rag/preview` | RAG capabilities / preview a SQL source's rows |
| GET | `/api/account/session` | who is signed in, which providers exist, and whether this installation still needs its first account |
| POST | `/api/account/setup` | create that first account. The only endpoint reachable without a session, and it refuses once one exists |
| POST | `/api/account/login` · `/api/account/logout` | sign in / out ([details](#sign-in-and-organizations)) |
| GET/POST | `/api/account/members` | organization members (admin only) · `POST /api/account/password` |
| GET | `/api/account/accounts` · POST `…/{userId}/use` | the accounts this browser has signed into, and switching to one |
| GET/PUT | `/api/account/providers` | register Microsoft / Google / Discord, and the redirect URI to give them (admin only) |
| GET/PUT | `/api/settings` | everything adjustable, with where each value came from (admin only) |
| GET | `/api/knowledge/embedder` · `/api/knowledge/reranker` | the two optional retrieval models: state, progress, size · POST `…/download` |
| GET/POST | `/api/knowledge/{id}/evals` · POST `…/evals/run` | the golden set for a base, and what retrieval scores against it |
| GET/POST | `/api/storage`, `/api/storage/migrate` | where this installation keeps its data, and copying it to another PostgreSQL (admin only) |

Flows persist as JSON under `apps/backend/data/flows` (override with `APP_DATA_DIR`).

## Headless: run a flow from a terminal

A flow is a JSON file and the backend jar is self-contained, so a run needs neither the desktop app
nor a browser:

```bash
pnpm desktop:build                                     # once — builds the jar
export CONCENTUS_EMAIL=you@company.com                 # the API needs an account, like anything else
export CONCENTUS_PASSWORD=…
node scripts/concentus-run.mjs my-flow.json --input "go"
echo $?                                                # 0 completed · 1 failed · 2 needs a human
```

It signs in like everything else does. A script that could drive the backend without credentials
would be a hole rather than a convenience — there is no unauthenticated mode any more, and there
was no honest way to keep one for this. Prefer the environment variables over `--email` /
`--password`: an argument ends up in the job's log and in the process list.

It boots the jar on a free port with a throwaway data directory, imports the flow, starts a run,
prints the agents' messages on **stdout** and everything else (progress, errors, the outcome) on
**stderr** — so `node scripts/concentus-run.mjs flow.json --input "go" --quiet > answer.txt` leaves
you the answer and nothing else.

| Option | |
|---|---|
| `--input "text"` | the run's first message. Without it a manual-trigger flow has nothing to do. |
| `--url URL` | use a backend that is already running instead of booting one. |
| `--jar PATH` | a different jar (default `apps/backend/target/concentus-backend.jar`). |
| `--timeout SECS` | give up waiting (default 1800) and exit 3. |
| `--quiet` | only the answer and the outcome line. |
| `--email` / `--password` | the account to sign in as. Default to `CONCENTUS_EMAIL` / `CONCENTUS_PASSWORD`. |

**Exit codes**, because a shell needs to tell three different situations apart:

| | |
|---|---|
| `0` | the run completed |
| `1` | it failed — the run errored, was terminated, or could not be started at all (no signed-in CLI, no API key, budget reached). The backend's own words are on stderr. |
| `2` | **it needs a human** — waiting for approval, asking a question, or with no instruction |
| `3` | timed out; the run is still going in that backend |
| `4` | usage or setup problem (no such file, jar missing, backend never came up) |

Deliberate limits: it **never answers for you** — approval mode and questions exit 2 rather than
guessing — and the flow travels in the request rather than being saved, so a headless run leaves no
flow behind in whatever database it used. It polls the run rather than opening the UI's WebSocket:
nobody watches a terminal for sub-second latency, and polling needs no client to keep alive.

## Concentus as an MCP server

Concentus consumes MCP servers — an MCP node grants an agent someone else's tools. This is the same
socket pointed the other way: **the backend is itself an MCP server**, so an outside agent can
design, validate, run and steer flows exactly as a person does through the canvas. Point Claude
Code at it and "write me a flow that reviews my pull requests, then run it on this repo" produces a
real flow on the dashboard, checked before it is saved.

```bash
claude mcp add --transport http concentus http://127.0.0.1:8734/api/mcp/studio
```

8734 is the port the desktop app prefers and keeps across launches. If it was taken, the app took
another one — the window title bar and the log say which.

### What it can do

| | |
|---|---|
| **Design** | `flow_schema` · `list_flows` · `get_flow` · `create_flow` · `update_flow` · `update_flow_node` · `delete_flow` |
| **Check** | `validate_flow` — compiles the graph, then runs the same [pre-run doctor](#concepts) the canvas does |
| **Run** | `run_flow` · `list_runs` · `get_run` · `get_run_nodes` · `get_run_flow` · `send_command` · `stop_run` · `approve_run` · `reject_run` · `retry_run` |
| **History** | `list_flow_versions` · `get_flow_version` · `restore_flow_version` · `read_flow_memory` · `clear_flow_memory` |
| **Library** | `list_resources` · `save_resource` · `delete_resource` over agents, MCP servers, databases, knowledge bases, skills, variables and credentials |

Three details worth knowing before you use it:

- **Nothing is saved unvalidated.** `create_flow` and `update_flow` compile the graph first and
  refuse it with the compiler's own message, so a misunderstood request comes back as something to
  fix rather than as a broken flow on your dashboard.
- **`update_flow_node` merges.** A model asked to change one field sends back the fields it thought
  about and forgets the rest; applied literally that would strip the tool allowlist that made a
  reviewer read-only. Pass `replace: true` when removing a field *is* the edit.
- **Runs are asynchronous.** `run_flow` returns a run id immediately with nothing done yet; the
  agent polls `get_run` until it reaches `COMPLETED` or `ERROR`. Deletions and memory clears require
  `confirm: true`.

Credential **values** are never returned, here as nowhere else in the API. Writing one is allowed
and it is unreadable the moment it lands.

### Reachability, and the honest limits

| | |
|---|---|
| **Desktop** (`app.auth.enabled=false`) | Works with no token. That is not a hole: the socket is bound to `127.0.0.1` and every other API endpoint is already open on it, so anything that could call this could call `/api/flows` directly. Set `CONCENTUS_MCP_TOKEN` anyway and it is enforced. |
| **Server** (`app.auth.enabled=true`) | A token is **required** — the endpoint answers `401` to everyone until `CONCENTUS_MCP_TOKEN` is set. It is not a bypass of the account system: it stands for the account named in `CONCENTUS_MCP_ACCOUNT`, and the request runs in that account's organization exactly as that person's browser session would. A token whose account cannot be resolved is refused rather than falling back to the default organization. |

There is **no public URL** for this, and that is a property of the product rather than an omission.
The backend runs on your machine so that flows can use your Claude subscription, your repositories
and your folders; a hostname on the internet cannot reach a loopback socket. Two ways to get one,
both of which need this endpoint either way:

- **Host a Concentus backend** with `AUTH_ENABLED=true` behind your own domain and proxy `/mcp` to
  `/api/mcp/studio`. Its flows are a different database from the app on your desk, and its runs can
  only use cloud mode (an API key), never your subscription or your local checkouts.
- **Relay to the running app** — a public endpoint holding an outbound WebSocket from each desktop
  install. This is the only shape that keeps your flows, your subscription and your repositories
  while offering a URL; it needs a service that can hold sockets open (Fly, Railway, Cloudflare
  Durable Objects — not the static Vercel site), a pairing step, and it only answers while the app
  is open.

## The YAML CLI (still here)

The original single-agent CLI lives in the same backend module. See its usage in
[apps/backend](apps/backend) — build the module and run `com.concentus.Main`:

```bash
cd apps/backend && mvn -q package
java -cp target/concentus-backend.jar com.concentus.Main path/to/agent.yaml "your prompt"
```

## Notes & limits (v1)

- Managed runs create fresh agents/environment each launch (demo simplicity) — in production these
  are persistent, versioned resources created once.
- Managed-mode **MCP auth** needs a vault; MCP servers are declared without credentials for now
  (public/unauthenticated MCP and native GitHub repo mounts work as-is).
- Runs, events and per-node output are persisted (see [Persistence](#persistence-postgresql)), but
  the live handles to a running session are not — restarting the backend leaves history intact and
  drops anything mid-flight.
- **Agent scoping steers, it doesn't isolate — on the shared-session path.** A local run there is
  one CLI process for the whole flow, so context folders and delegation rosters are written into
  each agent's instructions rather than enforced: an agent is told which folders and which agents
  are its own, but can still reach the others. Three things ARE enforced on that path:
  per-sub-agent **tool allowlists** (by the CLI), per-flow **MCP isolation**
  (by `--strict-mcp-config`) — and when you need the real thing, a process per agent with
  enforced MCP facades is exactly what
  [independent workers](#independent-workers-fan-out-execution) provide.
- **Settings apply on the next start**, mostly. Almost every one sizes a thread pool or a policy
  when its bean is built, so the screen says which wait for a restart rather than pretending
  otherwise. Sign-in providers are the exception, and deliberately so: somebody who has just
  pasted a client id wants to try it.
- **Two accounts at once needs two windows.** A session is a cookie, so they cannot share one
  browsing context; the desktop shell opens a second window with its own cookie jar. Switching
  between accounts in one window is a click.
- Built against `anthropic-java` 2.34.0 and Spring Boot 3.5.x on Java 25.
