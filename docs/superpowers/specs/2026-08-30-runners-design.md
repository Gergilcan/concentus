# Runners — design (30 August 2026)

## 1. What it is

Concentus has one backend that is both the control plane and the place flows execute. That is
the desktop app: the `claude` CLI runs on the machine the window is on, against the login already
there. It is also why a hosted Concentus could not run anybody's flows on their subscription — the
CLI would be in the server's container, with no login, no repositories and no folders.

A **runner** is the execution half on its own: a process on a machine somebody operates — a
laptop, a NAS, a Docker container on their own cluster — that connects *outbound* to a Concentus
backend (the **hub**), registers with a token minted there, and executes the `claude` CLI turns the
hub hands it. The hub keeps everything else: flows, runs, events, approvals, credentials, groups,
policies, MCP proxying, the UI. The runner keeps the login, the folders, the clones and the
processes.

Same shape as a GitLab runner, with one rule that follows from Anthropic's terms: **the
credential stays where its owner runs the process.** The hub never holds a Claude login or a
`setup-token`; a runner is registered *by* the person or organization operating it and carries its
own auth. Concentus-hosted execution stays API-key only, as before.

Two modes follow, and no second installer:

- **Offline** — the desktop app as it is today: its own backend, its own database, execution on
  the same machine. Nothing changes.
- **Online** — a hub somebody deployed (`java -jar`, or the Docker image), and runners that
  connect to it: the desktop app *also* acting as a runner for a hub (a setting, off by default),
  the bare jar in runner mode, or the runner Docker image. The UI of the hub is reached in a
  browser, or from the desktop app's tray ("Open server").

Runners are visible to **any account in the organization, or limited to a group, or to one
person** — the registering account chooses, and the choice decides who may run flows on it.

## 2. Model

```
runners
  id               text primary key        rn_<12 hex>
  organization_id  text not null
  name             text not null           unique per organization, case-blind
  scope            text not null           'organization' | 'group' | 'user'
  group_id         text                    scope = group: the group whose members may use it
  user_id          text                    scope = user: the owner; the only account that may use it
  token_hash       text not null unique    hex SHA-256 of the registration token, crn_ + 40 chars
  created_by       text                    email, for the audit line
  created_at       bigint not null
  last_seen_at     bigint                  last heartbeat, minute resolution
  revoked_at       bigint                  null while the token works

runs + runner_id text, runner_name text    (V21: alter table; stamped at launch, history not a pointer)
```

No foreign keys, for the reason V8–V20 give. `credentials`-style re-declaration is not needed:
`runs` is re-declared `if not exists` in V21 for the V1-baselined installs, as V20 does for
`credentials`.

Live state lives in memory (`RunnerRegistry`): per connected runner its socket, what it said in
`hello` (version, OS, arch, hostname, CLI path, whether the CLI is logged in, the CLI version,
`authKind`, capacity, file separator, the hub URL it dialed), its busy count and `connectedAt`.
One connection per runner id: a second connection replaces the first, which is closed with the
reason `replaced`. A runner is **online** while its socket is open and it has heart-beaten within
45 s; the store's `last_seen_at` is written at most once a minute.

The registration token is `crn_` and forty random characters, shown exactly once, hashed like a
service account's. A runner that presents an unknown or revoked token is refused at the handshake
with `401`/`403` and must not retry.

## 3. Where a run executes: `RunHost`

The executors (`LocalClaudeExecutor`, `FanoutExecutor`) keep doing what they do — compose
`CLAUDE.md`, the agents, the MCP config, the settings, the skills; build the CLI's argv; stream
`stream-json` into the run. What they stop assuming is that the working directory, the git
checkouts and the `claude` process are on this machine. That is one interface:

