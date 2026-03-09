#!/bin/bash
set -e

# Create Host VPS Script
#
# This script creates a new Hetzner VPS pre-configured as a Docker multi-tenant host.
# It uses the Hetzner CLI (hcloud) to provision the server.
#
# Prerequisites:
#   - hcloud CLI installed and authenticated
#   - SSH key added to Hetzner
#   - Environment variables set (optional)
#
# Usage:
#   ./create-host-vps.sh [SERVER_NAME] [SERVER_TYPE]
#
# Environment Variables:
#   HETZNER_API_TOKEN    - Hetzner API token (if not using hcloud context)
#   HETZNER_SSH_KEY_IDS  - Comma-separated SSH key IDs
#   HETZNER_LOCATION     - Datacenter location (default: nbg1)

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP_DIR="$(dirname "$SCRIPT_DIR")/host-setup"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

# Default values
SERVER_NAME="${1:-suprclaw-host-$(date +%s)}"
SERVER_TYPE="${2:-cpx31}"  # 4 vCPU, 8 GB RAM - good for ~20 containers
LOCATION="${HETZNER_LOCATION:-nbg1}"
IMAGE="ubuntu-22.04"

# Check prerequisites
check_prerequisites() {
    log_step "Checking prerequisites..."
    
    # Check hcloud CLI
    if ! command -v hcloud &> /dev/null; then
        log_error "hcloud CLI not found. Install with:"
        log_error "  brew install hcloud  # macOS"
        log_error "  or visit: https://github.com/hetznercloud/cli"
        exit 1
    fi
    
    # Check if authenticated
    if ! hcloud context active &> /dev/null; then
        if [[ -z "$HETZNER_API_TOKEN" ]]; then
            log_error "Not authenticated with Hetzner. Either:"
            log_error "  1. Run: hcloud context create my-context"
            log_error "  2. Set HETZNER_API_TOKEN environment variable"
            exit 1
        fi
        
        # Use token for this command
        export HCLOUD_TOKEN="$HETZNER_API_TOKEN"
    fi
    
    # Check SSH keys
    if [[ -z "$HETZNER_SSH_KEY_IDS" ]]; then
        log_warn "HETZNER_SSH_KEY_IDS not set. SSH access may not work."
        log_warn "Set it to your SSH key ID from Hetzner Console."
    fi
    
    log_info "Prerequisites OK"
}

# Generate cloud-init user data
generate_userdata() {
    log_step "Generating cloud-init user data..."
    
    # Read the install script
    local install_script
    install_script=$(cat "$SETUP_DIR/install-docker.sh" | base64 -w 0)
    
    cat > /tmp/cloud-init-${SERVER_NAME}.yml <<EOF
#cloud-config
package_update: true
package_upgrade: true

users:
  - name: suprclaw
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    home: /home/suprclaw

write_files:
  - path: /tmp/install-docker.sh
    encoding: b64
    content: ${install_script}
    permissions: '0755'

runcmd:
  - /tmp/install-docker.sh > /var/log/suprclaw-setup.log 2>&1
  - echo "Setup complete at \$(date)" >> /var/log/suprclaw-setup.log

final_message: "SuprClaw host setup complete!"
EOF

    log_info "Cloud-init config created at /tmp/cloud-init-${SERVER_NAME}.yml"
}

# Create the server
create_server() {
    log_step "Creating Hetzner server..."
    log_info "Name: $SERVER_NAME"
    log_info "Type: $SERVER_TYPE"
    log_info "Location: $LOCATION"
    
    local ssh_key_args=""
    if [[ -n "$HETZNER_SSH_KEY_IDS" ]]; then
        # Parse comma-separated IDs
        IFS=',' read -ra KEY_IDS <<< "$HETZNER_SSH_KEY_IDS"
        for key_id in "${KEY_IDS[@]}"; do
            ssh_key_args="$ssh_key_args --ssh-key $key_id"
        done
    fi
    
    # Create server
    hcloud server create \
        --name "$SERVER_NAME" \
        --type "$SERVER_TYPE" \
        --image "$IMAGE" \
        --location "$LOCATION" \
        --user-data-from-file /tmp/cloud-init-${SERVER_NAME}.yml \
        $ssh_key_args \
        --poll-interval 5s \
        2>&1 | tee /tmp/hcloud-create-${SERVER_NAME}.log
    
    if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
        log_error "Failed to create server"
        exit 1
    fi
    
    log_info "Server created successfully"
}

