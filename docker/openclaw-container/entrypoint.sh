#!/bin/bash
set -e

# OpenClaw Container Entrypoint
#
# This script configures and starts the OpenClaw environment
# Environment variables expected:
# - GATEWAY_TOKEN: OpenClaw gateway authentication token (required)
# - HOOK_TOKEN: Webhook token for task notifications (required)
# - SUPABASE_URL: User's Supabase project URL (required)
# - SUPABASE_SERVICE_KEY: User's Supabase service key (required)
# - SUPABASE_PROJECT_REF: User's Supabase project reference (required)
# - SUPABASE_SCHEMA: Supabase schema name (optional, default: public)
# - SUPABASE_API_KEY: Kong API key for self-hosted Supabase (optional)
# - MCP_CONFIG_JSON: JSON string with MCP tool configurations (optional)
# - USER_ID: The user ID this container belongs to (required)
# - LEAD_AGENT_ID: Supabase UUID for the lead agent (patched post-provisioning via docker exec)
# - AUTO_APPROVE_PROXY_PAIRING: auto-approve the first pending remote pairing request (optional, default: true)
# - AUTO_APPROVE_PROXY_PAIRING_WINDOW_SECONDS: bootstrap auto-approval window in seconds (optional, default: 180)

# Color codes for logging
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Validate required environment variables
validate_env() {
    local missing=()

    [[ -z "$GATEWAY_TOKEN" ]] && missing+=("GATEWAY_TOKEN")
    [[ -z "$HOOK_TOKEN" ]] && missing+=("HOOK_TOKEN")
    [[ -z "$SUPABASE_URL" ]] && missing+=("SUPABASE_URL")
    [[ -z "$SUPABASE_SERVICE_KEY" ]] && missing+=("SUPABASE_SERVICE_KEY")
    [[ -z "$SUPABASE_PROJECT_REF" ]] && missing+=("SUPABASE_PROJECT_REF")
    [[ -z "$USER_ID" ]] && missing+=("USER_ID")

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required environment variables: ${missing[*]}"
        exit 1
    fi
}

# Setup OpenClaw configuration
setup_openclaw_config() {
    log_info "Setting up OpenClaw configuration..."

    local config_dir="/home/openclaw/.openclaw"
    local config_file="$config_dir/openclaw.json"

    mkdir -p "$config_dir"

    local bedrock_model="${OPENCLAW_MODEL:-amazon-bedrock/minimax.minimax-m2.1}"
    local bedrock_model_id="${bedrock_model##*/}"
    local aws_region="${AWS_REGION:-us-east-1}"

    cat > "$config_file" <<EOF
{
  "models": {
    "providers": {
      "amazon-bedrock": {
        "baseUrl": "https://bedrock-runtime.${aws_region}.amazonaws.com",
        "auth": "aws-sdk",
        "api": "bedrock-converse-stream",
        "models": [
          {
            "id": "${bedrock_model_id}",
            "name": "MiniMax M2.1 (Bedrock)",
            "reasoning": false,
            "input": ["text"],
            "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
            "contextWindow": 1000000,
            "maxTokens": 8192
          }
        ]
      }
    },
    "bedrockDiscovery": {
      "enabled": false,
      "region": "${aws_region}",
      "providerFilter": ["minimax"],
      "refreshInterval": 3600
    }
  },
  "agents": {
    "defaults": {
      "model": { "primary": "${bedrock_model}" },
      "models": { "${bedrock_model}": { "alias": "MiniMax" } },
      "workspace": "/home/openclaw/.openclaw/workspace-shared",
      "compaction": { "mode": "safeguard" },
      "heartbeat": { "every": "4h" },
      "maxConcurrent": 4,
      "subagents": { "maxConcurrent": 8 },
      "sandbox": { "mode": "non-main", "workspaceAccess": "rw", "scope": "agent" }
    },
    "list": [
      { "id": "main", "name": "main", "workspace": "/home/openclaw/.openclaw/workspace", "model": "${bedrock_model}" }
    ]
  },
  "messages": { "ackReactionScope": "group-mentions" },
  "commands": { "native": "auto", "nativeSkills": "auto" },
  "hooks": {
    "enabled": true,
    "path": "/hooks",
    "token": "${HOOK_TOKEN}",
    "defaultSessionKey": "hook:ingress",
    "allowRequestSessionKey": true,
    "allowedSessionKeyPrefixes": ["hook:"],
    "allowedAgentIds": ["main"]
  },
  "gateway": {
    "port": 18788,
    "mode": "local",
    "bind": "loopback",
    "controlUi": {
      "allowedOrigins": ["*"]
    },
    "auth": {
      "mode": "token",
      "token": "${GATEWAY_TOKEN}"
    },
    "remote": {
      "token": "${GATEWAY_TOKEN}"
    },
    "tailscale": { "mode": "off", "resetOnExit": false },
    "nodes": {
      "denyCommands": ["camera.snap","camera.clip","screen.record","calendar.add","contacts.add","reminders.add"]
    }
  }
}
EOF

    chown -R openclaw:openclaw "$config_dir"
    chmod 600 "$config_file"

    log_info "OpenClaw configuration written to $config_file"
}

