# Concentus

A **desktop application** for designing, running, and steering Claude agents — visually.

**[Download for Windows or Linux →](https://github.com/Gergilcan/concentus/releases/latest)**

Install it and open it. There is no server to deploy, no database to provision, no API key to
paste, and nothing to configure — the app carries its own Java runtime and its own PostgreSQL, and
runs your flows on the Claude Code login already on your machine.

### Why a desktop app and not a web service

Because the interesting half of this product only works on your own machine. Concentus runs flows
by driving the `claude` CLI against **your Claude subscription** — no API key, no per-token bill.
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

- A **flow** is a multi-agent orchestration graph. **Agent** nodes (one marked *coordinator*),
  plus **MCP**, **Repository**, **SQL**, **Knowledge** and **API (OpenAPI)** capability nodes — an
  API node turns any REST API into typed tools from its OpenAPI spec, with each operation
  allowed explicitly — and, for fan-out flows, a **Merge** node. Connect a capability to
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
  coordinator becomes a sub-agent (a flow may only have one coordinator), names are made unique, and
  a copied webhook node starts with an empty secret. Flows themselves duplicate from the ⧉ button on
  their dashboard card.
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
  `apps/backend/data/agents/` are migrated in on first launch). The agent node inspector's
  **Load from library** dropdown populates a node from one. The MCP tab also has a **one-click
  catalog** — GitHub, Linear, Notion, Sentry, Stripe and more — each entry saying how it
  authenticates, because that is the question that actually stops people.
- **RAG** — two kinds of context, injected at run start:
  - **SQL source** node: its query runs (generic JDBC; PostgreSQL bundled, add other drivers to the
    backend pom) and the rows are injected into the connected agent's context. Test the query from
    the node inspector before running.
  - **Knowledge** node: pick a knowledge base (Resources → Knowledge — upload PDFs, Word, Excel,
    text, or whole folders with per-folder exclusions), and the passages most relevant to the run's
    prompt are injected into the connected agent, recorded per node. Semantic ranking runs
    **inside the app**: a one-click ~130 MB model download (multilingual-e5-small over ONNX), no
    Ollama and no Docker — though a model server serving `bge-m3` is picked up automatically if
    you have one. Without either, retrieval falls back to word overlap and the UI says so.
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
- **Flow memory** — every saved flow keeps short notes that survive between runs
  (`memory_read` / `memory_append` tools): decisions taken, state reached, approaches that failed.
  Agents are told to read it before starting, so run N+1 stops redoing what run N learned.
- **Budgets & usage** — a flow can carry a monthly USD ceiling; at or past it, new runs are
  refused until next month (a run in flight always finishes). The **Usage** page shows measured
  Claude consumption on the machine, and every run prices each block at its own model's rate.
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
  rather than passing outputs along as judged. At most one per flow.
- **The Merge node** runs after every worker: one more process that receives all their reports
  (failures included), reads their workspaces, and reconciles them into the run's answer. It is
  the only fan-out process with shell access — running the tests belongs to the one step whose
  job is verifying the combined result, not to N unattended workers.
- **The run console reports the graph, not just the transcript**: a fan-out run shows how many
  workers ran and failed, how much retrying propped the run up, the verifier's kill rate, and
  the wall-clock versus sequential time — the parallelism the fan-out actually bought.

Two current limits, said where you can plan around them: repository nodes are **not cloned into
workers** yet (a flow that pushes code stays on subagents execution), and workers run against
your Claude login — pointing them at a local model gateway is future work.

## Install

