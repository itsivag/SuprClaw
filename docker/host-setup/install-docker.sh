#!/bin/bash
set -e

# Host VPS Setup Script
# 
# This script prepares a fresh Ubuntu VPS to be a Docker multi-tenant host.
# Run this on the host VPS (or include in cloud-init user-data).
#
# Usage: curl -fsSL https://your-domain.com/install-docker.sh | bash

# Configuration
TRAEFIK_VERSION="v3.0"
DOCKER_COMPOSE_VERSION="v2.27.0"
SUPRCLAW_DIR="/opt/suprclaw"
TRAEFIK_DIR="/opt/traefik"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Check if running as root
check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "This script must be run as root"
        exit 1
    fi
}

# Wait for apt lock to be released (Ubuntu cloud-init boots while unattended-upgrades holds the lock)
wait_for_apt() {
    local deadline=$(($(date +%s) + 180))
    while fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || fuser /var/lib/apt/lists/lock >/dev/null 2>&1; do
        if [[ $(date +%s) -gt $deadline ]]; then
            log_warn "Timed out waiting for apt lock — proceeding anyway"
            break
        fi
        log_info "Waiting for apt lock to be released..."
        sleep 5
    done
}

# Update system packages
update_system() {
    log_step "Updating system packages..."
    wait_for_apt
    # Kill any lingering unattended-upgrade processes to free the lock
    systemctl stop unattended-upgrades 2>/dev/null || true
    wait_for_apt
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        curl \
        wget \
        git \
        jq \
        ufw \
        net-tools \
        apt-transport-https \
        ca-certificates \
        gnupg \
        lsb-release \
        software-properties-common
}

# Install Docker
install_docker() {
    log_step "Installing Docker..."
    
    # Remove old versions
    apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true
    
    # Add Docker's official GPG key
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    
    # Add Docker repository
    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
        $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    
    # Install Docker Engine
    wait_for_apt
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    
    # Start Docker
    systemctl start docker
    systemctl enable docker
    
    # Verify installation
    docker --version
    docker compose version
    
    log_info "Docker installed successfully"
}

# Setup Docker user permissions
setup_docker_users() {
    log_step "Setting up Docker access..."

    # Create picoclaw user for managing containers via SSH key auth
    if ! id "picoclaw" &>/dev/null; then
        useradd -m -s /bin/bash picoclaw
        log_info "Created picoclaw user"
    fi
    usermod -aG docker picoclaw

    # Inject provisioning SSH public key so SshCommandExecutorImpl can connect
    if [[ -n "$PROVISIONING_SSH_PUBLIC_KEY" ]]; then
        mkdir -p /home/picoclaw/.ssh
        # Append only if not already present
        if ! grep -qF "$PROVISIONING_SSH_PUBLIC_KEY" /home/picoclaw/.ssh/authorized_keys 2>/dev/null; then
            echo "$PROVISIONING_SSH_PUBLIC_KEY" >> /home/picoclaw/.ssh/authorized_keys
        fi
        chmod 700 /home/picoclaw/.ssh
        chmod 600 /home/picoclaw/.ssh/authorized_keys
        chown -R picoclaw:picoclaw /home/picoclaw/.ssh
        log_info "SSH public key installed for picoclaw"
    else
        log_warn "PROVISIONING_SSH_PUBLIC_KEY not set — picoclaw SSH key auth will NOT work"
    fi
}

# Configure firewall
setup_firewall() {
    log_step "Configuring firewall..."
    
    # Reset UFW
    ufw --force reset
    
    # Default policies
    ufw default deny incoming
    ufw default allow outgoing
    
    # Allow SSH (important!)
    ufw allow OpenSSH
    
    # Allow HTTP/HTTPS
    ufw allow 80/tcp
    ufw allow 443/tcp
    
    # Allow Traefik dashboard (restrict to localhost in production)
    ufw allow from 127.0.0.1 to any port 8080
    
    # Enable firewall
    ufw --force enable
    
    log_info "Firewall configured"
    ufw status verbose
}

