# Concentus

A monorepo for designing, running, and steering Claude agents — visually.

- **Backend** (`apps/backend`) — Spring Boot API (Java 25) that compiles no-code **flows** into
  Managed-Agents multi-agent sessions, launches them, streams their output over WebSocket, and
  routes commands to running sessions. Wraps the official `com.anthropic:anthropic-java` SDK.
  Also ships the original single-agent **YAML CLI**.
- **Frontend** (`apps/frontend`) — Vite + React 19 + TypeScript + SASS. A React Flow canvas to
  build multi-agent flows (coordinator + sub-agents + MCP/repo capability nodes), a runs panel,
  and a live streaming console with a command box.

```
concentus/
├── package.json            # pnpm workspace root
├── pnpm-workspace.yaml
├── apps/
│   ├── backend/            # Spring Boot API + YAML CLI (Maven, Java 25)
│   │   └── src/main/java/com/concentus/
│   │       ├── ConcentusApplication.java   # Spring Boot entry
│   │       ├── web/         # REST controllers + WebSocket handler + CORS
│   │       ├── service/     # FlowCompiler, RunService, ManagedFlowLauncher
│   │       ├── store/       # file-backed flow store
│   │       ├── model/       # Flow/Run DTOs
│   │       ├── config/      # AgentSpec (YAML schema) + ConfigLoader
│   │       ├── runner/      # CLI runners (managed / self-hosted)
│   │       └── Main.java     # CLI entry point
│   └── frontend/           # Vite React TS SASS
│       └── src/
│           ├── flow/        # React Flow canvas + custom nodes
│           ├── components/  # Toolbar, Palette, Inspector, RunsPanel, Console, RagPanel
│           ├── state/       # zustand store (canvas <-> backend flow)
│           ├── api/         # REST + WebSocket client, shared types
│           └── styles/      # SASS theme + globals
```

## Concepts

- A **flow** is a multi-agent orchestration graph. **Agent** nodes (one marked *coordinator*),
  plus **MCP**, **Repository**, and **SQL** capability nodes. Connect a capability to an agent to
  grant access. Each sub-agent's *Delegate when…* description is what its delegator uses to route,
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

  Scoping is guidance, not enforcement: every agent in the flow is registered with the CLI, so an
  agent is *told* which agents are its own to call, but nothing physically prevents it reaching
  another. If a delegation targets an agent that isn't in the flow, the console says so instead of
  failing silently.