Download the installer for your platform from
**[the latest release](https://github.com/Gergilcan/concentus/releases/latest)**:

| Platform | File | Notes |
|---|---|---|
| **Windows** | `Concentus Setup <version>.exe` | Per-user install; no administrator rights needed |
| **Linux** | `Concentus-<version>.AppImage` | `chmod +x` and run it — nothing to install |
| **Linux (Debian/Ubuntu)** | `concentus_<version>_amd64.deb` | `sudo apt install ./concentus_<version>_amd64.deb` |

There are no prerequisites. Java and PostgreSQL are inside the installer — the app does **not** use
or interfere with any Java or PostgreSQL you already have.

macOS is not built. The code runs there, but shipping a Mac build requires an Apple Developer
account for notarization, without which Gatekeeper blocks the app outright — so a build would only
produce something that refuses to open.

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
and the picker says so rather than showing a figure that isn't real. Runs on a Claude **subscription**
have no per-token bill at all — there the figure is an equivalent-usage estimate for comparing runs,
not a charge; on the **API** it approximates the real charge.

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
- Running the backend by hand does **not** activate the embedded database — that lives in the
  `desktop` profile. Point `PERSIST_DB_*` at a PostgreSQL of your own for development.
- An unreachable database never crash-loops the app (`initialization-fail-timeout=-1`); each store
  logs and reports itself unavailable instead.

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

The API requires an authenticated session. Sessions are the servlet container's own — no extra
dependency — and every state-changing request carries the CSRF token Spring Security sets as the
readable `XSRF-TOKEN` cookie (the SPA's `req()` helper does this for you).

- **The administrator account** comes from `CONCENTUS_ADMIN_EMAIL` / `CONCENTUS_ADMIN_PASSWORD`,
  and is provisioned at startup whenever *that email* has no account yet — not only on an empty
  database, so setting it after the first boot still works. Leave the password blank and one is
  generated and printed **once** in the backend log. Minimum 12 characters, the same rule that
  governs changing a password later, so an account is never created in a state from which its own
  password could not be re-set.
- **An existing password is never overwritten from configuration.** That would let anyone able to
  edit an environment variable take over a live account, and would silently undo a password
  changed in the app. `CONCENTUS_ADMIN_PASSWORD_RESET=true` is the explicit one-run opt-in for a
  genuinely lost one.
- There is deliberately **no public registration endpoint**: on a self-hosted install that would
  let whoever reaches the server first claim the organization. Further members are invited by an
  admin from `POST /api/account/members`.
- **Every integration table is partitioned by `organization_id`**, and the id always comes from
  the authenticated principal, never from a request parameter — so no request can address another
  tenant's mail events, subscriptions or estimates.
- **`AUTH_ENABLED=false`** leaves the API open exactly as it was before accounts existed, and
  resolves everything to `APP_ORGANIZATION_ID`. Local development only.
- **Accounts fail closed.** Unlike run persistence, which degrades to memory when the database is
  unavailable, sign-in refuses rather than degrading — a server nobody can authenticate against is
  safer than one that authenticates nobody.

Pre-existing resources (flows, agents, MCP definitions, databases) are now behind sign-in but are
still shared across the deployment rather than partitioned per organization; repartitioning them
would be a data migration that breaks existing installs.

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
| POST | `/api/webhooks/{flowId}` | inbound webhook that starts a run with the event payload ([auth](#webhook-authentication)) |
| GET | `/api/rag/status` · POST `/api/rag/preview` | RAG capabilities / preview a SQL source's rows |
| GET | `/api/account/session` | whether sign-in is required, and who is signed in |
| POST | `/api/account/login` · `/api/account/logout` | sign in / out ([details](#sign-in-and-organizations)) |
| GET/POST | `/api/account/members` | organization members (admin only) · `POST /api/account/password` |

Flows persist as JSON under `apps/backend/data/flows` (override with `APP_DATA_DIR`).

## Headless: run a flow from a terminal

A flow is a JSON file and the backend jar is self-contained, so a run needs neither the desktop app
nor a browser:

```bash
pnpm desktop:build                                     # once — builds the jar
node scripts/concentus-run.mjs my-flow.json --input "go"
echo $?                                                # 0 completed · 1 failed · 2 needs a human
```

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
- Built against `anthropic-java` 2.34.0 and Spring Boot 3.5.x on Java 25.
