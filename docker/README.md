# Docker Multi-Tenant Assets

This directory contains all Docker-related assets for the SuprClaw multi-tenant architecture.

## 📁 Directory Structure

```
docker/
├── openclaw-container/          # User container image
│   ├── Dockerfile              # Container definition
│   ├── entrypoint.sh           # Runtime configuration
│   ├── supervisord.conf        # Process management
│   └── nginx-default.conf      # Nginx config (optional)
│
├── host-setup/                  # Host VPS configuration
│   ├── install-docker.sh       # One-time host setup
│   └── traefik/
│       ├── traefik.yml         # Static Traefik config
│       └── dynamic/
│           └── user-template.yml # Template for user routes
│
└── scripts/
    └── create-host-vps.sh      # Automated host creation
```

## 🚀 Quick Start

### 1. Create a Host VPS

```bash
# Set your Hetzner credentials
export HETZNER_API_TOKEN="your_token"
export HETZNER_SSH_KEY_IDS="12345678"

# Create a host VPS
./docker/scripts/create-host-vps.sh my-host-1 cpx31
```

This will:
- Create a Hetzner VPS with Docker pre-installed
- Install and configure Traefik reverse proxy
- Set up firewall rules
- Return the server IP and details

### 2. Build the OpenClaw Container Image

```bash
# On the host VPS (or your local machine for testing)
cd docker/openclaw-container
docker build -t suprclaw/openclaw:latest .
```

### 3. Run a Test Container

```bash
# On the host VPS
docker run -d \
  --name test-container \
  -p 18001:18789 \
  -e GATEWAY_TOKEN="abc123..." \
  -e HOOK_TOKEN="def456..." \
  -e SUPABASE_URL="https://..." \
  -e SUPABASE_SERVICE_KEY="..." \
  -e SUPABASE_PROJECT_REF="..." \
  -e USER_ID="test-user-123" \
  suprclaw/openclaw:latest
```

## 🐳 OpenClaw Container

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GATEWAY_TOKEN` | ✅ | OpenClaw gateway authentication token |
| `HOOK_TOKEN` | ✅ | Webhook token for task notifications |
| `SUPABASE_URL` | ✅ | User's Supabase project URL |
| `SUPABASE_SERVICE_KEY` | ✅ | User's Supabase service key |
| `SUPABASE_PROJECT_REF` | ✅ | User's Supabase project reference |
| `USER_ID` | ✅ | The user ID this container belongs to |
| `MCP_CONFIG_JSON` | ⬜ | JSON string with MCP tool configurations |
| `WEBHOOK_BASE_URL` | ⬜ | Base URL for webhooks |

### Ports

| Port | Description |
|------|-------------|
| `18789` | OpenClaw gateway (WebSocket + HTTP) |

### Processes Managed by Supervisor

1. **openclaw-gateway** - Main OpenClay gateway service
2. **mcp-auth-proxy** - MCP tool authentication proxy
3. **mcporter** - MCP tool process manager

### Health Checks

The container includes a health check that verifies the gateway is responding:

```bash
curl http://localhost:18789/health
```

## 🖥️ Host VPS

### Specifications

Recommended specs for host VPS:

| Type | vCPU | RAM | Max Containers | Est. Cost/Month |
|------|------|-----|----------------|-----------------|
| CX22 | 2 | 4 GB | 5-8 | €5.35 |
| CPX31 | 4 | 8 GB | 15-20 | €14.76 |
| CPX41 | 8 | 16 GB | 30-40 | €29.44 |

### Installed Services

- **Docker Engine** - Container runtime
- **Docker Compose** - Multi-container orchestration
- **Traefik** - Reverse proxy and load balancer
- **UFW** - Firewall
- **Fail2Ban** - Intrusion prevention

### Directory Structure on Host

```
/opt/suprclaw/           # SuprClaw data and scripts
/opt/traefik/            # Traefik configuration
├── traefik.yml          # Static config
├── dynamic/             # Dynamic configs (per-user)
│   ├── user1.yml
│   ├── user2.yml
│   └── ...
└── certs/               # SSL certificates
/var/log/suprclaw/       # Log files
```

### Traefik Routing

Each user container gets a dynamic configuration file:

```yaml
# /opt/traefik/dynamic/user1.suprclaw.com.yml
http:
  routers:
    user1-suprclaw-com-router:
      rule: "Host(`user1.suprclaw.com`)"
      service: user1-suprclaw-com-service
      entryPoints:
        - websecure
      tls: {}
  
  services:
    user1-suprclaw-com-service:
      loadBalancer:
        servers:
          - url: "http://127.0.0.1:18001"