# Setup Traefik directories and config
setup_traefik() {
    log_step "Setting up Traefik..."
    
    # Create directories
    mkdir -p "$TRAEFIK_DIR/dynamic"
    mkdir -p "$TRAEFIK_DIR/certs"
    
    # Copy static config
    if [[ -f "traefik/traefik.yml" ]]; then
        cp traefik/traefik.yml "$TRAEFIK_DIR/traefik.yml"
    else
        # Create default config
        cat > "$TRAEFIK_DIR/traefik.yml" <<'EOF'
entryPoints:
  web:
    address: ":80"
    http:
      redirections:
        entryPoint:
          to: websecure
          scheme: https
          permanent: true
  
  websecure:
    address: ":443"
  
  traefik:
    address: ":8080"

api:
  insecure: true
  dashboard: true

providers:
  file:
    directory: /opt/traefik/dynamic
    watch: true
log:
  level: INFO
  format: json

accessLog:
  format: json

certificatesResolvers:
  letsencrypt:
    acme:
      email: admin@suprclaw.com
      storage: /opt/traefik/certs/acme.json
      tlsChallenge: {}
EOF
    fi
    
    # Create ACME storage file with proper permissions
    touch "$TRAEFIK_DIR/certs/acme.json"
    chmod 600 "$TRAEFIK_DIR/certs/acme.json"
    
    # Set permissions — picoclaw needs write access to add/remove route files
    chown -R picoclaw:picoclaw "$TRAEFIK_DIR"
    
    log_info "Traefik configuration created"
}

# Start Traefik container
start_traefik() {
    log_step "Starting Traefik..."
    
    # Stop existing Traefik if running
    docker stop traefik 2>/dev/null || true
    docker rm traefik 2>/dev/null || true
    
    # Run Traefik
    docker run -d \
        --name traefik \
        --restart unless-stopped \
        --network host \
        -p 80:80 \
        -p 443:443 \
        -p 8080:8080 \
        -v /var/run/docker.sock:/var/run/docker.sock:ro \
        -v "$TRAEFIK_DIR/traefik.yml:/etc/traefik/traefik.yml:ro" \
        -v "$TRAEFIK_DIR/dynamic:/opt/traefik/dynamic:ro" \
        -v "$TRAEFIK_DIR/certs:/opt/traefik/certs" \
        -e DOCKER_API_VERSION=1.47 \
        -e TZ=UTC \
        "traefik:$TRAEFIK_VERSION"
    
    # Wait for Traefik to be ready
    log_info "Waiting for Traefik to start..."
    for i in {1..30}; do
        if curl -s http://localhost:8080/ping 2>/dev/null | grep -q "OK"; then
            log_info "Traefik is ready!"
            return 0
        fi
        sleep 1
    done
    
    log_error "Traefik failed to start"
    docker logs traefik --tail 50
    return 1
}

# Install utility scripts
install_scripts() {
    log_step "Installing utility scripts..."
    
    mkdir -p "$SUPRCLAW_DIR/bin"
    
    # Container management script
    cat > "$SUPRCLAW_DIR/bin/manage-container" <<'EOF'
#!/bin/bash
# Utility script for managing PicoClaw containers on this host

COMMAND=$1
CONTAINER_ID=$2

 case $COMMAND in
    logs)
        docker logs -f "$CONTAINER_ID"
        ;;
    status)
        docker ps --filter "id=$CONTAINER_ID" --format "table {{.ID}}\t{{.Names}}\t{{.Status}}"
        ;;
    restart)
        docker restart "$CONTAINER_ID"
        ;;
    stop)
        docker stop "$CONTAINER_ID"
        ;;
    start)
        docker start "$CONTAINER_ID"
        ;;
    exec)
        shift 2
        docker exec -it "$CONTAINER_ID" "$@"
        ;;
    *)
        echo "Usage: $0 {logs|status|restart|stop|start|exec} <container_id>"
        exit 1
        ;;
esac
EOF
    chmod +x "$SUPRCLAW_DIR/bin/manage-container"
    
    # Add to PATH
    echo "export PATH=\$PATH:$SUPRCLAW_DIR/bin" > /etc/profile.d/suprclaw.sh
    
    log_info "Utility scripts installed"
}

# Setup log rotation
setup_logrotate() {
    log_step "Setting up log rotation..."
    
    cat > /etc/logrotate.d/suprclaw <<EOF
/var/log/suprclaw/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0644 picoclaw picoclaw
}
EOF

    mkdir -p /var/log/suprclaw
    chown picoclaw:picoclaw /var/log/suprclaw
    
    log_info "Log rotation configured"
}

# Print summary
print_summary() {
    local ip_address=$(hostname -I | awk '{print $1}')
    
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║          Docker Multi-Tenant Host Setup Complete!            ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    log_info "Host IP: $ip_address"
    log_info "Traefik Dashboard: http://$ip_address:8080"
    log_info "Docker Version: $(docker --version)"
    log_info ""
    log_info "Next steps:"
    log_info "  1. Pull PicoClaw image: docker pull suprclaw/picoclaw:latest"
    log_info "  2. Configure DNS to point to: $ip_address"
    log_info "  3. Start creating user containers!"
    echo ""
}

# Main execution
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║      SuprClaw Docker Multi-Tenant Host Setup                 ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    check_root
    update_system
    install_docker
    setup_docker_users
    setup_firewall
    setup_traefik
    start_traefik
    install_scripts
    setup_logrotate
    print_summary
}

# Run main function
main "$@"
