# Docker Runtime

SuprClaw now ships a PicoClaw-only runtime image.

## Layout

- `picoclaw-container/`: tenant runtime image and workspace template
- `host-setup/`: shared-host setup scripts, Traefik templates, and helper install flow

## Build

```bash
cd docker/picoclaw-container
docker build -t suprclaw/picoclaw:latest .
```

## Runtime Defaults

- Gateway port: `18790`
- Runtime user: `picoclaw`
- Runtime home: `/home/picoclaw/.picoclaw`
- Workspace root: `/home/picoclaw/.picoclaw/workspace`

## Notes

- Workspace guidance is skill-first. `TOOLS.md` remains only as a temporary compatibility mirror.
- Native MCP, when enabled, is written into `picoclaw.json` and managed directly by PicoClaw.
