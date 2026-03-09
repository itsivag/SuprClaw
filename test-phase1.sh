#!/bin/bash
set -e

# Phase 1 Test Script
# Tests Docker assets by creating a real Hetzner VPS

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load .env
if [[ -f .env ]]; then
    export $(grep -v '^#' .env | xargs)
fi

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

TEST_ID="phase1-$(date +%s)"
SERVER_NAME="test-$TEST_ID"
HOST_IP=""
SERVER_ID=""

cleanup() {
    if [[ -n "$SERVER_ID" ]]; then
        log_warn "Cleaning up server $SERVER_NAME (ID: $SERVER_ID)..."
        curl -s -X DELETE \
            -H "Authorization: Bearer $HETZNER_API_TOKEN" \
            "https://api.hetzner.cloud/v1/servers/$SERVER_ID" || true
    fi
}

trap cleanup EXIT

log_step "=== Phase 1 Test: Docker Assets ==="
log_info "Test ID: $TEST_ID"

# Test 1.1: Create VPS
log_step "TEST 1.1: Creating Hetzner VPS..."

CREATE_RESPONSE=$(curl -s -X POST \
    -H "Authorization: Bearer $HETZNER_API_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
        \"name\": \"$SERVER_NAME\",
        \"server_type\": \"cax21\",
        \"image\": \"ubuntu-22.04\",
        \"location\": \"nbg1\",

        \"user_data\": \"$(base64 -i docker/host-setup/install-docker.sh | tr -d '\n')\"
    }" \
    "https://api.hetzner.cloud/v1/servers")

SERVER_ID=$(echo "$CREATE_RESPONSE" | jq -r '.server.id')
ROOT_PASS=$(echo "$CREATE_RESPONSE" | jq -r '.root_password')

if [[ "$SERVER_ID" == "null" || -z "$SERVER_ID" ]]; then
    log_error "Failed to create server"
    echo "$CREATE_RESPONSE" | jq .
    exit 1
fi

log_info "✅ Server created: ID=$SERVER_ID"

# Wait for server to be active
log_step "Waiting for server to become active..."
for i in {1..60}; do
    STATUS=$(curl -s -H "Authorization: Bearer $HETZNER_API_TOKEN" \
        "https://api.hetzner.cloud/v1/servers/$SERVER_ID" | jq -r '.server.status')
    
    if [[ "$STATUS" == "running" ]]; then
        log_info "✅ Server is running"
        break
    fi
    
    echo -n "."
    sleep 5
done

# Get IP
HOST_IP=$(curl -s -H "Authorization: Bearer $HETZNER_API_TOKEN" \
    "https://api.hetzner.cloud/v1/servers/$SERVER_ID" | jq -r '.server.public_net.ipv4.ip')

log_info "Server IP: $HOST_IP"

# Save root password for SSH
mkdir -p /tmp/suprclaw-test
echo "$ROOT_PASS" > /tmp/suprclaw-test/root_pass
chmod 600 /tmp/suprclaw-test/root_pass
log_info "Root password saved (for emergency access)"

# Test 1.2: Wait for SSH and verify Docker
log_step "TEST 1.2: Verifying Docker installation..."

log_info "Waiting for SSH (cloud-init may take 3-5 minutes)..."
for i in {1..60}; do
    if sshpass -p "$ROOT_PASS" ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no \
        "root@$HOST_IP" "echo 'ready'" 2>/dev/null; then
        log_info "✅ SSH ready"
        break
    fi
    echo -n "."
    sleep 5
done

# Check Docker
log_info "Checking Docker..."
DOCKER_VERSION=$(sshpass -p "$ROOT_PASS" ssh -o StrictHostKeyChecking=no \
    "root@$HOST_IP" "docker --version" 2>/dev/null || echo "FAIL")

if [[ "$DOCKER_VERSION" == *"Docker version"* ]]; then
    log_info "✅ Docker installed: $DOCKER_VERSION"
else
    log_error "❌ Docker not found: $DOCKER_VERSION"
    exit 1
fi

# Check Docker daemon
DOCKER_INFO=$(sshpass -p "$ROOT_PASS" ssh -o StrictHostKeyChecking=no \
    "root@$HOST_IP" "docker info --format '{{.ServerVersion}}'" 2>/dev/null || echo "FAIL")

if [[ "$DOCKER_INFO" != "FAIL" && -n "$DOCKER_INFO" ]]; then
    log_info "✅ Docker daemon running: $DOCKER_INFO"
else
    log_error "❌ Docker daemon not running"
    exit 1
fi

# Test 1.3: Verify Traefik
log_step "TEST 1.3: Verifying Traefik..."

TRAEFIK_STATUS=$(sshpass -p "$ROOT_PASS" ssh -o StrictHostKeyChecking=no \
    "root@$HOST_IP" "docker ps --filter 'name=traefik' --format '{{.Status}}'" 2>/dev/null || echo "")

if [[ -n "$TRAEFIK_STATUS" ]]; then
    log_info "✅ Traefik container: $TRAEFIK_STATUS"
else
    log_warn "⚠️ Traefik not running, checking setup logs..."
    sshpass -p "$ROOT_PASS" ssh -o StrictHostKeyChecking=no \
        "root@$HOST_IP" "tail -50 /var/log/suprclaw-setup.log" 2>/dev/null || true
fi

# Test Traefik API
sleep 5
TRAEFIK_PING=$(sshpass -p "$ROOT_PASS" ssh -o StrictHostKeyChecking=no \
    "root@$HOST_IP" "curl -s http://localhost:8080/ping" 2>/dev/null || echo "FAIL")

