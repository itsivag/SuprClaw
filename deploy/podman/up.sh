#!/usr/bin/env bash
set -euo pipefail

STACK_ENV_FILE="${STACK_ENV_FILE:-/etc/default/suprclaw-stack}"
if [[ -f "$STACK_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STACK_ENV_FILE"
fi

: "${POD_NAME:=suprclaw-stack}"
: "${BACKEND_CONTAINER_NAME:=suprclaw-backend}"
: "${LITELLM_CONTAINER_NAME:=suprclaw-litellm}"
: "${PROXY_CONTAINER_NAME:=suprclaw-caddy}"
: "${BACKEND_IMAGE:?BACKEND_IMAGE must be set in $STACK_ENV_FILE}"
: "${LITELLM_IMAGE:=ghcr.io/berriai/litellm:main-stable}"
: "${SUPRCLAW_API_HOST:=api.suprclaw.com}"
: "${BACKEND_ENV_FILE:=/etc/suprclaw/backend.env}"
: "${FIREBASE_CREDENTIALS_FILE:=/etc/suprclaw/firebase-credentials.json}"
: "${LITELLM_ENV_FILE:=/etc/suprclaw/litellm.env}"
: "${LITELLM_CONFIG_FILE:=/etc/suprclaw/litellm-config.yaml}"
: "${CADDYFILE:=/etc/suprclaw/Caddyfile}"

mkdir -p /var/lib/suprclaw/caddy/data /var/lib/suprclaw/caddy/config /opt/suprclaw/podman

if [[ ! -f "$BACKEND_ENV_FILE" ]]; then
  echo "Missing backend env file: $BACKEND_ENV_FILE" >&2
  exit 1
fi

podman pod rm -f "$POD_NAME" >/dev/null 2>&1 || true

podman pull "$BACKEND_IMAGE"
podman pull caddy:2

ENABLE_LITELLM=0
if [[ -s "$LITELLM_CONFIG_FILE" ]]; then
  ENABLE_LITELLM=1
  podman pull "$LITELLM_IMAGE"
fi

podman pod create --replace --name "$POD_NAME" -p 80:80 -p 443:443

podman run -d \
  --name "$BACKEND_CONTAINER_NAME" \
  --pod "$POD_NAME" \
  --env-file "$BACKEND_ENV_FILE" \
  -v "$FIREBASE_CREDENTIALS_FILE:$FIREBASE_CREDENTIALS_FILE:ro,Z" \
  --restart=always \
  "$BACKEND_IMAGE"

if [[ "$ENABLE_LITELLM" == "1" ]]; then
  podman run -d \
    --name "$LITELLM_CONTAINER_NAME" \
    --pod "$POD_NAME" \
    --env-file "$LITELLM_ENV_FILE" \
    -v "$LITELLM_CONFIG_FILE:/app/config.yaml:ro,Z" \
    --restart=always \
    "$LITELLM_IMAGE" \
    --config /app/config.yaml \
    --port 4000
fi

podman run -d \
  --name "$PROXY_CONTAINER_NAME" \
  --pod "$POD_NAME" \
  -e SUPRCLAW_API_HOST="$SUPRCLAW_API_HOST" \
  -v "$CADDYFILE:/etc/caddy/Caddyfile:ro,Z" \
  -v /var/lib/suprclaw/caddy/data:/data:Z \
  -v /var/lib/suprclaw/caddy/config:/config:Z \
  --restart=always \
  caddy:2

podman ps --filter "pod=$POD_NAME"