# Setup MCP tools configuration
setup_mcp_config() {
    log_info "Setting up MCP configuration..."

    local mcp_dir="/home/openclaw/.openclaw"
    local mcp_file="$mcp_dir/mcp.json"

    if [[ -n "$MCP_CONFIG_JSON" ]]; then
        echo "$MCP_CONFIG_JSON" > "$mcp_file"
        chown openclaw:openclaw "$mcp_file"
        chmod 600 "$mcp_file"
        log_info "MCP configuration written"
    else
        echo '{"tools": []}' > "$mcp_file"
        chown openclaw:openclaw "$mcp_file"
        log_warn "No MCP_CONFIG_JSON provided, creating empty config"
    fi
}

# Setup environment file for processes (secrets — root:root 600)
setup_environment() {
    log_info "Setting up environment..."

    local env_file="/etc/suprclaw/mcp.env"
    mkdir -p /etc/suprclaw

    cat > "$env_file" <<EOF
OPENCLAW_GATEWAY_TOKEN=${GATEWAY_TOKEN}
SUPABASE_URL=${SUPABASE_URL}
SUPABASE_SERVICE_KEY=${SUPABASE_SERVICE_KEY}
SUPABASE_PROJECT_REF=${SUPABASE_PROJECT_REF}
SUPABASE_SCHEMA=${SUPABASE_SCHEMA:-public}
SUPABASE_API_KEY=${SUPABASE_API_KEY:-}
SUPABASE_ACCESS_TOKEN=${SUPABASE_SERVICE_KEY}
AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-}
AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-}
AWS_REGION=${AWS_REGION:-us-east-1}
AWS_BEARER_TOKEN_BEDROCK=${AWS_BEARER_TOKEN_BEDROCK:-}
USER_ID=${USER_ID}
EOF

    chmod 600 "$env_file"
    log_info "mcp.env written"
}

# nginx config is baked into the image — just validate it at startup
verify_nginx() {
    nginx -t 2>&1 && log_info "nginx config OK" || { log_error "nginx config invalid"; exit 1; }
}

