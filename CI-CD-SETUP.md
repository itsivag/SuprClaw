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

Add these repository secrets:

| Secret | Purpose |
| --- | --- |
| `SSH_PRIVATE_KEY` | Base64-encoded deploy private key used by GitHub Actions |
| `HETZNER_SERVER_IP` | Public IP of the Hetzner VPS |
| `HETZNER_DEPLOY_USER` | SSH user on the VPS, usually `suprclaw` |
| `GHCR_PULL_USERNAME` | Username used on the VPS to pull from GHCR |
| `GHCR_PULL_TOKEN` | Token used on the VPS to pull from GHCR |
| `ENV_FILE` | Full backend `.env` file content |
| `SUPRCLAW_API_HOST` | Public API hostname, usually `api.suprclaw.com` |

Optional LiteLLM secrets:

| Secret | Purpose |
| --- | --- |
| `LITELLM_IMAGE` | Override LiteLLM image, defaults to `ghcr.io/berriai/litellm:main-stable` |
| `LITELLM_ENV_FILE` | Environment file content for LiteLLM |
| `LITELLM_CONFIG_YAML` | LiteLLM config YAML mounted into the container |

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
2. Add all required GitHub secrets.
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

## Useful commands

```bash
ssh suprclaw@YOUR_HETZNER_IP
sudo systemctl status suprclaw-stack --no-pager
sudo podman ps
sudo podman pod ps
sudo journalctl -u suprclaw-stack -n 100 --no-pager
curl -H 'Host: api.suprclaw.com' http://127.0.0.1/health
```