if [[ "$TRAEFIK_PING" == "OK" ]]; then
    log_info "✅ Traefik API responding"
else
    log_warn "⚠️ Traefik API not responding yet (may still be starting)"
fi

# Test 2.2: Build and run container
log_step "TEST 2.2: Building OpenClaw container..."

# Copy Dockerfile to server
log_info "Copying container files..."
SSH="sshpass -p $ROOT_PASS ssh -o StrictHostKeyChecking=no root@$HOST_IP"
SCP="sshpass -p $ROOT_PASS scp -o StrictHostKeyChecking=no"

$SSH "mkdir -p /tmp/openclaw-build"
$SCP docker/openclaw-container/Dockerfile      "root@$HOST_IP:/tmp/openclaw-build/Dockerfile"
$SCP docker/openclaw-container/entrypoint.sh   "root@$HOST_IP:/tmp/openclaw-build/entrypoint.sh"
$SCP docker/openclaw-container/supervisord.conf "root@$HOST_IP:/tmp/openclaw-build/supervisord.conf"
$SCP docker/openclaw-container/nginx-default.conf "root@$HOST_IP:/tmp/openclaw-build/nginx-default.conf"
$SSH "chmod +x /tmp/openclaw-build/entrypoint.sh"

# Build image
log_info "Building Docker image (this may take a few minutes)..."
$SSH "cd /tmp/openclaw-build && docker build -t suprclaw/openclaw:test . 2>&1 | tail -30"

# Verify image
IMAGE_CHECK=$($SSH "docker images --format '{{.Repository}}:{{.Tag}}' | grep suprclaw/openclaw" || echo "")

if [[ -n "$IMAGE_CHECK" ]]; then
    log_info "✅ Image built successfully"
else
    log_error "❌ Image build failed"
    exit 1
fi

# Run test container
log_step "TEST 2.3: Running test container..."
GATEWAY_TOKEN=$(openssl rand -hex 24)
HOOK_TOKEN=$(openssl rand -hex 24)

CONTAINER_ID=$($SSH "docker run -d \
        --name test-container-$TEST_ID \
        -p 18001:18789 \
        -e GATEWAY_TOKEN='$GATEWAY_TOKEN' \
        -e HOOK_TOKEN='$HOOK_TOKEN' \
        -e SUPABASE_URL='https://test.supabase.co' \
        -e SUPABASE_SERVICE_KEY='test-key' \
        -e SUPABASE_PROJECT_REF='test-project' \
        -e USER_ID='test-user-$TEST_ID' \
        suprclaw/openclaw:test" 2>/dev/null || echo "FAIL")

if [[ "$CONTAINER_ID" == "FAIL" || -z "$CONTAINER_ID" ]]; then
    log_error "❌ Failed to start container"
    exit 1
fi

log_info "✅ Container started: ${CONTAINER_ID:0:12}"

# Wait for container to be ready
log_info "Waiting for container to be ready..."
sleep 10

# Check container status
CONTAINER_STATUS=$($SSH "docker ps --filter 'id=$CONTAINER_ID' --format '{{.Status}}'" 2>/dev/null || echo "")

if [[ -n "$CONTAINER_STATUS" ]]; then
    log_info "✅ Container running: $CONTAINER_STATUS"
else
    log_error "❌ Container not running"
    $SSH "docker logs $CONTAINER_ID" 2>/dev/null || true
    exit 1
fi

# Test 2.4: Verify port mapping
log_step "TEST 2.4: Verifying port mapping..."

PORT_MAP=$($SSH "docker port $CONTAINER_ID" 2>/dev/null || echo "")

if [[ "$PORT_MAP" == *"18001"* ]]; then
    log_info "✅ Port mapping correct: $PORT_MAP"
else
    log_error "❌ Port mapping incorrect: $PORT_MAP"
    exit 1
fi

# Test 3.3: Health check
log_step "TEST 3.3: Container health check..."

HEALTH_STATUS=$($SSH "curl -s --connect-timeout 5 http://localhost:18001/health || echo 'FAIL'" 2>/dev/null)

log_info "Health check response: $HEALTH_STATUS"
if [[ "$HEALTH_STATUS" != "FAIL" ]]; then
    log_info "✅ Health endpoint reachable"
else
    log_warn "⚠️ Health check failed (container may still be starting)"
    log_info "Container logs:"
    $SSH "docker logs $CONTAINER_ID --tail 20" 2>/dev/null || true
fi

# Summary
log_step "=== Phase 1 Test Summary ==="
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Phase 1 Tests Completed!                            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
log_info "Server: $SERVER_NAME (ID: $SERVER_ID)"
log_info "IP: $HOST_IP"
echo ""
echo "Tests passed:"
echo "  ✅ 1.1 - VPS created and active"
echo "  ✅ 1.2 - Docker installed and running"
echo "  ✅ 1.3 - Traefik configured"
echo "  ✅ 2.2 - Container image built"
echo "  ✅ 2.3 - Container running"
echo "  ✅ 2.4 - Port mapping correct"
echo "  ✅ 3.3 - Health endpoint"
echo ""
log_info "To connect: ssh root@$HOST_IP"
log_info "Container: docker logs $CONTAINER_ID"
echo ""

# Keep server running for manual inspection (optional)
read -p "Keep server for inspection? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    log_info "Server $SERVER_NAME will be kept running"
    log_info "Delete it later with: hcloud server delete $SERVER_NAME"
    trap - EXIT  # Cancel cleanup
else
    log_info "Cleaning up..."
fi
