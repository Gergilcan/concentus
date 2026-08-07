# syntax=docker/dockerfile:1

# ---------- build the static site ----------
FROM node:24-alpine AS build
RUN corepack enable
WORKDIR /repo
# Workspace manifests first for layer caching.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/frontend/package.json apps/frontend/package.json
RUN pnpm install --filter frontend... --frozen-lockfile
COPY apps/frontend ./apps/frontend
RUN pnpm --filter frontend build

# ---------- serve via nginx (proxies /api and /ws to the backend) ----------
FROM nginx:1.27-alpine
# A template, not a finished config: the entrypoint runs envsubst over /etc/nginx/templates and
# writes the result to /etc/nginx/conf.d, which is what makes the backend URL a deploy-time choice.
COPY apps/frontend/default.conf.template /etc/nginx/templates/default.conf.template
COPY apps/frontend/proxy.conf /etc/nginx/concentus-proxy.conf
# --chmod because the entrypoint ignores a .envsh that is not executable — and it says so in a log
# line nobody reads, then serves a config built from the default backend.
COPY --chmod=0755 apps/frontend/docker-entrypoint.d/15-backend-url.envsh /docker-entrypoint.d/15-backend-url.envsh
COPY --from=build /repo/apps/frontend/dist /usr/share/nginx/html

# Which backend this SPA talks to. The default is the docker-compose service; override it to serve
# against a deployed backend (e.g. -e CONCENTUS_BACKEND_URL=https://concentus.onrender.com).
ENV CONCENTUS_BACKEND_URL=http://backend:8080
# Substitute only our own variables, so a stray env var can never rewrite part of the config.
ENV NGINX_ENVSUBST_FILTER=^CONCENTUS_
EXPOSE 80
