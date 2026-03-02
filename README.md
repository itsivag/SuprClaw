# SuprClaw

> **⚠️ Work in Progress** — This project is under active development. Things will break, APIs will change, and everything moves fast. Do not rely on stability.

## About

SuprClaw is the backend service for a multi-tenant AI agent orchestration platform. It handles provisioning personal AI agent infrastructure on DigitalOcean, managing per-user isolated databases via Supabase, and proxying real-time WebSocket communication between clients and AI agents running on user-owned VPS instances.

Each user gets their own VPS (powered by [OpenClaw](https://github.com/anthropics/openclaw)) and an isolated Supabase project for storing tasks, agents, and conversation history.

## How It Works

### Architecture Overview

```
Client (Android/iOS/macOS)
        │
        ▼
  SuprClaw Backend (this repo)
  ├── Firebase Auth        → authenticates every request
  ├── Firestore            → stores internal droplet state & routing
  ├── Central Supabase     → routes user → VPS (public.user_droplets)
  └── WebSocket Proxy      → forwards messages to user's VPS
        │
        ▼
  DigitalOcean VPS (per user)
  ├── openclaw-gateway     → Claude API proxy
  ├── mcp-auth-proxy       → routes MCP tool calls (port 18790)
  └── Per-user Supabase    → stores tasks, agents, documents
```

### Key Flows

**1. Provisioning a new user**
- Creates a DigitalOcean droplet from a base snapshot
- In parallel: creates a per-user Supabase project
- Configures SSH, gateway token, DNS, and SSL on the VPS
- Injects MCP credentials to `/etc/suprclaw/mcp.env`
- Initializes user's database schema and inserts default agents
- Registers the VPS in the central Supabase routing table

**2. WebSocket proxy**
- Client connects to `/ws` with a Firebase JWT
- Backend authenticates, looks up the user's VPS gateway URL from Firestore
- Opens a proxied WebSocket connection to the VPS
- Messages flow bidirectionally through an interceptor pipeline (auth, logging, token usage tracking)

**3. Task assignment webhooks**
- Supabase DB webhook fires on `task_assignees` INSERT
- Backend receives `POST /webhooks/tasks/{projectRef}`, looks up user, forwards to VPS `/hooks/agent`
- Agent on VPS receives the task notification and begins work

**4. Marketplace agent installation**
- User selects an agent from the catalog (`/marketplace`)
- Backend validates required MCP tool env vars
- Clones agent repo to VPS via SSH, writes MCP routes and env, restarts proxy

## Setup

### Prerequisites

- JDK 21+
- A [DigitalOcean](https://digitalocean.com) account with API access
- A [Supabase](https://supabase.com) account (for the central project + Management API access)
- A Firebase project with Firestore enabled and a service account key
- A pre-built DigitalOcean snapshot with OpenClaw installed (base image)
- A domain name with DNS managed via DigitalOcean

### Environment Variables

Copy `.env.example` to `.env` (or set these in your environment):

```env
# DigitalOcean
DO_API_TOKEN=your_digitalocean_api_token
DO_SSH_KEY_ID=your_ssh_key_id_on_do
SNAPSHOT_ID=your_base_image_snapshot_id
DOMAIN=yourdomain.com

# Supabase (central project)
SUPABASE_URL=https://your-central-project.supabase.co
SUPABASE_KEY=your_central_anon_or_service_key
SUPABASE_SERVICE_KEY=your_central_service_role_key

# Supabase Management API
SUPABASE_MANAGEMENT_TOKEN=your_supabase_management_api_token
SUPABASE_ORG_ID=your_supabase_organization_id
SUPABASE_REGION=us-east-1

# Firebase
FIREBASE_PROJECT_ID=your_firebase_project_id
FIREBASE_CREDENTIALS_PATH=/path/to/serviceAccountKey.json

# Webhooks
WEBHOOK_BASE_URL=https://api.yourdomain.com
WEBHOOK_SECRET=your_webhook_secret

# Optional MCP tool keys
FIRECRAWL_API_KEY=your_firecrawl_api_key
```

### Running Locally

```bash
# Run the server (reads .env automatically)
./gradlew run
```

The server starts on `http://0.0.0.0:8080`.

### Building

```bash
# Build a fat JAR with all dependencies
./gradlew buildFatJar

# Run the fat JAR
java -jar build/libs/suprclaw-all.jar
```

### Docker

```bash
# Build the Docker image
./gradlew buildImage

# Run with Docker
./gradlew runDocker
```

### Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for production deployment instructions.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/droplets` | Provision a new VPS droplet |
| `GET` | `/api/droplets/my-droplet` | Get current user's droplet info |
| `DELETE` | `/api/droplets/my-droplet` | Tear down droplet and all data |
| `GET` | `/api/droplets/{id}/status` | Get provisioning status |
| `GET` | `/api/agents` | List agents on user's droplet |
| `POST` | `/api/agents/{id}` | Create a new agent |
| `DELETE` | `/api/agents/{id}/{name}` | Delete an agent |
| `GET` | `/api/tasks` | List tasks |
| `GET` | `/api/tasks/{id}` | Get task details |
| `GET` | `/marketplace` | Browse agent marketplace |
| `POST` | `/marketplace/{id}` | Install a marketplace agent |
| `WS` | `/ws` | WebSocket proxy to user's VPS |
| `GET` | `/health` | Health check |

All `/api/*` and `/marketplace` routes require Firebase authentication (`Authorization: Bearer <firebase_id_token>`).

## Tech Stack

- **Runtime**: Kotlin / Ktor 3.4.0 / JVM 21
- **Auth**: Firebase Auth + Firestore
- **Database**: Supabase (central routing) + per-user Supabase projects
- **Infrastructure**: DigitalOcean Droplets + DNS
- **VPS config**: SSH via SSHJ
- **Build**: Gradle with Shadow plugin
