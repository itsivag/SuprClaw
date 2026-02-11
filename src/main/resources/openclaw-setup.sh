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

# Set environment variables for Google Gemini
export GOOGLE_API_KEY="{{GOOGLE_API_KEY}}"
export AI_PROVIDER="{{AI_PROVIDER}}"
export AI_MODEL="{{AI_MODEL}}"

echo "export GOOGLE_API_KEY=\"{{GOOGLE_API_KEY}}\"" >> /root/.bashrc
echo "export AI_PROVIDER=\"{{AI_PROVIDER}}\"" >> /root/.bashrc
echo "export AI_MODEL=\"{{AI_MODEL}}\"" >> /root/.bashrc

echo "GOOGLE_API_KEY=\"{{GOOGLE_API_KEY}}\"" >> /etc/environment
echo "AI_PROVIDER=\"{{AI_PROVIDER}}\"" >> /etc/environment
echo "AI_MODEL=\"{{AI_MODEL}}\"" >> /etc/environment

# Create OpenClaw config directory
mkdir -p /root/.openclaw

# Create OpenClaw config file with Gemini settings
cat > /root/.openclaw/config.json <<EOF
{
  "ai": {
    "provider": "{{AI_PROVIDER}}",
    "model": "{{AI_MODEL}}",
    "apiKey": "{{GOOGLE_API_KEY}}"
  },
  "gateway": {
    "port": 18789
  }
}
EOF

# Run OpenClaw onboarding (non-interactive)
echo "Running OpenClaw onboarding..."
openclaw onboard --install-daemon --provider {{AI_PROVIDER}} || echo "Onboarding completed with warnings"

# Start OpenClaw gateway
echo "Starting OpenClaw gateway..."
nohup openclaw gateway --port 18789 > /var/log/openclaw-gateway.log 2>&1 &

echo "=== OpenClaw Setup Completed at $(date) ==="
echo "Bot '{{BOT_NAME}}' is ready with {{AI_PROVIDER}} ({{AI_MODEL}})!"
echo "Access at: http://$(curl -s http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address):18789"
