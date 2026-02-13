
#!/bin/bash
set -euo pipefail

exec > >(tee -a /var/log/user-create.log)
exec 2>&1

USERNAME="openclaw"
PASSWORD="CHANGE_ME_PASSWORD"

echo "=== Creating user: $USERNAME ==="

if id -u "$USERNAME" >/dev/null 2>&1; then
  echo "User already exists — skipping"
  exit 0
fi

# create user with home dir + bash shell
useradd -m -s /bin/bash "$USERNAME"

# set password
echo "$USERNAME:$PASSWORD" | chpasswd

# add to sudo group (optional — remove if not needed)
usermod -aG sudo "$USERNAME"

# secure home dir
chmod 700 "/home/$USERNAME"
chown -R "$USERNAME:$USERNAME" "/home/$USERNAME"

echo "✅ User created successfully"

echo "── Onboarding ──"
openclaw onboard \
    --install-daemon \
    --non-interactive \
    --auth-choice gemini-api-key \
    --gemini-api-key "{{GEMINI_API_KEY}}" \
    --gateway-port 18789 \
    --accept-risk

echo "── Setting model ──"
openclaw models set google/gemini-2.5-flash

echo "── Waiting for gateway ──"

for i in $(seq 1 30); do
    if openclaw gateway status &>/dev/null; then
        echo "✅ Gateway ready (${i}s)"
        break
    fi
    [ "$i" -eq 30 ] && { echo "❌ Gateway failed to start after 30s"; exit 1; }
    sleep 1
done

echo "── Running doctor ──"
openclaw doctor --fix --yes || true

echo "✅ OpenClaw is ready!"