```java
public interface RunHost {
    String id();                       // "local", or the runner id
    String displayName();              // "this machine" / the runner's name
    Optional<String> command();        // the claude command on that host
    ProcessCeiling ceiling();          // the hub's for local; unlimited for remote (the runner enforces its own)
    String toolsBaseUrl();             // where the CLI reaches this backend: http://127.0.0.1:<port>, or the hub URL the runner dialed
    /** The folders the host lets a run read, as absolute paths ON THAT HOST; rejections reported. */
    List<String> resolveContextDirs(List<String> requested, BiConsumer<String, String> onRejected);
    /** A referenced CLAUDE.md, read on that host under its allowlist; null when none/rejected. */
    String readClaudeMd(String raw, BiConsumer<String, String> onRejected);
    List<GitWorkspace.Checkout> prepareClones(List<RepoSpec> repos, Path workdir);
    String headOf(Path checkout);
    String patchOf(Path checkout);
    String patchSince(Path checkout, String base) throws IOException;
    /** Spawns the CLI. Remote: syncs the mirror first, rewrites workdir paths in argv, streams back. */
    Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException;
}
```

- `LocalRunHost` is what every run has today: `LocalClaudeSupport`, `ContextFolderResolver`,
  `GitWorkspace`, `ProcessBuilder`, the hub's `ProcessCeiling`.
- `RemoteRunHost` wraps one connected runner. The hub keeps writing the run's files into its own
  **mirror** directory (`<data-dir>/local/<runId>`, exactly the paths the executors already use);
  `start()` ships every file of the mirror that changed since the last sync (clones excluded — they
  exist only on the runner), rewrites every argv element and env value that starts with the mirror
  path to the runner's workdir (`<runner data-dir>/runs/<runId>`, with the runner's separator),
  and spawns there. The returned `Process` is a `RemoteProcess`: its stdout is a pipe fed by the
  runner's `stdout` frames, its stdin forwards to the runner, `destroy()` sends `proc.stop`,
  `waitFor()` completes on `exit`. Nothing in the executors changes for that: they read lines,
  write the prompt to stdin, keep the process on the run and destroy it on Stop.
- Context folders become `List<String>` in the executors' signatures (they were `List<Path>`, and
  a Linux path through a Windows `Path` comes out with backslashes). Remote folders are resolved
  by the runner against **its** `LOCAL_CONTEXT_ROOTS`.
- Checkouts on a remote host keep a `Path` whose last segment is the folder name (that is all the
  executors read from it); the `RunPatch` registered for it stores `directory` as
  `runner:<runnerId>:<absolute path on the runner>`, so `RunDiffService` routes the re-read to the
  runner while it is connected and otherwise notes *"Runner ‹name› is offline; this is the change
  as last read"*.
- `AgentRun` gains `runnerId`, `runnerName` (persisted) and a transient `host`; `RunHosts.hostOf(run)`
  answers the local host when `runnerId` is null, the remote host when that runner is connected,
  and throws *"Runner ‹name› is offline"* otherwise — a restored run whose runner is gone is IDLE
  with that line in its log, as a restart already leaves in-flight runs.
- `run.backend` stays `"local"`: it is the Claude CLI backend, billed per subscription, turn-based;
  only *where* changed. The start line says so: *Runner ‹name› — running on its Claude login*.
- `LocalClaudeExecutor.writeMcpConfig`, `CliMcpServers.node` and `FanoutExecutor.runEndpoint` take
  the base URL from the host instead of `127.0.0.1:<port>`; `app.public-url` overrides what the
  runner dialed, for a hub behind a proxy that rewrites hosts.
- Fan-out goes through the same host: worker workspaces live under the run's mirror, clones per
  worker are `prepareClones` on the host, patches are `patchOf` on the host, the merge step's
  `git apply`/commit/push run there too. Nothing is refused for being a fan-out.
- A run's turn on a remote host holds no slot of the hub's ceiling. The runner acquires one of its
  own before spawning (`EXECUTION_MAX_PROCESSES` there, default 4) and says *"waiting for a free
  slot on ‹name›"* through a `log` frame, which lands in the run as a system line.

## 4. Protocol

One WebSocket, `wss://<hub>/ws/runner`, opened by the runner with
`Authorization: Bearer crn_…`. JSON text frames, a `type` each. Requests from the hub carry a
`reqId` and are answered with `ack`. Frames about a process carry its `procId`.

Runner → hub:

