#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVER_NAME="${1:-suprclaw-backend-$(date +%s)}"
SERVER_TYPE="${2:-cpx32}"
LOCATION="${HETZNER_LOCATION:-nbg1}"
IMAGE="ubuntu-24.04"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

check_prerequisites() {
  log_step "Checking prerequisites..."

  if ! command -v hcloud >/dev/null 2>&1; then
    log_error "hcloud CLI not found. Install it first: https://github.com/hetznercloud/cli"
    exit 1
  fi

  if ! hcloud context active >/dev/null 2>&1; then
    if [[ -z "${HETZNER_API_TOKEN:-}" ]]; then
      log_error "No active hcloud context and HETZNER_API_TOKEN is not set."
      exit 1
    fi
    export HCLOUD_TOKEN="$HETZNER_API_TOKEN"
  fi

  if [[ -z "${BACKEND_DEPLOY_SSH_PUBLIC_KEY:-}" ]]; then
    log_error "BACKEND_DEPLOY_SSH_PUBLIC_KEY is required."
    exit 1
  fi
}

generate_userdata() {
  log_step "Generating cloud-init..."
  cat > "/tmp/${SERVER_NAME}-backend-cloud-init.yml" <<EOF
#cloud-config
package_update: true
package_upgrade: true

users:
  - default
  - name: suprclaw
    groups: sudo
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    ssh_authorized_keys:
      - ${BACKEND_DEPLOY_SSH_PUBLIC_KEY:-}

runcmd:
  - apt-get update -y
  - DEBIAN_FRONTEND=noninteractive apt-get install -y podman uidmap slirp4netns fuse-overlayfs jq curl ufw
  - mkdir -p /opt/suprclaw/podman /etc/suprclaw /var/lib/suprclaw/caddy/data /var/lib/suprclaw/caddy/config
  - chown -R suprclaw:suprclaw /opt/suprclaw /var/lib/suprclaw
  - systemctl enable podman-auto-update.timer || true
  - ufw --force reset
  - ufw default deny incoming
  - ufw default allow outgoing
  - ufw allow OpenSSH
  - ufw allow 80/tcp
  - ufw allow 443/tcp
  - ufw --force enable
  - touch /var/run/suprclaw-backend-host-ready

final_message: "SuprClaw backend host ready"
EOF
}

create_server() {
  log_step "Creating Hetzner server..."
  local ssh_key_args=""
  if [[ -n "${HETZNER_SSH_KEY_IDS:-}" ]]; then
    IFS=',' read -ra KEY_IDS <<< "$HETZNER_SSH_KEY_IDS"
    for key_id in "${KEY_IDS[@]}"; do
      ssh_key_args="$ssh_key_args --ssh-key ${key_id// /}"
    done
  fi

  # shellcheck disable=SC2086
  hcloud server create \
    --name "$SERVER_NAME" \
    --type "$SERVER_TYPE" \
    --image "$IMAGE" \
    --location "$LOCATION" \
    --user-data-from-file "/tmp/${SERVER_NAME}-backend-cloud-init.yml" \
    $ssh_key_args \
    --poll-interval 5s
}

wait_for_ready() {
  log_step "Waiting for host readiness..."
  local ip
  ip="$(hcloud server ip "$SERVER_NAME")"
  local attempts=0
  until ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no "root@$ip" "test -f /var/run/suprclaw-backend-host-ready && echo READY" 2>/dev/null | grep -q READY; do
    attempts=$((attempts + 1))
    if [[ $attempts -gt 90 ]]; then
      log_error "Timed out waiting for backend host readiness"
      exit 1
    fi
    sleep 10
  done
  log_info "Host ready: $ip"
}

print_summary() {
  local ip
  ip="$(hcloud server ip "$SERVER_NAME")"
  echo
  log_info "Server: $SERVER_NAME"
  log_info "Type: $SERVER_TYPE"
  log_info "Location: $LOCATION"
  log_info "IP: $ip"
  log_info "Deploy user: suprclaw"
  echo
  log_info "Next steps:"
  log_info "  1. Point api.suprclaw.com to $ip"
  log_info "  2. Add GitHub secrets HETZNER_SERVER_IP=$ip and HETZNER_DEPLOY_USER=suprclaw"
  log_info "  3. Run the deploy workflow"
}

main() {
  check_prerequisites
  generate_userdata
  create_server
  wait_for_ready
  print_summary
}

main "$@"
