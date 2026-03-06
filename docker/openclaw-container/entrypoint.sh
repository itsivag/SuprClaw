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
# - MCP_CONFIG_JSON: JSON string with MCP tool configurations (optional)
# - USER_ID: The user ID this container belongs to (required)

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
    
    # Ensure directory exists
    mkdir -p "$config_dir"
    
    # Create OpenClaw configuration
    cat > "$config_file" <<EOF
{
  "gateway": {
    "mode": "local",
    "auth": {
      "token": "${GATEWAY_TOKEN}"
    },
    "remote": {
      "token": "${GATEWAY_TOKEN}"
    }
  },
  "hooks": {
    "token": "${HOOK_TOKEN}",
    "url": "${WEBHOOK_BASE_URL:-}"
  },
  "database": {
    "supabase": {
      "url": "${SUPABASE_URL}",
      "serviceKey": "${SUPABASE_SERVICE_KEY}",
      "projectRef": "${SUPABASE_PROJECT_REF}"
    }
  }
}
EOF
    
    # Set proper ownership
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
        # Write provided MCP configuration
        echo "$MCP_CONFIG_JSON" > "$mcp_file"
        chown openclaw:openclaw "$mcp_file"
        chmod 600 "$mcp_file"
        log_info "MCP configuration written"
    else
        # Create empty MCP config
        echo '{"tools": []}' > "$mcp_file"
        chown openclaw:openclaw "$mcp_file"
        log_warn "No MCP_CONFIG_JSON provided, creating empty config"
    fi
}

# Setup systemd user directories (for openclaw service compatibility)
setup_systemd_compat() {
    log_info "Setting up systemd compatibility..."
    
    local systemd_dir="/home/openclaw/.config/systemd/user"
    mkdir -p "$systemd_dir"
    chown -R openclaw:openclaw /home/openclaw/.config
}

# Setup environment file for processes
setup_environment() {
    log_info "Setting up environment..."
    
    local env_file="/etc/suprclaw/environment"
    mkdir -p /etc/suprclaw
    
    cat > "$env_file" <<EOF
OPENCLAW_GATEWAY_TOKEN=${GATEWAY_TOKEN}
SUPABASE_URL=${SUPABASE_URL}
SUPABASE_SERVICE_KEY=${SUPABASE_SERVICE_KEY}
SUPABASE_PROJECT_REF=${SUPABASE_PROJECT_REF}
USER_ID=${USER_ID}
EOF
    
    chmod 600 "$env_file"
    log_info "Environment file created"
}

# Setup MCP credentials directory
setup_mcp_credentials() {
    log_info "Setting up MCP credentials..."
    
    local creds_dir="/etc/suprclaw"
    mkdir -p "$creds_dir"
    
    # If MCP_CONFIG_JSON contains credentials, extract them
    if [[ -n "$MCP_CONFIG_JSON" ]]; then
        # Parse and write individual credential files if needed
        # This depends on your MCP tool structure
        echo "$MCP_CONFIG_JSON" | jq -r '.tools[]? | select(.env) | .name' 2>/dev/null | while read -r tool_name; do
            local tool_env=$(echo "$MCP_CONFIG_JSON" | jq -r ".tools[] | select(.name == \"$tool_name\") | .env // {}")
            if [[ "$tool_env" != "{}" && "$tool_env" != "null" ]]; then
                echo "$tool_env" > "$creds_dir/${tool_name}.env"
                chmod 600 "$creds_dir/${tool_name}.env"
            fi
        done
    fi
    
    chown -R openclaw:openclaw "$creds_dir"
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
    setup_systemd_compat
    setup_environment
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
