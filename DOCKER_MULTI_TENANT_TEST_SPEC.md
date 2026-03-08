# Docker Multi-Tenant Integration Test Specification

This document defines the complete integration test suite for the Docker-based multi-tenant architecture.

## Test Overview

| Phase | Test Count | Purpose | Est. Runtime |
|-------|------------|---------|--------------|
| 1 - Host VPS | 3 | Verify host provisioning with Docker | ~6 min |
| 2 - Container | 4 | Container lifecycle management | ~3 min |
| 3 - Network | 3 | Traefik routing and connectivity | ~1 min |
| 4 - Handshake | 3 | Gateway authentication & WebSocket | ~1 min |
| 5 - E2E | 2 | Full provisioning flow & cleanup | ~6 min |
| Utility | 2 | Supporting service tests | ~30 sec |
| **Total** | **17** | | **~17 min** |

---

## Phase 1: Host VPS Provisioning

### Test 1.1: Create host VPS with Docker pre-installed
**File:** `DockerMultiTenantIntegrationTest.kt:78`

**What it tests:**
- VPS creation via Hetzner API
- Server reaches "active" state
- Public IP is assigned

**Expected Results:**
```
✅ Host VPS created: ID=<server_id>
Attempt 1: status=initializing
Attempt 2: status=starting
...
Attempt 12: status=active
✅ Host VPS active at: <ip_address>
```

**Implementation Required:**
- `HetznerService.createServer()` - Already exists ✓
- `HetznerService.getServer()` - Already exists ✓
- Host image with Docker pre-installed (new snapshot)

---

### Test 1.2: Verify Docker is installed and running on host
**File:** `DockerMultiTenantIntegrationTest.kt:110`

**What it tests:**
- Docker binary is available
- Docker daemon is running
- Docker Compose is available

**Expected Results:**
```
✅ SSH ready
✅ Docker version: Docker version 24.0.x, build xxx
✅ Docker daemon running: 24.0.x
✅ Docker Compose: Docker Compose version v2.x.x
```

**Implementation Required:**
- `SshCommandExecutor` - Already exists ✓
- Custom snapshot with Docker installed (see `docker/host-setup/install-docker.sh`)

---

### Test 1.3: Verify Traefik is running on host
**File:** `DockerMultiTenantIntegrationTest.kt:137`

**What it tests:**
- Traefik container is running
- Traefik API responds to ping
- Dynamic configuration directory exists

**Expected Results:**
```
✅ Traefik running: traefik Up x minutes
✅ Traefik API responding
```

**Implementation Required:**
- `TraefikManager` - Auto-installs Traefik if missing
- Traefik configuration files in `/opt/traefik/`

---

## Phase 2: Container Lifecycle

### Test 2.1: Allocate port for new container
**File:** `DockerMultiTenantIntegrationTest.kt:166`

**What it tests:**
- Port allocator tracks used ports per host
- Ports are in configured range (18001-18050)
- No duplicate allocations

**Expected Results:**
```
✅ Port allocated: 18001
```

**Implementation Required:**
- `ContainerPortAllocator` service
- Thread-safe port tracking

---

### Test 2.2: Create OpenClaw container on host
**File:** `DockerMultiTenantIntegrationTest.kt:179`

**What it tests:**
- Container image is pulled/available
- Container starts successfully
- Port mapping is correct (container:18789 → host:1800x)
- Container reaches "running" state

**Expected Results:**
```
✅ Container created: ID=abc123def456
✅ Container status: running
```

**Implementation Required:**
- `DockerContainerService.createContainer()`
- `openclaw-container/Dockerfile`
- Container registry or local image

---

### Test 2.3: Verify container processes are running
**File:** `DockerMultiTenantIntegrationTest.kt:211`

**What it tests:**
- OpenClaw gateway process is running
- MCP auth proxy is running
- Configuration files have correct tokens

**Expected Results:**
```
✅ OpenClaw gateway process: PID=42
✅ MCP auth proxy process: PID=43
✅ OpenClaw config verified
```

**Implementation Required:**
- `openclaw-container/supervisord.conf`
- Proper process management in container

---

### Test 2.4: Verify port mapping is correct
**File:** `DockerMultiTenantIntegrationTest.kt:235`