# Write only IDENTITY.md at runtime (needs LEAD_AGENT_ID env var interpolation).
# All other workspace files (AGENTS.md, TOOLS.md, HEARTBEAT.md, BOOTSTRAP.md, SOUL.md,
# memory/heartbeat-state.json) are baked into the image via Dockerfile COPY.
setup_workspace() {
    log_info "Setting up lead agent workspace..."
    local workspace="/home/openclaw/.openclaw/workspace"
    mkdir -p "$workspace/memory"

    cat > "$workspace/IDENTITY.md" <<EOF
---

# IDENTITY.md — Lead Coordinator

## UID
UID in Supabase: \`${LEAD_AGENT_ID:-unknown}\`

You are the Lead Coordinator a.k.a CEO is the orchestration authority of Mission Control.

They see the entire task system, control assignment flow, and ensure work progresses from intake to verified completion.

They do not create deliverables.
They create movement.

They operate from system state (Supabase + memory), not assumptions.

Calm. Decisive. Data-driven. Minimal.

Their success is measured by flow, clarity, and zero stagnation.
EOF

    chown -R openclaw:openclaw "$workspace"
    log_info "Workspace ready at $workspace"
}

setup_mcp_routes() {
    log_info "Writing mcp-routes.json..."
    mkdir -p /etc/suprclaw
    if [[ -n "$MCP_ROUTES_JSON" ]]; then
        echo "$MCP_ROUTES_JSON" > /etc/suprclaw/mcp-routes.json
        chmod 600 /etc/suprclaw/mcp-routes.json
        chown root:root /etc/suprclaw/mcp-routes.json
        log_info "mcp-routes.json written ($(echo "$MCP_ROUTES_JSON" | jq 'keys' -c 2>/dev/null || echo '?') tools)"
    else
        echo '{}' > /etc/suprclaw/mcp-routes.json
        chmod 600 /etc/suprclaw/mcp-routes.json
        log_warn "MCP_ROUTES_JSON not set, wrote empty routes"
    fi
}

setup_mcporter_config() {
    log_info "Writing mcporter.json..."
    mkdir -p /home/openclaw/.mcporter /home/openclaw/config
    local content
    if [[ -n "$MCP_MCPORTER_JSON" ]]; then
        content="$MCP_MCPORTER_JSON"
    else
        content='{"mcpServers":{}}'
    fi
    echo "$content" > /home/openclaw/.mcporter/mcporter.json
    echo "$content" > /home/openclaw/config/mcporter.json
    chown openclaw:openclaw /home/openclaw/.mcporter/mcporter.json /home/openclaw/config/mcporter.json
    chmod 644 /home/openclaw/.mcporter/mcporter.json /home/openclaw/config/mcporter.json
    log_info "mcporter.json written"
}

# Setup MCP credentials directory
setup_mcp_credentials() {
    log_info "Setting up MCP credentials..."

    local creds_dir="/etc/suprclaw"
    mkdir -p "$creds_dir"

    if [[ -n "$MCP_CONFIG_JSON" ]]; then
        echo "$MCP_CONFIG_JSON" | jq -r '.tools[]? | select(.env) | .name' 2>/dev/null | while read -r tool_name; do
            local tool_env=$(echo "$MCP_CONFIG_JSON" | jq -r ".tools[] | select(.name == \"$tool_name\") | .env // {}")
            if [[ "$tool_env" != "{}" && "$tool_env" != "null" ]]; then
                echo "$tool_env" > "$creds_dir/${tool_name}.env"
                chown root:root "$creds_dir/${tool_name}.env"
                chmod 600 "$creds_dir/${tool_name}.env"
            fi
        done
    fi
}

# Verify OpenClaw installation
verify_openclaw() {
    log_info "Verifying OpenClaw installation..."

    if ! command -v openclaw &> /dev/null; then
        log_error "OpenClaw command not found"
        exit 1
    fi

    local version=$(openclaw --version 2>/dev/null || echo "unknown")
    log_info "OpenClaw version: $version"
}

# Print startup banner
print_banner() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║          SuprClaw OpenClaw Container                         ║"
    echo "║          User: $USER_ID                                      ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    log_info "Starting container initialization..."
}

# Main initialization
main() {
    print_banner

    # Validate environment
    validate_env

    # Setup configurations
    verify_openclaw
    setup_openclaw_config
    setup_mcp_config
    setup_environment
    verify_nginx
    setup_workspace
    setup_mcp_routes
    setup_mcporter_config
    setup_mcp_credentials

    # Ensure proper permissions
    chown -R openclaw:openclaw /home/openclaw
    chown -R openclaw:openclaw /var/log/openclaw-* 2>/dev/null || true

    log_info "Container initialization complete!"
    log_info "Starting supervisord..."
    echo ""

    # Execute the provided command (default: supervisord)
    exec "$@"
}

# Run main with all arguments
main "$@"
