# Production Deployment

SuprClaw production deployment now targets a single Hetzner VPS running a Podman stack:

- `suprclaw-backend`
- optional `litellm`
- `caddy`

The backend no longer runs as a bare-metal systemd `java -jar` service.

## Recommended server

- Provider: Hetzner Cloud
- Type: `cpx32`
- Location: `nbg1`
- OS: Ubuntu 24.04

## Provision the VPS

Use the bootstrap script:

```bash
BACKEND_DEPLOY_SSH_PUBLIC_KEY="$(cat ~/.ssh/github_actions_deploy.pub)" \
HETZNER_API_TOKEN=... \
HETZNER_SSH_KEY_IDS=... \
/Users/itsivag/IdeaProjects/suprclaw/SuprClaw/deploy/hetzner/create-backend-vps.sh suprclaw-backend cpx32
```

This installs Podman and prepares:

- `/opt/suprclaw/podman`
- `/etc/suprclaw`
- `/var/lib/suprclaw/caddy`

## Deploy flow

Deployment is driven by [deploy.yml](/Users/itsivag/IdeaProjects/suprclaw/SuprClaw/.github/workflows/deploy.yml).

On each push to `main` or manual dispatch:

1. Gradle builds `SuprClaw-all.jar`
2. GitHub Actions builds and pushes `ghcr.io/<owner>/suprclaw-backend`
3. The workflow reads Hetzner and GHCR deploy credentials from `ENV_FILE`
4. The workflow uploads the Podman deployment bundle
5. The host restarts `suprclaw-stack.service`

Deployment-related keys expected inside `ENV_FILE`:

```env
HETZNER_SERVER_IP=...
HETZNER_DEPLOY_USER=suprclaw
GHCR_PULL_USERNAME=...
GHCR_PULL_TOKEN=...
SSH_PRIVATE_KEY=...
SUPRCLAW_API_HOST=api.suprclaw.com
```

Optional LiteLLM settings also live in the same `ENV_FILE`:

```env
LITELLM_ENABLED=true
LITELLM_IMAGE=ghcr.io/berriai/litellm:main-stable
LITELLM_MASTER_KEY=...
LITELLM_PROXY_MODEL_NAME=suprclaw-default
LITELLM_UPSTREAM_MODEL=bedrock/minimax.minimax-m2.1
LITELLM_PUBLIC_BASE=https://api.suprclaw.com/litellm/v1
AWS_BEARER_TOKEN_BEDROCK=...
# or AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION
```

When enabled, the deploy workflow generates the LiteLLM host files from `ENV_FILE` and exposes the proxy at:

```text
https://api.suprclaw.com/litellm/v1
```

## Stack files on the host

- `/etc/default/suprclaw-stack`
- `/etc/suprclaw/backend.env`
- `/etc/suprclaw/litellm.env`
- `/etc/suprclaw/litellm-config.yaml`
- `/etc/suprclaw/Caddyfile`
- `/etc/systemd/system/suprclaw-stack.service`

## Verification

```bash
ssh suprclaw@YOUR_HETZNER_IP
sudo systemctl status suprclaw-stack --no-pager
sudo podman ps
curl -H 'Host: api.suprclaw.com' http://127.0.0.1/health
```

## Rollback

Rollback is image-based. Edit `/etc/default/suprclaw-stack` and pin `BACKEND_IMAGE` to an older GHCR tag, then restart:

```bash
sudo systemctl restart suprclaw-stack
```
