#!/bin/bash

# Setup Wildcard SSL Certificate for *.suprclaw.com
# Run this script on your backend server (where the Kotlin app runs)

set -e

echo "🔐 Setting up wildcard SSL certificate for *.suprclaw.com"
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "❌ Please run as root (sudo ./setup-wildcard-ssl.sh)"
    exit 1
fi

# Install certbot with DigitalOcean plugin
echo "📦 Installing certbot and DigitalOcean DNS plugin..."
apt-get update -qq
apt-get install -y certbot python3-certbot-dns-digitalocean

# Get DigitalOcean API token from .env file
if [ -f ".env" ]; then
    export $(cat .env | grep DIGITALOCEAN_API_KEY | xargs)
else
    echo "❌ .env file not found!"
    exit 1
fi

if [ -z "$DIGITALOCEAN_API_KEY" ]; then
    echo "❌ DIGITALOCEAN_API_KEY not found in .env file!"
    exit 1
fi

# Create credentials file
echo "🔑 Creating DigitalOcean credentials file..."
mkdir -p /etc/letsencrypt
cat > /etc/letsencrypt/digitalocean.ini <<EOF
dns_digitalocean_token = $DIGITALOCEAN_API_KEY
EOF
chmod 600 /etc/letsencrypt/digitalocean.ini

# Obtain wildcard certificate
echo "📜 Obtaining wildcard SSL certificate..."
echo "   This will create DNS TXT records for verification..."
echo ""

certbot certonly \
  --dns-digitalocean \
  --dns-digitalocean-credentials /etc/letsencrypt/digitalocean.ini \
  -d "*.suprclaw.com" \
  -d "suprclaw.com" \
  --non-interactive \
  --agree-tos \
  --email admin@suprclaw.com

# Check if successful
if [ -f "/etc/letsencrypt/live/suprclaw.com/fullchain.pem" ]; then
    echo ""
    echo "✅ Wildcard SSL certificate obtained successfully!"
    echo ""
    echo "📁 Certificate files:"
    ls -lh /etc/letsencrypt/live/suprclaw.com/
    echo ""
    echo "🔄 Auto-renewal is configured via certbot.timer"
    systemctl status certbot.timer --no-pager
    echo ""
    echo "✅ Setup complete! Your backend can now deploy SSL to droplets."
    echo ""
    echo "📝 Next steps:"
    echo "   1. Restart your Kotlin backend server"
    echo "   2. Create a test droplet"
    echo "   3. It will automatically get HTTPS with the wildcard certificate"
else
    echo "❌ Failed to obtain certificate!"
    exit 1
fi
