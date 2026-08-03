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

### Docker Compose (backend + frontend + Postgres)

```bash
./scripts/setup-env.sh        # or: .\scripts\setup-env.ps1  on Windows
                              # creates .env and generates CONCENTUS_SECRET_KEY
```

Then fill in two values in `.env` — the script tells you which:

| | |
|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | from `claude setup-token` above. Without it every run fails at "Not signed in". |
| `CONCENTUS_ADMIN_EMAIL` | the account you sign in with. Leave `CONCENTUS_ADMIN_PASSWORD` blank and one is generated and printed **once** in the backend log. |

```bash
docker compose up --build
# frontend → http://localhost:3000   ·   backend → http://localhost:8080
```

Three services: `db` (Postgres, on a named volume), `backend` (the jar plus the `claude` CLI), and
`frontend` (nginx serving the SPA and proxying `/api` + `/ws`). The frontend waits for the backend's
health check rather than merely for the container to exist — nginx resolves `backend` at startup and
exits if the name isn't there yet. The marketing site (`apps/website`) is not part of compose.

Everything else is entered **in the app**, not in a file: mailbox sign-ins, Holded credentials,
GitHub/GitLab tokens. They are encrypted with `CONCENTUS_SECRET_KEY` before storage, which is why
that key belongs with the database — change it and every stored credential becomes unreadable.

Two things worth setting once in `.env` so nothing asks again:

- `MAIL_MICROSOFT_TENANT_ID` / `MAIL_MICROSOFT_CLIENT_ID` — the Entra app registration for Microsoft
  365 mailboxes. One registration serves every mailbox (it identifies the *application*, not the
  mailbox), so with these set the Input node stops asking; a node can still override them under
  *Advanced* for a second tenant. Neither is a secret.
- `LOCAL_CONTEXT_ROOTS` — only if agents need host folders as context. Repository nodes don't: they
  clone into the run's own working directory.

**MCP servers inside the container.** User-scope MCP registrations and their OAuth authorizations
live in `.claude.json`, which by default sits at `~/.claude.json` — *outside* `~/.claude`. Compose
therefore sets `CLAUDE_CONFIG_DIR=/root/.claude` so it lands on the `concentus-claude` volume;
without that, every `docker compose up --build` would silently drop every MCP server a flow depends
on, and the first sign of it would be an agent reporting that it has no tools.

A server that authenticates with a **bearer token** works headlessly and is the path to prefer. One
that needs an interactive OAuth sign-in cannot complete it in a container — there is no browser —
so authorize it once from a shell in the container:

```bash
docker compose exec backend claude mcp login "<server name>"
```

#### Reusing your host Claude login

Bind-mounting the host's `~/.claude` also carries MCP authorizations across, but two Claude
installations writing the same files corrupt each other's state — stop the host CLI while the
container runs, or prefer `claude setup-token`. Replace the `concentus-claude` volume line with:

```yaml
      - ${CLAUDE_HOME}/.claude:/root/.claude
      - ${CLAUDE_HOME}/.claude.json:/root/.claude.json
```

and set `CLAUDE_HOME` in `.env` (`/home/you`, or `C:/Users/you`). Compose does not expand `$HOME`
or `%USERPROFILE%` for you, which is why this is an explicit variable.

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

## Models

Flows run on **Claude**, and which credential is present decides where:

| Credential | Backend | How it runs |
|---|---|---|
| Claude Code sign-in (`claude`) | **Local** | the `claude` CLI on your subscription, on this machine |
| `ANTHROPIC_API_KEY` | **API** | Anthropic's hosted Managed-Agents session |

`ANTHROPIC_AUTH_MODE` picks between them: `auto` (default — key set means API, otherwise local),
`local`, or `api-key`. The toolbar badge shows which is active, and `GET /api/auth/status` reports
it. With neither credential the designer still runs; only launching a flow fails, with a message
saying so.

A flow names a model (`claude-opus-4-8`, `claude-sonnet-5`, …). The field is free text, so a model
released after this list still works — the picker is a shortcut, not a whitelist.

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

`CONCENTUS_SECRET_KEY` is **required**: the backend refuses to start without it. Every credential
it holds depends on that key, so starting anyway would leave a half-working deployment — credential
fields that silently cannot save, mail triggers quietly not polling. It fails once, at startup, with
the command to generate one. Changing the key makes existing credentials unreadable; they have to
be re-entered.

### Git providers, and why OAuth is the wrong route here

The MCP registration is a header, not a browser flow — `claude mcp add --transport http … -H "<header>"`.
So any credential that works as a header works headlessly, which is what makes this usable from
Docker at all: the interactive OAuth sign-in needs a terminal and a browser, and a container has
neither. `GET /api/mcp/capabilities` reports that, and the UI offers the token route instead of a
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
