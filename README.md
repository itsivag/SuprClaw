# SuprClaw

> **⚠️ Work in Progress** — This project is under active development. Things will break, APIs will change, and everything moves fast. Do not rely on stability.

## About

SuprClaw is the backend service for a multi-tenant AI agent orchestration platform. It handles provisioning personal AI agent infrastructure on Hetzner-backed Docker hosts, managing per-user isolated databases via Supabase, and proxying real-time WebSocket communication between clients and AI agents running on user-owned VPS instances.

Each user gets their own OpenClaw container (running on a managed host VPS) and an isolated Supabase project for storing tasks, agents, and conversation history.

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
  Hetzner Host VPS (shared)
  ├── openclaw containers  → one container per user
  ├── mcp-auth-proxy       → routes MCP tool calls (port 18790)
  └── Per-user Supabase    → stores tasks, agents, documents
```

### Key Flows

**1. Provisioning a new user**
- Allocates capacity on a Hetzner Docker host (or creates a new host)
- Creates a dedicated OpenClaw container for the user
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
- A [Hetzner Cloud](https://www.hetzner.com/cloud) account with API access
- A [Supabase](https://supabase.com) account (for the central project + Management API access)
- A Firebase project with Firestore enabled and a service account key
- A domain name with DNS managed via Hetzner

### Environment Variables

Copy `.env.example` to `.env` (or set these in your environment):

```env
# Hetzner Cloud
HETZNER_API_TOKEN=your_hetzner_api_token
VPS_PROVIDER=hetzner
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

# Firebase Web SDK (Admin Page)
FIREBASE_WEB_API_KEY=your_firebase_web_api_key
FIREBASE_WEB_APP_ID=your_firebase_web_app_id
# Optional: defaults to ${FIREBASE_PROJECT_ID}.firebaseapp.com when omitted
FIREBASE_WEB_AUTH_DOMAIN=your-project.firebaseapp.com
# Optional
FIREBASE_WEB_MESSAGING_SENDER_ID=your_sender_id
FIREBASE_WEB_STORAGE_BUCKET=your-project.appspot.com
FIREBASE_WEB_MEASUREMENT_ID=G-XXXXXXXXXX

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
| `GET` | `/api/tasks/deliverables` | List deliverables (task documents) for authenticated user |
| `GET` | `/api/tasks/{id}` | Get task details |
| `GET` | `/marketplace` | Browse agent marketplace |
| `POST` | `/marketplace/{id}` | Install a marketplace agent |
| `GET` | `/admin` | Admin webpage (Firebase Web login) |
| `GET` | `/api/admin/config` | Firebase Web config for admin page bootstrap |
| `GET` | `/api/admin/users?scope=provisioned|all` | Admin list of users, containers, and weekly usage |
| `GET` | `/api/admin/metrics` | Admin live host/container resource metrics (CPU, memory, network) |
| `DELETE` | `/api/admin/users/{userId}` | Admin teardown of a user's provisioned container |
| `WS` | `/ws` | WebSocket proxy to user's VPS |
| `GET` | `/health` | Health check |

All `/api/*` and `/marketplace` routes require Firebase authentication (`Authorization: Bearer <firebase_id_token>`).
Admin APIs under `/api/admin/*` additionally require Firebase custom claim `role=admin`.

## Tech Stack

- **Runtime**: Kotlin / Ktor 3.4.0 / JVM 21
- **Auth**: Firebase Auth + Firestore
- **Database**: Supabase (central routing) + per-user Supabase projects
- **Infrastructure**: Hetzner Cloud hosts + Docker containers + DNS
- **VPS config**: SSH via SSHJ
- **Build**: Gradle with Shadow plugin