| type | fields |
|---|---|
| `hello` | `version, os, arch, hostname, javaVersion, claudeCommand, claudeLoggedIn, claudeVersion, authKind` (`subscription`/`api-key`/`none`), `capacity, fileSeparator, workdirRoot, hubUrl, contextRoots[]` |
| `heartbeat` | `busy` — every 15 s |
| `ack` | `reqId, ok, error?, result?` |
| `stdout` | `procId, line` — one CLI output line, UTF-8 |
| `log` | `procId?, text` — a line for the run's console (slot waits, clone progress) |
| `exit` | `procId, code` |

Hub → runner:

| type | fields | result |
|---|---|---|
| `welcome` | `runnerId, name` | — |
| `workspace.sync` | `reqId, runId, files: [{path, content}]` (paths relative to the run's workdir; creates directories) | `{workdir}` — absolute, on the runner |
| `git.clone` | `reqId, runId, subdir?, repos: [{url, branch, token, envVar}]` | `{checkouts: [{url, folder, directory, envVar, head, ok, error}]}` |
| `git.head` | `reqId, directory` | `{head}` |
| `git.patchOf` | `reqId, directory` | `{patch}` (null = no change) |
| `git.patchSince` | `reqId, directory, base` | `{patch}` or `ok:false` |
| `context.resolve` | `reqId, folders[]` | `{accepted[], rejected: [{path, reason}]}` |
| `fs.read` | `reqId, path` | `{content}` |
| `proc.start` | `reqId, procId, runId, args[], workdir, env{}` | `{}` once started (after the slot) |
| `proc.stdin` | `procId, data, close` | — |
| `proc.stop` | `procId` | — |
| `workspace.delete` | `reqId, runId` | `{}` |

Tokens for clones travel in `git.clone` and again in `proc.start`'s `env` — over TLS, never
written to disk on either side (the runner's credential helper reads the environment variable,
exactly as the local path does). The hub marks a runner offline when the socket closes or 45 s
pass without a heartbeat; every process the runner was running for it is then reported as exited
with `-1` and the turn ends as *"Runner ‹name› disconnected"*. The runner reconnects with backoff
(2 s → 60 s), and what it was running keeps running to completion on its side, its output lost:
a turn is not resumable across a disconnection in this version.

## 5. API

```
GET    /api/runners                 {runners: RunnerView[], hubUrl, mayCreate: {organization, groups: [groupId], user}}
POST   /api/runners                 {name, scope, groupId?}  → {runner: RunnerView, token, hubUrl}
PUT    /api/runners/{id}            {name}
POST   /api/runners/{id}/revoke
DELETE /api/runners/{id}
GET    /api/runners/usable          RunnerView[] the caller may run flows on (revoked excluded, offline included)
GET    /api/runners/self            {configured, connected, hubUrl, name, error}  — this backend's own embedded runner agent
WS     /ws/runner                   the runner protocol above (bearer token, no session)
```

```ts
interface RunnerView {
  id: string; organizationId: string; name: string
  scope: 'organization' | 'group' | 'user'
  groupId: string | null; groupName: string | null
  userId: string | null; ownerEmail: string | null
  createdBy: string | null; createdAt: number; lastSeenAt: number | null; revokedAt: number | null
  online: boolean; busy: number; capacity: number | null
  hostname: string | null; os: string | null; arch: string | null; version: string | null
  claudeVersion: string | null; authKind: 'subscription' | 'api-key' | 'none' | null
  connectedAt: number | null
  mine: boolean      // the caller owns it (scope user) or registered it
  usable: boolean    // the caller may run flows on it
}
```

Who may do what:

- **See**: an ADMIN every runner of the organization; anybody else the organization-scoped ones,
  the group-scoped ones of groups they are in, and the user-scoped ones they own.
- **Register** (`POST`): ADMIN any scope; MEMBER `user` for themselves, and `group` for a group
  they manage — which needs the GROUPS feature, refused with its sentence otherwise. OPERATOR and
  VIEWER: 403 (the write rule in `SecurityConfig`, unchanged).
- **Rename, revoke, delete**: ADMIN; the owner of a user-scoped runner; a manager of a
  group-scoped runner's group.
- **Use** (run a flow on it): same organization, not revoked, and — `organization`: everyone;
  `group`: a member of the group, or an ADMIN, or a launch with no principal (a schedule, a
  webhook) whose flow belongs to that group; `user`: the owner only, never a launch with no
  principal and never an ADMIN who is not the owner — it is somebody's machine and somebody's
  login.

Audit kinds: `runner.registered`, `runner.renamed`, `runner.revoked`, `runner.deleted`.

`/api/runners/self` is reachable with the desktop shell's launch token as well as a session, so
the tray can show whether this install's agent is connected.

## 6. Which host a launch gets

`FlowGraph` gains `runner` (String, the 17th component; every convenience constructor and
`withId`/`withFolder` updated, `FlowGraphRoundTripTest` extended):

- **null / blank — automatic**: the local host when the Claude CLI backend is available here;
  otherwise, when at least one usable runner is online, the least busy one (the log says *"No
  Claude login on this server; running on runner ‹name›"*); otherwise exactly today's answer (an
  API key, or the unroutable error).
- **`any`**: the least busy usable online runner; none online → *"No runner is online for this
  flow. Start one, or set the flow to run here."*
- **an id**: that runner. Unknown, revoked or not usable by whoever launches → refused, naming it;
  offline → *"Runner ‹name› is offline."*

Only the Claude CLI backend runs on runners. A flow whose coordinator uses a self-hosted model and
names a runner is refused: *"Runners execute Claude CLI flows; ‹model› runs on the local-model
backend."* The doctor reports a flow that names a runner that is revoked or that the flow's own
group could not use.

## 7. Frontend

- **Resources → Runners** — a tab of its own, after *Service accounts*, visible to every role
  (a viewer sees where things run; only `mayCreate` decides the button). The Members/Service
  accounts roster: count + *+ New* in the header; one row per runner: name, a scope chip
  (*Organization* / the group's name / *Only me*), a status dot (online/offline, tooltip:
  hostname · OS/arch · version · CLI auth), a *busy/capacity* chip while online, *last seen*;
  quiet actions: rename (✎), *Revoke*, *Delete*. *+ New* asks a name and a scope (*Organization*
  for admins, *Group…* for the groups the caller may, *Only me*); the answer is the one screen the
  token is on: the token with a copy button, and the three ways to start it, hub URL filled in —
  `docker run …`, `java -jar concentus-backend.jar runner --url … --token …`, and "in the
  desktop app: Settings → Connect to a server". A closed answer is gone; only its hash exists.
- **Flow settings** (the *Settings* modal): *Runs on* — *This server* (default), *Any runner*,
  and each usable runner as *name · online/offline*. Tooltip: what a runner is and that only the
  Claude CLI flows use one.
- **Runs**: a chip with the runner's name on a run that ran on one (tooltip *Ran on runner X*),
  in the runs list; the console's start line already names it.
- Every string through `t()`, Spanish and Catalan in `es.json`/`ca.json`; explanations in tooltips.

## 8. Desktop

- A setting: `runner: { url, name? }` in `settings.json`, the token in the OS keyring like the API
  key (`runner-token.ts`). When both are set the shell passes `CONCENTUS_RUNNER_URL`,
  `CONCENTUS_RUNNER_TOKEN`, `CONCENTUS_RUNNER_NAME` to the backend it starts, whose embedded
  agent connects. Everything local keeps working — this is *also*, not *instead*.
- The first-run wizard gets a third, optional step, **Server**: *Connect this machine to a
  Concentus server* — server URL, registration token, a *Save* that restarts the backend; and a
  *Skip*. The same page is reachable later from the tray (*Set up…*).
- Tray: *Runner: connected to ‹host›* / *Runner: not connected — ‹reason›* (from
  `GET /api/runners/self`, polled every 30 s while the menu is open), and *Open server* when a URL
  is set — a window on the hub's UI with its own session partition.

## 9. Running a runner

```
java -jar concentus-backend.jar runner --url https://hub.example.com --token crn_… [--name office-pc]
     [--data-dir DIR] [--claude PATH] [--context-roots /srv/a,/srv/b] [--max-processes 4]
```

Environment for each: `CONCENTUS_RUNNER_URL`, `CONCENTUS_RUNNER_TOKEN`, `CONCENTUS_RUNNER_NAME`,
`CONCENTUS_RUNNER_DATA_DIR` (default `~/.concentus-runner`), `CLAUDE_COMMAND`,
`LOCAL_CONTEXT_ROOTS`, `EXECUTION_MAX_PROCESSES`. The runner's Claude auth is the CLI's own — a
login on the machine, or `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token` — or
`ANTHROPIC_API_KEY`; it reports which as `authKind`. `runner` as the first argument means the jar
never starts Spring: no database, no web server, a few hundred milliseconds to connect. Exit
codes: `2` usage, `3` token refused (no retry), `0` on SIGTERM.

`ConcentusApplication.main` dispatches on `args[0] == "runner"`. The same agent runs **inside** a
full backend when `concentus.runner.url`/`concentus.runner.token` (the env above) are set — the
desktop's "also a runner" mode, using that backend's own CLI, context roots and ceiling.

Docker, under `packaging/docker/`:

- `Dockerfile.runner` — multi-stage: the jar built on Linux (Maven + JDK 25), then
  `eclipse-temurin:25-jre` with Node, `@anthropic-ai/claude-code` and `git`; entrypoint
  `java -jar /app/concentus-backend.jar runner`; volume `/data`.
- `Dockerfile.hub` — the same jar as the control plane: `PERSIST_DB_URL` for a company database or
  the embedded PostgreSQL on `/data`, `CONCENTUS_SECRET_KEY`, `CONCENTUS_ADMIN_EMAIL/PASSWORD`,
  listening on `0.0.0.0:8080`.
- `docker-compose.yml` — `hub` + `runner`, a `.env.example` with the token slot; the README there
  says the order: start the hub, register a runner in the UI, put the token in `.env`, start the
  runner.
- `release.yml` builds and pushes `ghcr.io/gergilcan/concentus-runner:<version>` and `:latest`
  on a stable tag, in a job that cannot fail the release.

## 10. Docs

README: a *Runners* section under *Sign-in and organizations* (what, the credential rule, scopes,
how to start one, the flow setting), the API table rows, the environment variables. The site's
docs (en/es/ca): an *Runners* `<h2>` after *Teams and sign-in*; the changelog. Release notes for
0.1.14 gain the section.

## 11. Tests

Backend: `RunnerStoreTest` (real database; create, unique name, find by hash, revoke, delete,
touch), `RunnersMigrationTest` (V21 on a V1-baselined database), `RunnerServiceTest` (see /
register / use rules per role and scope, the token shape, the GROUPS refusal),
`RunnerProtocolTest` (every frame round-trips), `RemoteRunHostTest` (mirror sync, argv and env
rewrite Windows → Linux, streamed stdout and exit, destroy → `proc.stop`, clones → checkouts,
patch delegation, disconnection → exit −1), `RunnerAgentTest` (the agent against a fake hub:
hello, a workspace sync, a clone against a local bare repository, a process run of a fake CLI,
stop, reconnect on close, refusal on 401), `RunServiceRunnerSelectionTest` (automatic / any / id /
offline / not usable / self-hosted model), `FlowGraphRoundTripTest` (the new component),
`RunDiffServiceTest` (remote directory routed / offline note), and an end-to-end:
`RunnerEndToEndTest` — a real hub (`@SpringBootTest`, embedded database), a real agent in the
same JVM connected over the real socket with a fake `claude` that prints `stream-json`, a flow
set to that runner: the run completes with the fake's answer in its events and the runner named
on the run.

Frontend: `RunnersPanel.test.tsx` (roster per role, create → token shown once → gone, revoke),
`FlowModals` (the *Runs on* select), `RunsPanel` (the chip). Desktop: settings/env wiring, the
token store.

E2E (`17-runners.spec.ts`): register a runner in the UI, start the jar in runner mode from the
spec with a fake `claude`, see it online in the roster, set a flow to it, run, read the fake's
answer in the console and the runner chip on the run; revoke → offline.

## 12. Not in this version

Resuming a turn across a runner disconnection; runner-side cleanup of finished workspaces beyond
`workspace.delete` (which nothing calls yet — retention purges hub rows only); runners hosting
self-hosted models; tags/labels beyond the three scopes; a queue that waits for a runner to come
online (a launch with none online fails at once; schedules simply fire again).
