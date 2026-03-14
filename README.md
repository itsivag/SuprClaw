# SuprClaw

> **⚠️ Work in Progress** — This project is under active development. Things will break, APIs will change, and everything moves fast. Do not rely on stability.

## About

SuprClaw is the backend service for a multi-tenant AI agent orchestration platform. It provisions PicoClaw runtimes on Hetzner-backed Docker hosts, manages per-user isolated databases via Supabase, and proxies real-time WebSocket communication between clients and user runtimes.

Each user gets their own PicoClaw runtime container on a managed host and an isolated Supabase project for storing tasks, agents, and conversation history.

## How It Works

### Architecture Overview

```
Client (Android/iOS/macOS)
        │
        ▼
  SuprClaw Backend (this repo)
  ├── Firebase Auth        → authenticates every request
  ├── Firestore            → stores internal droplet state & routing
  ├── Central Supabase     → routes user → runtime (public.user_droplets)
  └── WebSocket Proxy      → bridges messages to the user's runtime
        │
        ▼
  Hetzner Host VPS (shared)
  ├── picoclaw containers  → one container per user
  ├── native MCP           → optional runtime-managed MCP servers
  └── Per-user Supabase    → stores tasks, agents, documents
```

### Key Flows

**1. Provisioning a new user**
- Allocates capacity on a Hetzner Docker host (or creates a new host)
- Creates a dedicated PicoClaw container for the user
- In parallel: creates a per-user Supabase project
- Configures SSH, gateway token, DNS, and SSL on the host
- Writes runtime config and optional native MCP server entries to `picoclaw.json`
- Initializes user's database schema and inserts default agents
- Registers the runtime in the central Supabase routing table

**2. WebSocket proxy**
- Client connects to `/ws` with a Firebase JWT
- Backend authenticates, looks up the user's runtime from Firestore
- Opens a runtime bridge for the session
- Messages flow bidirectionally through an interceptor pipeline (auth, logging, token usage tracking)

**3. Task assignment webhooks**
- Supabase DB webhook fires on `task_assignees` INSERT
- Backend receives `POST /webhooks/tasks/{projectRef}`, looks up the user runtime, and dispatches a wake event
- The target agent session receives the task notification and begins work

**4. Marketplace agent installation**
- User selects an agent from the catalog (`/marketplace`)
- Backend validates required MCP tool env vars when native MCP is needed
- Clones the agent repo into the runtime workspace via SSH and reloads the runtime config

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
ZAPIER_MCP_EMBED_ID=your_zapier_embed_id
ZAPIER_MCP_EMBED_SECRET=your_zapier_embed_secret
CONNECTOR_PUBLIC_BASE_URL=https://api.yourdomain.com
CONNECTOR_SESSION_SIGNING_SECRET=your_connector_session_signing_secret
CONNECTOR_SESSION_TTL_SECONDS=900
# Optional post-callback redirects (if blank, callback returns JSON)
CONNECTOR_CALLBACK_SUCCESS_URL=suprclaw://connectors/success
CONNECTOR_CALLBACK_FAILURE_URL=suprclaw://connectors/failure
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
| `GET` | `/api/notifications` | List notifications from the authenticated user's schema |
| `GET` | `/api/tasks/deliverables` | List deliverables (task documents) for authenticated user |
| `GET` | `/api/tasks/{id}` | Get task details |
| `GET` | `/marketplace` | Browse agent marketplace |
| `POST` | `/marketplace/{id}` | Install a marketplace agent |
| `GET` | `/api/connectors` | List connected third-party connectors |
| `POST` | `/api/connectors/apps/session` | Start product-branded connector session and return backend-owned `connectUrl` |
| `GET` | `/api/connectors/apps/session/{sessionId}` | Get connector session status (`pending/completed/failed/cancelled/expired`) |
| `GET` | `/api/connectors/apps/connect/{sessionId}` | Backend-owned connect URL that renders the branded in-app Zapier MCP embed |
| `GET` | `/api/connectors/apps/callback` | Provider callback/finalize endpoint (also supports `POST`) |
| `PUT` | `/api/connectors/{provider}/policy` | Update connector allowlist policy |
| `DELETE` | `/api/connectors/{provider}` | Disconnect a connector |
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
