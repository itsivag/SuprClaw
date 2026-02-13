#!/bin/bash
set -e

# Log setup
exec > >(tee -a /var/log/openclaw-setup.log)
exec 2>&1

echo "=== OpenClaw Setup Started at $(date) ==="
echo "Bot Name: {{BOT_NAME}}"
echo "AI Provider: {{AI_PROVIDER}}"
echo "AI Model: {{AI_MODEL}}"

# Wait for system to be fully ready
sleep 10

# Set environment variables for OpenAI
export OPENAI_API_KEY="{{OPENAI_API_KEY}}"
export AI_PROVIDER="{{AI_PROVIDER}}"
export AI_MODEL="{{AI_MODEL}}"

echo "export OPENAI_API_KEY=\"{{OPENAI_API_KEY}}\"" >> /root/.bashrc
echo "export AI_PROVIDER=\"{{AI_PROVIDER}}\"" >> /root/.bashrc
echo "export AI_MODEL=\"{{AI_MODEL}}\"" >> /root/.bashrc

echo "OPENAI_API_KEY=\"{{OPENAI_API_KEY}}\"" >> /etc/environment
echo "AI_PROVIDER=\"{{AI_PROVIDER}}\"" >> /etc/environment
echo "AI_MODEL=\"{{AI_MODEL}}\"" >> /etc/environment

# Create OpenClaw config directory and workspace
mkdir -p /root/.openclaw
mkdir -p /root/.openclaw/workspace

# Create proper OpenClaw config file (JSON5 format)
cat > /root/.openclaw/openclaw.json <<'CONFIGEOF'
{
  env: {
    OPENAI_API_KEY: "{{OPENAI_API_KEY}}"
  },
  agents: {
    defaults: {
      model: {
        primary: "{{AI_PROVIDER}}/{{AI_MODEL}}"
      },
      workspace: "~/.openclaw/workspace"
    }
  },
  gateway: {
    mode: "local",
    port: 18789,
    bind: "loopback"
  }
}
CONFIGEOF

echo "Created OpenClaw config at /root/.openclaw/openclaw.json"
cat /root/.openclaw/openclaw.json

# Run OpenClaw onboarding (non-interactive)
echo "Running OpenClaw onboarding..."
openclaw onboard --install-daemon --provider {{AI_PROVIDER}} || echo "Onboarding completed with warnings"

# Wait a bit for onboarding to complete
sleep 5

# Run doctor to check for issues
echo "Running OpenClaw doctor..."
openclaw doctor --fix || true

# Start OpenClaw gateway as daemon
echo "Starting OpenClaw gateway..."

# Try using OpenClaw's restart command first (uses daemon)
openclaw restart || {
    echo "Restart failed, starting manually..."
    nohup openclaw gateway --port 18789 > /var/log/openclaw-gateway.log 2>&1 &
    echo $! > /var/run/openclaw-gateway.pid
}

# Wait for gateway to start
sleep 10

# Verify gateway started
if lsof -nP -iTCP:18789 -sTCP:LISTEN > /dev/null 2>&1; then
    echo "✅ Gateway started successfully on port 18789"
    ps aux | grep openclaw | grep -v grep
else
    echo "❌ Gateway failed to start! Check logs:"
    tail -50 /var/log/openclaw-gateway.log

    # Try one more time with verbose output
    echo "Attempting to start with verbose output..."
    openclaw gateway --port 18789 --verbose
fi

echo "=== OpenClaw Setup Completed at $(date) ==="
echo "Bot '{{BOT_NAME}}' is ready with {{AI_PROVIDER}} ({{AI_MODEL}})!"
echo "Access at: http://$(curl -s http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address):18789"