```

Traefik watches this directory and auto-reloads when files change.

## 🔒 Security

### Container Isolation

- Each container runs as a separate Linux user (`openclaw`)
- No access to Docker socket from within containers
- Network isolation between containers
- Resource limits can be applied (CPU, memory)

### Network Security

- Only ports 80, 443, and 22 (SSH) are exposed publicly
- Container ports are bound to localhost only
- Traefik handles SSL termination
- UFW firewall blocks all other ports

### Secrets Management

- Gateway tokens passed as environment variables
- MCP credentials mounted as files
- All sensitive files have restricted permissions (600)

## 🔧 Manual Host Setup

If you prefer to set up a host manually instead of using the script:

```bash
# 1. Create an Ubuntu 22.04 VPS

# 2. SSH into the server
ssh root@<server-ip>

# 3. Run the setup script
curl -fsSL https://raw.githubusercontent.com/your-repo/main/docker/host-setup/install-docker.sh | bash

# Or copy and run locally:
# scp docker/host-setup/install-docker.sh root@<server-ip>:/tmp/
# ssh root@<server-ip> "bash /tmp/install-docker.sh"
```

## 🧪 Testing

### Test Container Locally

```bash
# Build image
docker build -t suprclaw/openclaw:test docker/openclaw-container/

# Run with test environment
docker run -it --rm \
  -p 18789:18789 \
  -e GATEWAY_TOKEN="$(openssl rand -hex 24)" \
  -e HOOK_TOKEN="$(openssl rand -hex 24)" \
  -e SUPABASE_URL="https://test.supabase.co" \
  -e SUPABASE_SERVICE_KEY="test-key" \
  -e SUPABASE_PROJECT_REF="test-project" \
  -e USER_ID="test-local" \
  suprclaw/openclaw:test

# In another terminal, test:
curl http://localhost:18789/health
websocat ws://localhost:18789/ws
```

### Test on Host VPS

```bash
# SSH to host
ssh root@<host-ip>

# Check Traefik is running
curl http://localhost:8080/ping

# List running containers
docker ps

# Check container logs
docker logs <container-id>
```

## 📊 Monitoring

### Container Metrics

```bash
# Container stats
docker stats

# Specific container
docker stats <container-id>

# Logs
docker logs -f <container-id>
```

### Host Metrics

```bash
# System resources
htop
free -h
df -h

# Docker system
docker system df
docker info
```

### Traefik Dashboard

Access the Traefik dashboard at: `http://<host-ip>:8080`

## 🛠️ Troubleshooting

### Container Won't Start

```bash
# Check logs
docker logs <container-id>

# Check supervisor status
docker exec <container-id> supervisorctl status

# Check OpenClaw config
docker exec <container-id> cat /home/openclaw/.openclaw/openclaw.json
```

### Traefik Not Routing

```bash
# Check Traefik logs
docker logs traefik

# Verify dynamic config
ls -la /opt/traefik/dynamic/
cat /opt/traefik/dynamic/<subdomain>.yml

# Test route directly
curl -H "Host: user1.suprclaw.com" http://localhost/
```

### SSL Issues

```bash
# Check certificate
openssl s_client -connect user1.suprclaw.com:443 -servername user1.suprclaw.com

# Verify Traefik has certs
ls -la /opt/traefik/certs/
```

## 📝 Notes

- **Port Range**: Containers use ports 18001-18050 by default (configurable)
- **Image Updates**: Rebuild and push new image versions as needed
- **Backup**: Consider backing up `/opt/traefik/dynamic/` for route recovery
- **Scaling**: Add more host VPSs when existing hosts reach capacity

## 🤝 Contributing

When modifying these assets:
1. Test locally first with `docker build`
2. Test on a staging host VPS
3. Update this README with any changes
4. Ensure backward compatibility when possible
