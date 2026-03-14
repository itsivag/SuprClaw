#!/usr/bin/env bash
set -euo pipefail

STACK_ENV_FILE="${STACK_ENV_FILE:-/etc/default/suprclaw-stack}"
if [[ -f "$STACK_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STACK_ENV_FILE"
fi

: "${POD_NAME:=suprclaw-stack}"

podman pod rm -f "$POD_NAME" >/dev/null 2>&1 || true