**What it tests:**
- Docker port mapping is set correctly
- Host port matches allocation

**Expected Results:**
```
✅ Port mapping verified: 0.0.0.0:18001->18789/tcp
```

---

## Phase 3: Network Routing

### Test 3.1: Configure Traefik route for container
**File:** `DockerMultiTenantIntegrationTest.kt:247`

**What it tests:**
- Dynamic configuration file is created
- Traefik reloads configuration
- Route points to correct port

**Expected Results:**
```
✅ Traefik route configured
```

**Implementation Required:**
- `TraefikManager.addRoute()`
- Dynamic config in `/opt/traefik/dynamic/<subdomain>.yml`

---

### Test 3.2: Verify local connectivity through Traefik
**File:** `DockerMultiTenantIntegrationTest.kt:264`

**What it tests:**
- HTTP request through Traefik reaches container
- Host header routing works

**Expected Results:**
```
✅ Local connectivity: HTTP 200 (or 401/404, not 000)
```

---

### Test 3.3: Verify container health endpoint
**File:** `DockerMultiTenantIntegrationTest.kt:281`

**What it tests:**
- Direct connection to container port works
- Gateway responds to requests

**Expected Results:**
```
✅ Container health endpoint reachable
```

---

## Phase 4: SSH & Gateway Handshake

### Test 4.1: Verify OpenClaw gateway token authentication
**File:** `DockerMultiTenantIntegrationTest.kt:297`

**What it tests:**
- Gateway accepts token from config
- Status command works inside container

**Expected Results:**
```
✅ Gateway status accessible
```

---

### Test 4.2: WebSocket connection to container gateway
**File:** `DockerMultiTenantIntegrationTest.kt:313`

**What it tests:**
- WebSocket connection establishes
- Gateway sends `connect.challenge`
- Token is recognized

**Expected Results:**
```
✅ WebSocket connected
✅ Received connect.challenge (expected)
```

---

### Test 4.3: WebSocket handshake completion
**File:** `DockerMultiTenantIntegrationTest.kt:358`

**What it tests:**
- Full challenge-response handshake
- Gateway accepts connection after challenge
- Connection remains open

**Expected Results:**
```
← {"event":"connect.challenge",...}
→ Responded to challenge
← {"type":"res","id":"1","error":null}
✓ Connect response received
✅ WebSocket handshake complete
```

---

## Phase 5: End-to-End Flow

### Test 5.1: Full provisioning flow via DockerHostProvisioningService
**File:** `DockerMultiTenantIntegrationTest.kt:406`

**What it tests:**
- Complete user provisioning:
  1. Find/create host with capacity
  2. Allocate port
  3. Create container
  4. Configure Traefik
  5. Setup DNS
  6. Initialize Supabase
  7. Save to Firestore

**Expected Results:**
```
✅ Provisioning initiated: dropletId=<container_id>
Attempt 1: waiting_host (12%)
Attempt 2: creating_container (35%)
Attempt 3: configuring_traefik (55%)
Attempt 4: dns (65%)
Attempt 5: verifying (75%)
Attempt 6: complete (100%)
✅ Full provisioning complete: Provisioning complete. Connect via proxy at wss://...
```

**Implementation Required:**
- `DockerHostProvisioningService` (alternative to `DropletProvisioningService`)
- All supporting services

---

### Test 5.2: Container teardown and cleanup
**File:** `DockerMultiTenantIntegrationTest.kt:444`

**What it tests:**
- Container stops gracefully
- Traefik route is removed
- Container is deleted
- Port is released

**Expected Results:**
```
✅ Container stopped
✅ Traefik route removed
✅ Container deleted
✅ Port 18001 released
```

---

## Utility Tests

### Port allocator prevents duplicate allocation
**What it tests:**
- Same port not allocated twice to same host
- Port release makes it available again

**Implementation Required:**
- `ContainerPortAllocator.allocatePort()`
- `ContainerPortAllocator.releasePort()`

---

### HostPoolManager selects host with capacity
**What it tests:**
- Returns existing host if available
- Creates new host when all are full
- Tracks container counts per host

**Implementation Required:**
- `HostPoolManager.getOrCreateHostForNewUser()`
- Firestore persistence for host tracking

