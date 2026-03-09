# Security Audit Report

## Executive Summary

This review covered the Kotlin/Ktor backend, the Node-based MCP auth proxy, and the infrastructure provisioning code in this repository. I found one critical host-breakout path in the current Docker multi-tenant deployment model, plus several high-severity issues around entitlement enforcement, SSH trust, secret handling, and information disclosure.

The most urgent issue is that agent-management actions are still implemented as host-level SSH shell commands even though the application is wired to run in shared Docker-host mode. In practice, that gives authenticated users a path to execute commands on the shared host rather than inside their own container.

No Kotlin-specific reference docs were available in the local `security-best-practices` skill, so the findings below are based on direct code review and general backend security best practices.

## Scope

- Kotlin/Ktor application code under `src/main/kotlin`
- Runtime config and provisioning scripts under `src/main/resources` and `docker/`
- Node MCP auth proxy under `src/main/resources/mcp-auth-proxy.js`

## Method

- Static review only
- No dynamic testing or live exploitation
- No code changes beyond this report

## Critical Findings

### SC-001: Agent management can execute host-level shell commands in Docker multi-tenant mode

Impact: An authenticated user can reach shell-execution paths that operate on the shared host instead of their own container, which can lead to cross-tenant compromise and likely host compromise.

Evidence:

