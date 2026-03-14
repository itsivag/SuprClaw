# SuprClaw Marketplace Workspace

Marketplace agents must remain self-contained inside their assigned workspace.

Rules:
- Keep install paths stable under `/home/picoclaw/.picoclaw/` or `/home/picoclaw/<marketplace path>`.
- Preserve `AGENTS.md`, `SOUL.md`, `USER.md`, `IDENTITY.md`, `HEARTBEAT.md`, `memory/`, and `skills/`.
- Do not assume `TOOLS.md` exists; if it does, treat it as legacy compatibility only.
- Keep role-specific instructions inside workspace files or local skills, not external MCP wiring.