---

## Running the Tests

### Prerequisites

1. **Environment Variables:**
```bash
export HETZNER_API_TOKEN="your_hetzner_token"
export HETZNER_SSH_KEY_IDS="12345678"
export SUPABASE_MANAGEMENT_TOKEN="your_token"
export FIREBASE_CREDENTIALS_PATH="/path/to/serviceAccountKey.json"
```

2. **Host VPS Snapshot:**
Create a snapshot with Docker pre-installed:
```bash
# Run on a fresh VPS to create the base image
./docker/scripts/create-host-snapshot.sh
```

### Execute Tests

```bash
# Run all integration tests
./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest"

# Run specific phase
./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest.create host VPS*"
./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest.PHASE 2*"

# Run with cleanup disabled (for debugging)
./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest" -Dcleanup=false
```

### Test Results Format

Tests use JUnit 5 with structured output:
```
=== PHASE X.Y: Test Name ===
  Step details...
✅ Assertion passed
❌ Assertion failed (with details)
```

---

## Implementation Checklist

Track progress by marking each component as implemented:

### Services to Implement

| Service | Interface | Tests | Status |
|---------|-----------|-------|--------|
| `ContainerPortAllocator` | `allocatePort()`, `releasePort()` | 2.1, UT-1 | ⬜ |
| `DockerContainerService` | `createContainer()`, `deleteContainer()` | 2.2, 2.3, 2.4, 5.2 | ⬜ |
| `TraefikManager` | `addRoute()`, `removeRoute()` | 1.3, 3.1, 3.2, 5.2 | ⬜ |
| `HostPoolManager` | `getOrCreateHostForNewUser()` | 1.1, 5.1, UT-2 | ⬜ |
| `DockerHostProvisioningService` | `createAndProvision()`, `provisionDroplet()` | 5.1 | ⬜ |

### Docker Assets

| Asset | Purpose | Tests | Status |
|-------|---------|-------|--------|
| `Dockerfile` | OpenClaw container image | 2.2, 2.3 | ⬜ |
| `entrypoint.sh` | Container startup | 2.2 | ⬜ |
| `supervisord.conf` | Process management | 2.3 | ⬜ |
| `install-docker.sh` | Host VPS setup | 1.2 | ⬜ |
| `traefik.yml` | Reverse proxy config | 1.3, 3.1 | ⬜ |

### Data Models

| Model | Tests | Status |
|-------|-------|--------|
| `ContainerInfo` | 2.2 | ⬜ |
| `UserHostDroplet` | 5.1 | ⬜ |
| `HostInfo` | UT-2 | ⬜ |
| `ContainerProvisioningStatus` | 5.1 | ⬜ |

---

## Success Criteria

All tests must pass for the implementation to be considered complete:

- ✅ All 17 tests pass
- ✅ Phase 1: Host VPS creates with Docker in < 5 minutes
- ✅ Phase 2: Container creates and starts in < 2 minutes
- ✅ Phase 3: Traefik routes requests in < 5 seconds
- ✅ Phase 4: WebSocket handshake completes in < 10 seconds
- ✅ Phase 5: Full provisioning completes in < 5 minutes
- ✅ Cleanup removes all resources

---

## Troubleshooting

### Test 1.1 Fails (Host Creation)
- Check Hetzner API token
- Verify quota available: `curl -H "Authorization: Bearer $HETZNER_API_TOKEN" https://api.hetzner.cloud/v1/servers`
- Check rate limits

### Test 1.2 Fails (Docker Not Found)
- Ensure using correct snapshot with Docker pre-installed
- Check SSH key access to host

### Test 1.3 Fails (Traefik Not Running)
- Traefik should auto-install if missing
- Check port 80/443 not in use
- View logs: `docker logs traefik`

### Test 2.2 Fails (Container Creation)
- Verify OpenClaw container image exists: `docker images | grep openclaw`
- Check Docker daemon on host
- Review container logs: `docker logs <container_id>`

### Test 4.x Fails (WebSocket)
- Verify gateway token is set correctly in container
- Check port mapping: `docker port <container_id>`
- Test directly: `websocat ws://<host>:<port>/ws`
