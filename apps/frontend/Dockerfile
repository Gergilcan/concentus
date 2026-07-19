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
COPY apps/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/frontend/dist /usr/share/nginx/html
EXPOSE 80