- The app is explicitly wired to Docker multi-tenant provisioning in [src/main/kotlin/Application.kt:231](src/main/kotlin/Application.kt#L231) through [src/main/kotlin/Application.kt:299](src/main/kotlin/Application.kt#L299).
- The same `DropletConfigurationServiceImpl` is used for agent management in that mode at [src/main/kotlin/Application.kt:290](src/main/kotlin/Application.kt#L290).
- `createAgent()` builds a shell command and runs it over SSH against `userDroplet.ipAddress`, using host filesystem paths like `/home/openclaw/.openclaw/...`, at [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:35](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L35) through [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:41](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L41).
- `deleteAgent()` likewise executes `openclaw agents delete` and `rm -rf` on the SSH target at [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:127](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L127) through [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:141](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L141).
- In Docker mode, the stored `ipAddress` is the shared host IP, not a per-user VM.

Why this is exploitable:

- The request model allows a caller-controlled `model` field at [src/main/kotlin/com/suprbeta/digitalocean/models/CreateAgentRequest.kt:6](src/main/kotlin/com/suprbeta/digitalocean/models/CreateAgentRequest.kt#L6) through [src/main/kotlin/com/suprbeta/digitalocean/models/CreateAgentRequest.kt:9](src/main/kotlin/com/suprbeta/digitalocean/models/CreateAgentRequest.kt#L9).
- That `model` value is concatenated directly into the shell command at [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:38](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L38) through [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:39](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L39).
- The marketplace install path is even broader: untrusted `repo` and `sourcePath` values are interpolated into host shell commands at [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:85](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L85) through [src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt:94](src/main/kotlin/com/suprbeta/digitalocean/DropletConfigurationService.kt#L94), while the catalog is fetched live from GitHub at [src/main/kotlin/com/suprbeta/marketplace/MarketplaceService.kt:13](src/main/kotlin/com/suprbeta/marketplace/MarketplaceService.kt#L13) through [src/main/kotlin/com/suprbeta/marketplace/MarketplaceService.kt:41](src/main/kotlin/com/suprbeta/marketplace/MarketplaceService.kt#L41).

Recommended remediation:

- Split agent management into separate implementations for dedicated-VM mode and Docker-container mode.
- In Docker mode, target the user container explicitly with `docker exec <containerId> ...` or an internal control API.
- Remove string-built shell execution for user-influenced inputs; use strict allowlists and argument-safe command construction.
- Treat marketplace metadata as untrusted input. Pin the catalog source and validate `repo`, `source_path`, and `install_path` against an allowlist.

## High Findings

### SC-002: WebSocket quota enforcement trusts a client-controlled tier header

Impact: Any authenticated client can self-upgrade to a higher quota tier by sending `X-User-Tier: max` during WebSocket connection setup.

Evidence:

- The WebSocket route reads the tier from an untrusted request header at [src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt:41](src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt#L41) through [src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt:56](src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt#L56).
- The rate limiter enforces weekly limits from that stored tier at [src/main/kotlin/com/suprbeta/websocket/pipeline/RateLimitInterceptor.kt:28](src/main/kotlin/com/suprbeta/websocket/pipeline/RateLimitInterceptor.kt#L28) through [src/main/kotlin/com/suprbeta/websocket/pipeline/RateLimitInterceptor.kt:32](src/main/kotlin/com/suprbeta/websocket/pipeline/RateLimitInterceptor.kt#L32).
- By contrast, the HTTP usage routes already derive tier from verified Firebase custom claims at [src/main/kotlin/com/suprbeta/usage/UsageRoutes.kt:83](src/main/kotlin/com/suprbeta/usage/UsageRoutes.kt#L83) through [src/main/kotlin/com/suprbeta/usage/UsageRoutes.kt:85](src/main/kotlin/com/suprbeta/usage/UsageRoutes.kt#L85).

Recommended remediation:

- Ignore `X-User-Tier` entirely.
- Populate `userTier` from verified Firebase claims on the server.
- Consider revalidating claims periodically for long-lived sessions if tier changes must take effect quickly.

### SC-003: SSH host key verification is disabled for provisioning and Supabase administration

Impact: A network attacker who can intercept SSH traffic can impersonate hosts, steal the provisioning key, and receive arbitrary commands intended for your infrastructure.

Evidence:

- The main SSH executor uses `PromiscuousVerifier()` in both auth-probe and command paths at [src/main/kotlin/com/suprbeta/core/SshCommandExecutorImpl.kt:77](src/main/kotlin/com/suprbeta/core/SshCommandExecutorImpl.kt#L77) and [src/main/kotlin/com/suprbeta/core/SshCommandExecutorImpl.kt:120](src/main/kotlin/com/suprbeta/core/SshCommandExecutorImpl.kt#L120).
- The self-hosted Supabase management SSH path does the same at [src/main/kotlin/com/suprbeta/supabase/SelfHostedSupabaseManagementService.kt:417](src/main/kotlin/com/suprbeta/supabase/SelfHostedSupabaseManagementService.kt#L417).

Recommended remediation:

- Pin host keys or trusted fingerprints for VPS hosts and the self-hosted Supabase SSH target.
- Fail closed on host key mismatch.
- Rotate the provisioning SSH key if this has ever been used on an untrusted network.

### SC-004: Secret encryption fails open and can silently store plaintext credentials

Impact: If `FIRESTORE_ENCRYPTION_KEYSET` is missing or encryption fails at runtime, gateway tokens, hook tokens, SSH material, and Supabase service keys are stored in Firestore as plaintext.

Evidence:

- `CryptoService` disables encryption when the keyset is absent or invalid at [src/main/kotlin/com/suprbeta/core/CryptoService.kt:31](src/main/kotlin/com/suprbeta/core/CryptoService.kt#L31) through [src/main/kotlin/com/suprbeta/core/CryptoService.kt:45](src/main/kotlin/com/suprbeta/core/CryptoService.kt#L45).
- `encrypt()` returns plaintext when crypto is unavailable or when encryption fails at [src/main/kotlin/com/suprbeta/core/CryptoService.kt:53](src/main/kotlin/com/suprbeta/core/CryptoService.kt#L53) through [src/main/kotlin/com/suprbeta/core/CryptoService.kt:67](src/main/kotlin/com/suprbeta/core/CryptoService.kt#L67).
- Those code paths protect `gatewayToken`, `hookToken`, `sshKey`, and `supabaseServiceKey` in Firestore at [src/main/kotlin/com/suprbeta/firebase/FirestoreRepository.kt:196](src/main/kotlin/com/suprbeta/firebase/FirestoreRepository.kt#L196) through [src/main/kotlin/com/suprbeta/firebase/FirestoreRepository.kt:201](src/main/kotlin/com/suprbeta/firebase/FirestoreRepository.kt#L201).

Recommended remediation:

- Fail application startup if the encryption key is absent or invalid in environments that persist secrets.
- Fail writes when encryption cannot be performed.
- Add a startup audit/migration that detects and re-encrypts legacy plaintext records.

### SC-005: Gateway and hook tokens are generated with non-cryptographic randomness, and one path logs them

Impact: Authentication secrets are weaker than intended and may also be exposed in logs.

Evidence:

- Dedicated-VM token generation uses `chars.random()` at [src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt:703](src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt#L703) through [src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt:707](src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt#L707).
- The same path logs the full gateway token at [src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt:192](src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt#L192).
- Docker-mode token generation also uses `chars.random()` at [src/main/kotlin/com/suprbeta/docker/DockerHostProvisioningService.kt:764](src/main/kotlin/com/suprbeta/docker/DockerHostProvisioningService.kt#L764) through [src/main/kotlin/com/suprbeta/docker/DockerHostProvisioningService.kt:766](src/main/kotlin/com/suprbeta/docker/DockerHostProvisioningService.kt#L766).

Recommended remediation:

- Generate tokens with `SecureRandom`.
- Encode as hex or base64url from raw random bytes.
- Never log bearer tokens, even at debug level.

## Medium Findings

### SC-006: Droplet status endpoint allows authenticated users to read other users' provisioning state and stack traces

Impact: Authenticated users can enumerate droplet IDs and retrieve provisioning details, including internal error traces for failures.

Evidence:

- `GET /api/droplets/{id}/status` checks authentication but does not verify ownership at [src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt:129](src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt#L129) through [src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt:149](src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt#L149).
- `ProvisioningStatus` includes an `error` field at [src/main/kotlin/com/suprbeta/digitalocean/models/ProvisioningStatus.kt:11](src/main/kotlin/com/suprbeta/digitalocean/models/ProvisioningStatus.kt#L11) through [src/main/kotlin/com/suprbeta/digitalocean/models/ProvisioningStatus.kt:14](src/main/kotlin/com/suprbeta/digitalocean/models/ProvisioningStatus.kt#L14).
- Failures store full stack traces in that field at [src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt:330](src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt#L330) through [src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt:339](src/main/kotlin/com/suprbeta/digitalocean/DropletProvisioningService.kt#L339).

Recommended remediation:

- Scope status lookups to the authenticated user's own droplet only.
- Do not return stack traces to clients.
- Keep internal failure detail in server logs or an admin-only diagnostics channel.

### SC-007: `/ws/health` is unauthenticated and leaks live user/session metadata

Impact: Anyone who can reach the service can enumerate active user IDs, session IDs, queue sizes, and connection timing.

Evidence:

- The route is public and returns per-session details at [src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt:90](src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt#L90) through [src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt:114](src/main/kotlin/com/suprbeta/websocket/WebSocketRoutes.kt#L114).

Recommended remediation:

- Remove the endpoint from production, or protect it with admin authentication and IP restrictions.
- Return only coarse readiness data if a public health endpoint is required.

## Additional Concerns

These are lower-confidence or deployment-dependent concerns that still deserve attention:

- Dedicated-VM bootstrap enables SSH password authentication at [src/main/resources/scripts/user.yaml:2](src/main/resources/scripts/user.yaml#L2) through [src/main/resources/scripts/user.yaml:7](src/main/resources/scripts/user.yaml#L7), and I did not find code that later disables password auth.
- `POST /api/droplets` starts provisioning immediately at [src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt:27](src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt#L27) through [src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt:44](src/main/kotlin/com/suprbeta/digitalocean/DropletRoutes.kt#L44) with no visible per-user guard against duplicate or repeated provisioning requests, which could become a cost-amplification or abuse vector.
- Push notification failures log part of the FCM token at [src/main/kotlin/com/suprbeta/firebase/FcmNotificationService.kt:33](src/main/kotlin/com/suprbeta/firebase/FcmNotificationService.kt#L33) through [src/main/kotlin/com/suprbeta/firebase/FcmNotificationService.kt:35](src/main/kotlin/com/suprbeta/firebase/FcmNotificationService.kt#L35).

## Recommended Priority Order

1. Fix SC-001 before any further production exposure of the Docker multi-tenant path.
2. Fix SC-002 and SC-003 next, because both directly affect trust boundaries.
3. Fix SC-004 and SC-005 to harden secret handling.
4. Fix SC-006 and SC-007 to reduce cross-user information leakage.

