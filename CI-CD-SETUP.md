# CI/CD Setup for Hetzner + Podman

The production backend now deploys to a Hetzner VPS as a Podman-managed container stack instead of a bare-metal `java -jar` service.

## What the workflow does

The GitHub Actions workflow at [deploy.yml](/Users/itsivag/IdeaProjects/suprclaw/SuprClaw/.github/workflows/deploy.yml):

1. builds the fat jar
2. builds and pushes `ghcr.io/<owner>/suprclaw-backend`
3. uploads the Podman deployment bundle to the Hetzner VPS
4. restarts the `suprclaw-stack.service` systemd unit

The stack contains:
- `suprclaw-backend`
- optional `litellm`
- `caddy`

## Required GitHub secrets

Required repository secret:

| Secret | Purpose |
| --- | --- |
| `ENV_FILE` | Full backend `.env` file content, including deploy variables |

These deployment variables must now live inside `ENV_FILE`:

```env
HETZNER_SERVER_IP=...
HETZNER_DEPLOY_USER=suprclaw
GHCR_PULL_USERNAME=...
GHCR_PULL_TOKEN=...
SSH_PRIVATE_KEY=...
SUPRCLAW_API_HOST=api.suprclaw.com
```

Notes:
- `SSH_PRIVATE_KEY` should remain the base64-encoded deploy private key, because the workflow decodes it before SSH use.
- `SUPRCLAW_API_HOST` is optional. If omitted, the workflow defaults to `api.suprclaw.com`.

Optional LiteLLM settings now also live inside `ENV_FILE`:

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

When LiteLLM is enabled, the deploy workflow automatically derives:
- `/etc/suprclaw/litellm.env`
- `/etc/suprclaw/litellm-config.yaml`
- backend runtime values for `LITELLM_API_BASE`, `LITELLM_API_KEY`, and `LITELLM_MODEL_ID`

## Hetzner host bootstrap

Create the VPS with:

```bash
BACKEND_DEPLOY_SSH_PUBLIC_KEY="$(cat ~/.ssh/github_actions_deploy.pub)" \
HETZNER_API_TOKEN=... \
HETZNER_SSH_KEY_IDS=... \
/Users/itsivag/IdeaProjects/suprclaw/SuprClaw/deploy/hetzner/create-backend-vps.sh suprclaw-backend cpx32
```

Recommended host:
- `cpx32`
- `nbg1`
- Ubuntu 24.04

The script installs Podman, creates the `suprclaw` user, opens `22/80/443`, and prepares the Podman directories used by deployment.

## First deployment checklist

1. Point `api.suprclaw.com` to the Hetzner VPS IP.
2. Put the deploy variables into `ENV_FILE`.
3. Make sure the deploy SSH public key is present on the host.
4. Trigger the deploy workflow from GitHub Actions.

## Runtime layout on the host

- `/opt/suprclaw/podman/up.sh`
- `/opt/suprclaw/podman/down.sh`
- `/etc/default/suprclaw-stack`
- `/etc/suprclaw/backend.env`
- `/etc/suprclaw/litellm.env`
- `/etc/suprclaw/litellm-config.yaml`
- `/etc/suprclaw/Caddyfile`
- `/etc/systemd/system/suprclaw-stack.service`

Worker hosts should use the public LiteLLM base at:

```text
https://api.suprclaw.com/litellm/v1
```

## Useful commands

```bash
ssh suprclaw@YOUR_HETZNER_IP
sudo systemctl status suprclaw-stack --no-pager
sudo podman ps
sudo podman pod ps
sudo journalctl -u suprclaw-stack -n 100 --no-pager
curl -H 'Host: api.suprclaw.com' http://127.0.0.1/health
```