- An **Input / trigger** node sets how a flow starts:
  - **Manual** — run starts idle; you type the first message.
  - **Prompt** — pressing Run auto-sends a fixed prompt.
  - **Automatic (cron)** — saved flows run on a schedule.
  - **Webhook** — an external POST (e.g. a Linear issue/comment) starts a run with the event
    payload as input. Authentication is provider-agnostic: you name the **validation parameter**
    the provider sends its proof in, and paste the **secret** the provider issued (we never mint
    one). See [Webhook authentication](#webhook-authentication).
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
- **Agent library** — drop reusable agent YAMLs in `apps/backend/data/agents/` (same `AgentSpec`
  format as the CLI; two examples are seeded). The agent node inspector's **Load from library**
  dropdown populates a node from one, so you can swap agents easily.
- **RAG** — add a **SQL source** node and connect it to an agent. At run time its query is executed
  (generic JDBC; PostgreSQL bundled, add other drivers to the backend pom) and the rows are injected
  into that agent's context. Test a query from the node inspector before running.

## Prerequisites

- **Java 25 (JDK)** and **Maven** — backend
- **Node 24+** and **pnpm** — frontend
- **To run a flow** (the designer and API start without any credentials; the toolbar shows which is active):
  - **Local, on your Claude subscription (default):** sign in once with **Claude Code** (`claude`),
    and flows run locally via the `claude` CLI — no API key. Badge: **Local (subscription)**.
  - **Cloud (Managed Agents):** set `ANTHROPIC_API_KEY` and flows run in Anthropic's hosted sandbox.
  - `ANTHROPIC_AUTH_MODE` = `auto` (default: key set → cloud, else local) | `local` | `api-key`.
  - Repo / MCP / DB tokens come from the env vars named in each node.

## Run it

```bash
# once: sign in with Claude Code so flows run on your subscription
claude                # then /login
#   or, to use the cloud API instead:  export ANTHROPIC_API_KEY=sk-ant-...

pnpm install          # once, from the repo root
pnpm dev              # backend :8080 + frontend :5173, together
```

`pnpm dev` runs both, interleaving their logs under `backend`/`frontend` prefixes. To run just one:

```bash
pnpm dev:backend      # = cd apps/backend && mvn spring-boot:run
pnpm dev:frontend     # = pnpm --filter frontend dev  (proxies /api + /ws to :8080)
```

Open http://localhost:5173, drop an **Agent** node (auto-marked coordinator), add more agents +
MCP/Repository nodes, wire them up, **Save**, then **Run**. Pick the run in the bottom panel and
send commands in the console.

## Deploy

The backend runs on your Claude **subscription** with no API key. Because containers can't do the
interactive browser login, mint a long-lived **subscription token once on your host** and hand it to
the deployment (this is a subscription OAuth token from `claude setup-token`, *not* an API key):

```bash
claude setup-token            # opens a browser once; prints CLAUDE_CODE_OAUTH_TOKEN
```

### Docker Compose (backend + frontend)

```bash
echo "CLAUDE_CODE_OAUTH_TOKEN=..." > .env    # paste the token from setup-token
docker compose up --build
# frontend → http://localhost:3000   ·   backend → http://localhost:8080
```

The backend image bundles the `claude` CLI; the frontend image is nginx serving the SPA and proxying
`/api` + `/ws`. The marketing site (`apps/website`) is not part of compose. See
[docker-compose.yml](docker-compose.yml).

### Kubernetes — Helm

```bash
helm install concentus deploy/helm/concentus \
  --namespace concentus --create-namespace \
  --set backend.claudeOAuthToken="$CLAUDE_CODE_OAUTH_TOKEN" \
  --set backend.image.repository=YOUR_REGISTRY/concentus-backend \
  --set frontend.image.repository=YOUR_REGISTRY/concentus-frontend \
  --set publicNginx.enabled=true            # optional public entrypoint (LoadBalancer)
```

Key values: `backend.authMode` (`local`|`api-key`|`auto`), `backend.claudeOAuthToken` or
`backend.existingSecret`, `backend.persistence.*`, `publicNginx.enabled` / `publicNginx.service.type`
(`LoadBalancer`|`NodePort`), and an alternative `ingress.enabled`. Without a public entrypoint,
port-forward the frontend Service. See [deploy/helm/concentus/values.yaml](deploy/helm/concentus/values.yaml).

### Kubernetes — Kustomize

```bash
kubectl create namespace concentus
# edit deploy/kustomize/base/secret.yaml with your token first
kubectl apply -k deploy/kustomize/base                  # internal only (port-forward the frontend)
kubectl apply -k deploy/kustomize/overlays/public       # + optional public nginx (LoadBalancer)
```

The optional public nginx lives as a Kustomize **component**
([deploy/kustomize/components/public-nginx](deploy/kustomize/components/public-nginx)); the `public`
overlay enables it. Both Helm's `publicNginx` and this component route `/api` + `/ws` to the backend
and everything else to the frontend — a single external entrypoint and a natural place to terminate TLS.

> **Webhooks** need the public entrypoint (or ingress) reachable from the internet so the provider
> can POST to `/api/webhooks/{flowId}`.

## Persistence (PostgreSQL)

Runs, their events, node outputs and session ids are stored in PostgreSQL so they survive a
restart and can be continued. The backend **creates its own schema on startup**
(`create table if not exists`), so an empty database is all it ever needs — no migrations, no
seed scripts.

A database ships with every deployment path, so there is nothing to provision to get started:

| | What you get | Point it elsewhere |
|---|---|---|
| **docker-compose** | A `db` service on the compose network | Delete the service, set `PERSIST_DB_*` in `.env` |
| **Helm** | A Postgres StatefulSet + PVC (`postgresql.enabled: true`) | `postgresql.enabled: false` and fill in `externalDatabase.*` |
| **Kustomize** | `base/postgres.yaml` StatefulSet + PVC | Drop it from `resources`, override `PERSIST_DB_*` on the backend |

Using a hosted database (Neon, RDS, Cloud SQL) is just three env vars:

```bash
PERSIST_DB_URL=jdbc:postgresql://ep-xxx.eu-west-2.aws.neon.tech/neondb?sslmode=require
PERSIST_DB_USER=neondb_owner
PERSIST_DB_PASSWORD=...
```

Notes:

- **Set a real password before deploying anywhere shared.** Kustomize's `base/postgres.yaml` ships
  a placeholder you must replace; Helm generates one on first install and reuses it on upgrade.
- `PERSIST_ENABLED=false` runs without a database entirely — everything stays in memory and is
  lost on restart.
- The backend tolerates a briefly unreachable database at startup rather than crash-looping
  (`initialization-fail-timeout=-1`), so a slow database doesn't take the app down with it.

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

Flows persist as JSON under `apps/backend/data/flows` (override with `APP_DATA_DIR`).

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
- **Agent scoping steers, it doesn't isolate.** A local run is one CLI process for the whole flow,
  so context folders and delegation rosters are written into each agent's instructions rather than
  enforced: an agent is told which folders and which agents are its own, but can still reach the
  others. Real isolation needs a process per agent.
- Built against `anthropic-java` 2.34.0 and Spring Boot 3.5.x on Java 25.
