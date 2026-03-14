#!/usr/bin/env bash
set -euo pipefail

RUNTIME_HOME="${PICOCLAW_HOME:-/home/picoclaw/.picoclaw}"
RUNTIME_CONFIG="${PICOCLAW_CONFIG:-$RUNTIME_HOME/picoclaw.json}"
WORKSPACE_DIR="$RUNTIME_HOME/workspace"
TEMPLATE_DIR="/opt/suprclaw/workspace"

mkdir -p "$RUNTIME_HOME" "$WORKSPACE_DIR"
chown -R picoclaw:picoclaw /home/picoclaw "$RUNTIME_HOME"

if [ -d "$TEMPLATE_DIR" ]; then
  cp -a "$TEMPLATE_DIR/." "$WORKSPACE_DIR/"
  chown -R picoclaw:picoclaw "$WORKSPACE_DIR"
fi

MODEL_NAME="${PICOCLAW_MODEL_NAME:-suprclaw-default}"
if [ -n "${PICOCLAW_MODEL_ID:-}" ]; then
  MODEL_ID="$PICOCLAW_MODEL_ID"
else
  MODEL_ID="${LITELLM_MODEL_ID:-litellm/auto}"
fi

if [ -n "${PICOCLAW_PROVIDER_API_BASE:-}" ]; then
  MODEL_API_BASE="$PICOCLAW_PROVIDER_API_BASE"
elif [[ "$MODEL_ID" == litellm/* ]]; then
  MODEL_API_BASE="${LITELLM_API_BASE:-}"
elif [[ "$MODEL_ID" == openai/* ]]; then
  MODEL_API_BASE="https://api.openai.com/v1"
else
  MODEL_API_BASE=""
fi

if [ -n "${PICOCLAW_PROVIDER_API_KEY:-}" ]; then
  MODEL_API_KEY="$PICOCLAW_PROVIDER_API_KEY"
elif [[ "$MODEL_ID" == litellm/* ]]; then
  MODEL_API_KEY="${LITELLM_API_KEY:-}"
elif [[ "$MODEL_ID" == openai/* ]]; then
  MODEL_API_KEY="${OPENAI_API_KEY:-}"
else
  MODEL_API_KEY=""
fi

if [ -n "${AWS_BEARER_TOKEN_BEDROCK:-}" ] && [ -z "${PICOCLAW_PROVIDER_API_BASE:-}" ] && [ -z "${PICOCLAW_PROVIDER_API_KEY:-}" ]; then
  cat >&2 <<'WARN'
[suprclaw] AWS_BEARER_TOKEN_BEDROCK is set, but PicoClaw does not support amazon-bedrock as a native model protocol.
[suprclaw] Use LiteLLM as the shared Bedrock proxy by setting LITELLM_API_BASE and optionally LITELLM_API_KEY.
[suprclaw] Otherwise set PICOCLAW_MODEL_ID and PICOCLAW_PROVIDER_API_KEY for another supported provider.
WARN
fi

if [[ "$MODEL_ID" == litellm/* ]] && [ -z "$MODEL_API_BASE" ]; then
  cat >&2 <<'WARN'
[suprclaw] LiteLLM is selected, but LITELLM_API_BASE is not set.
[suprclaw] Set LITELLM_API_BASE to your shared LiteLLM endpoint, for example http://litellm.internal:4000/v1.
WARN
fi
PICO_CHANNEL_TOKEN="${GATEWAY_TOKEN:-${PICOCLAW_CHANNELS_PICO_TOKEN:-suprclaw-pico-token}}"

jq -n \
  --arg workspace "$WORKSPACE_DIR" \
  --arg modelName "$MODEL_NAME" \
  --arg modelId "$MODEL_ID" \
  --arg apiBase "$MODEL_API_BASE" \
  --arg apiKey "$MODEL_API_KEY" \
  --arg picoToken "$PICO_CHANNEL_TOKEN" \
  '{
    agents: {
      defaults: {
        workspace: $workspace,
        restrict_to_workspace: true,
        model_name: $modelName,
        max_tool_iterations: 20,
        summarize_message_threshold: 20,
        summarize_token_percent: 75
      },
      list: [
        {
          id: "main",
          default: true,
          name: "Lead",
          workspace: $workspace,
          model: $modelName
        }
      ]
    },
    model_list: [
      ({
        model_name: $modelName,
        model: $modelId
      }
      + (if $apiBase != "" then {api_base: $apiBase} else {} end)
      + (if $apiKey != "" then {api_key: $apiKey} else {} end))
    ],
    gateway: {
      host: "0.0.0.0",
      port: 18790
    },
    channels: {
      pico: {
        enabled: true,
        token: $picoToken
      }
    },
    tools: {
      mcp: {
        servers: {}
      }
    }
  }' > "$RUNTIME_CONFIG"

chown picoclaw:picoclaw "$RUNTIME_CONFIG"

exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf -n