# Wait for setup to complete
wait_for_setup() {
    log_step "Waiting for setup to complete..."
    
    # Get server IP
    local ip
    ip=$(hcloud server ip "$SERVER_NAME")
    
    log_info "Server IP: $ip"
    log_info "Waiting for cloud-init to complete (this may take 3-5 minutes)..."
    
    # Wait for SSH to be available
    local attempts=0
    while ! ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "echo 'SSH ready'" 2>/dev/null; do
        attempts=$((attempts + 1))
        if [[ $attempts -gt 60 ]]; then
            log_error "Timeout waiting for SSH"
            exit 1
        fi
        echo -n "."
        sleep 5
    done
    echo ""
    log_info "SSH is ready"
    
    # Wait for setup script to complete
    attempts=0
    while ! ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "grep 'Setup complete' /var/log/suprclaw-setup.log" 2>/dev/null; do
        attempts=$((attempts + 1))
        if [[ $attempts -gt 60 ]]; then
            log_warn "Setup timeout - checking status..."
            ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "tail -50 /var/log/suprclaw-setup.log"
            break
        fi
        echo -n "."
        sleep 10
    done
    echo ""
    
    log_info "Setup should be complete"
}

# Verify the setup
verify_setup() {
    log_step "Verifying setup..."
    
    local ip
    ip=$(hcloud server ip "$SERVER_NAME")
    
    # Check Docker
    if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "docker --version" 2>/dev/null; then
        log_info "✓ Docker is installed"
    else
        log_error "✗ Docker not found"
        return 1
    fi
    
    # Check Traefik
    if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "docker ps | grep traefik" 2>/dev/null; then
        log_info "✓ Traefik is running"
    else
        log_warn "✗ Traefik not running (may still be starting)"
    fi
    
    # Check API
    if curl -s "http://$ip:8080/ping" 2>/dev/null | grep -q "OK"; then
        log_info "✓ Traefik API responding"
    else
        log_warn "✗ Traefik API not responding (may still be starting)"
    fi
}

# Print summary
print_summary() {
    local ip
    ip=$(hcloud server ip "$SERVER_NAME")
    local server_id
    server_id=$(hcloud server describe "$SERVER_NAME" -o format='{{.ID}}')
    
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║          Docker Multi-Tenant Host Created!                   ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    log_info "Server ID: $server_id"
    log_info "Name: $SERVER_NAME"
    log_info "IP Address: $ip"
    log_info "SSH: ssh root@$ip"
    log_info "Traefik Dashboard: http://$ip:8080"
    echo ""
    log_info "To use this host in SuprClaw:"
    log_info "  1. Add to HostPoolManager database"
    log_info "  2. Configure DNS wildcard: *.suprclaw.com → $ip"
    log_info "  3. Start creating user containers!"
    echo ""
    log_info "To delete this server:"
    log_info "  hcloud server delete $SERVER_NAME"
    echo ""
}

# Cleanup on error
cleanup() {
    if [[ $? -ne 0 ]]; then
        log_error "Script failed. Cleaning up..."
        # Optional: delete partially created server
        # hcloud server delete "$SERVER_NAME" 2>/dev/null || true
    fi
}

trap cleanup EXIT

# Main
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║      SuprClaw Docker Host VPS Creator                        ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    check_prerequisites
    generate_userdata
    create_server
    wait_for_setup
    verify_setup
    print_summary
}

main "$@"
