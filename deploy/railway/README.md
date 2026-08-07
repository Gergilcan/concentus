# Railway

Two services from this one repository, each built from its own Dockerfile, plus Railway's Postgres.

The backend is **not** a pnpm package — `apps/backend` is Maven, and the workspace lists only
`apps/frontend`. `pnpm --filter backend ...` therefore matches nothing ("No projects matched the
filters"), whatever the command after it. It also could not work with a plain JDK builder: a run
shells out to the `claude` CLI, so the image needs Node and `@anthropic-ai/claude-code` alongside
the JRE. That is exactly what `apps/backend/Dockerfile` builds — use it, and leave Build Command
and Start Command **empty** so the image's own entrypoint runs.

## Per service

Point each service's *Settings → Config-as-code* at the file here, or set the same values by hand:

| | backend | frontend |
|---|---|---|
| Config file | `deploy/railway/backend.json` | `deploy/railway/frontend.json` |
| Dockerfile | `apps/backend/Dockerfile` | `apps/frontend/Dockerfile` |
| Root Directory | **empty** | **empty** |
| Healthcheck | `/actuator/health` | `/` |

Root Directory has to stay empty on both: the build context is what Railway hands Docker, and both
Dockerfiles copy repo-root paths (`pnpm-workspace.yaml`, `apps/backend/pom.xml`). Setting it to
`apps/backend` makes every `COPY` fail. Without config-as-code the equivalent is a
`RAILWAY_DOCKERFILE_PATH` service variable.

Nothing needs a `PORT` set: `server.port=${PORT:8080}` picks up whatever Railway assigns, and nginx
listens on 80.

## Variables

**backend** — the four that matter, same as everywhere else (see `.env.example`):

| | |
|---|---|
| `CONCENTUS_SECRET_KEY` | required; the backend will not start without it |
| `CLAUDE_CODE_OAUTH_TOKEN` | from `claude setup-token`, or `ANTHROPIC_API_KEY` for the cloud API |
| `CONCENTUS_ADMIN_EMAIL` | the account you sign in with |
| `MCP_OAUTH_REDIRECT_BASE` | the **frontend's** public URL — where an MCP OAuth sign-in returns to |

Add the Postgres service, then map its variables — Railway hands you a `postgres://` URL and Spring
wants JDBC, so this is a translation, not a copy:

```
PERSIST_DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
PERSIST_DB_USER=${{Postgres.PGUSER}}
PERSIST_DB_PASSWORD=${{Postgres.PGPASSWORD}}
```

Attach a **Volume** at `/data` as well. Flows, run history and the claude CLI's MCP registrations
live there, and Railway's filesystem is otherwise replaced on every deploy — the symptom is an app
that comes back empty after a redeploy rather than an error.

**frontend** — `CONCENTUS_BACKEND_URL=https://<backend>.up.railway.app`, its nginx proxies `/api`
and `/ws` there. The public URL, not `*.railway.internal`: private networking is IPv6-only and
nginx resolves the upstream once at startup, which is a different problem to debug than the one you
are solving. Both services being public costs nothing extra; the browser only ever talks to the
frontend.
