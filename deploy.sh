#!/usr/bin/env bash
set -euo pipefail

echo "Manual deploy.sh is deprecated for production."
echo "Use the GitHub Actions workflow at .github/workflows/deploy.yml."
echo
echo "If you need a host, provision it with:"
echo "  deploy/hetzner/create-backend-vps.sh suprclaw-backend cpx32"
