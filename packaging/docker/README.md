# Concentus in Docker: a hub and its runners

Two images from one `Dockerfile`, published on every stable release:

| Image | What it is |
|---|---|
| `ghcr.io/gergilcan/concentus-hub` | The control plane — flows, runs, accounts, credentials, the UI. The backend jar started normally, listening on `8080`, its data under `/data`. |
| `ghcr.io/gergilcan/concentus-runner` | The execution half on its own — the same jar in `runner` mode, with `git`, Node and the `claude` CLI installed. It connects *out* to a hub with a token minted there and runs the CLI turns the hub hands it. No port, no database. |

Both tagged `<version>` and `latest`. To build them here instead, from the **repository root**
(the context is the whole tree, because the jar is built from it):

```sh
docker build -f packaging/docker/Dockerfile --target hub    -t concentus-hub    .
docker build -f packaging/docker/Dockerfile --target runner -t concentus-runner .
```

## The rule about logins

A runner runs flows on a Claude subscription, and Anthropic's terms do not allow a subscription's
credentials to be routed through a third party — so the hub never holds one. **Put *your*
`claude setup-token` (or an API key) in the runner's environment, on infrastructure you operate;
the hub only ever learns which kind of auth the runner has, never the credential.** A runner
somebody else operates is theirs to log in, and the hub cannot do it for them.

## The order, with compose

The runner's token is minted in the hub's UI, so the hub has to exist first:

1. `cp .env.example .env` and fill in `CONCENTUS_SECRET_KEY` (`openssl rand -base64 32`).
   Keep it: it seals every stored credential, and a different one on the next start locks
   everything sealed before.
2. `docker compose up -d hub`, then open <http://localhost:8080> and create the first account —
   or set `CONCENTUS_ADMIN_EMAIL` / `CONCENTUS_ADMIN_PASSWORD` in `.env` before step 2 for a hub
   nobody will open in a browser.
3. In the hub: **Resources → Runners → + New**. Name it, choose who may use it (the organization,
   a group, or only you), and copy the token from the answer — it is shown once. Put it in
   `.env` as `CONCENTUS_RUNNER_TOKEN`, together with your `CLAUDE_CODE_OAUTH_TOKEN` (from
   `claude setup-token` on a machine where you are logged in) or an `ANTHROPIC_API_KEY`.
4. `docker compose --profile runner up -d`. The runner shows as online in the roster within
   seconds; a flow whose *Runs on* names it, or says *Any runner*, now executes there.

`docker compose up` builds both images from this checkout. To run the published ones instead:
`docker compose pull` and `docker compose --profile runner up -d --no-build`.

## What to know before relying on it

- **The hub is reached over a network by design**, and the sign-in is what stands in front of
  it. Put TLS in front of it: runners present their token as a bearer on a WebSocket, and
  repository tokens for clones travel over that socket.
- **The hub's embedded PostgreSQL is amd64.** On an arm64 host build with
  `--platform linux/amd64`, or point the hub at a database of yours with `PERSIST_DB_URL`
  (a Team or Enterprise license; without one the hub says so and stays on the embedded one).
  The runner image runs on either architecture — it never starts a database.
- **The images carry no pgvector.** The release compiles it in a CI action against PostgreSQL's
  own headers; the Dockerfile does not repeat that. The hub's log says what that costs.
- **Context folders on a runner are the runner's.** Mount them into the container and name them
  in `LOCAL_CONTEXT_ROOTS`; with the variable empty every context folder is rejected, exactly as
  on a hub.
- **A turn does not survive the runner disconnecting.** The hub ends the turn as *disconnected*
  after 45 s without a heartbeat; what the runner was running finishes on its side, its output
  lost. The runner reconnects on its own (2 s → 60 s backoff).
- **The volumes are the deployment.** `hub-data` holds the database; `runner-data` the run
  workspaces and clones. Both are named volumes owned by the image's `concentus` user
  (uid 10001); a bind mount in their place needs that ownership on the host side.
