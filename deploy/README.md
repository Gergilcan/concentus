# Deploying Concentus

Backend + frontend only (the marketing site in `apps/website` is deployed separately).

## Auth (no API key)

Concentus runs on your Claude **subscription**. Containers can't do the interactive browser login,
so mint a long-lived token once on a machine that has your subscription:

```bash
claude setup-token     # prints CLAUDE_CODE_OAUTH_TOKEN — a subscription token, NOT an API key
```

Provide it to the deployment as a secret. (Alternatively use the cloud API by setting
`ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_MODE=api-key`.)

## Images

Build and push the two images (Dockerfiles at `apps/backend` and `apps/frontend`):

```bash
docker build -t YOUR_REGISTRY/concentus-backend:1.0.0  -f apps/backend/Dockerfile  .
docker build -t YOUR_REGISTRY/concentus-frontend:1.0.0 -f apps/frontend/Dockerfile .
docker push YOUR_REGISTRY/concentus-backend:1.0.0
docker push YOUR_REGISTRY/concentus-frontend:1.0.0
```

## Helm

```bash
helm install concentus deploy/helm/concentus \
  --namespace concentus --create-namespace \
  --set backend.image.repository=YOUR_REGISTRY/concentus-backend \
  --set frontend.image.repository=YOUR_REGISTRY/concentus-frontend \
  --set backend.claudeOAuthToken="$CLAUDE_CODE_OAUTH_TOKEN" \
  --set publicNginx.enabled=true
```

- `publicNginx.enabled=true` adds a public reverse proxy (Service type `LoadBalancer` by default;
  set `publicNginx.service.type=NodePort` on bare metal).
- Prefer an existing ingress controller? Use `ingress.enabled=true` + `ingress.host` instead.
- Secrets: `backend.claudeOAuthToken` (chart creates the Secret) or `backend.existingSecret`
  (a Secret you manage, with keys `CLAUDE_CODE_OAUTH_TOKEN` and/or `ANTHROPIC_API_KEY`).
- Validate before applying: `helm template concentus deploy/helm/concentus --set publicNginx.enabled=true`.

## Kustomize

```bash
kubectl create namespace concentus
# 1) put your token in deploy/kustomize/base/secret.yaml
# 2) set your image names in deploy/kustomize/base/kustomization.yaml (images:)
kubectl apply -k deploy/kustomize/base                # internal only
kubectl apply -k deploy/kustomize/overlays/public     # + optional public nginx
```

The optional public nginx is a Kustomize **component** (`components/public-nginx`) that the `public`
overlay enables. Preview the rendered manifests with `kubectl kustomize deploy/kustomize/overlays/public`.

## Public entrypoint & webhooks

Both the Helm `publicNginx` and the Kustomize component route `/api` + `/ws` to the backend and
everything else to the frontend — one external address, and the place to terminate TLS. Webhook
triggers (`/api/webhooks/{flowId}?token=…`) need this reachable from the internet.

## Notes

- Backend state (flows, agents, runs history) lives under `/data`. Helm provisions a PVC
  (`backend.persistence`); the Kustomize base includes a 1Gi PVC. In-memory run handles still reset
  on pod restart.
- One release per namespace (the frontend proxy resolves the backend by Service name).
