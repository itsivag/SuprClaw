#!/bin/bash

# Quick Deployment Script for SuprClaw Backend
# Usage: ./deploy.sh <droplet-ip>

set -e

if [ -z "$1" ]; then
    echo "Usage: ./deploy.sh <droplet-ip>"
    echo "Example: ./deploy.sh 143.198.123.45"
    exit 1
fi

DROPLET_IP=$1
DEPLOY_USER="suprclaw"

echo "🚀 Deploying SuprClaw Backend to $DROPLET_IP"
echo ""

# Build the application locally
echo "📦 Building application..."
./gradlew clean build

# Create deployment package
echo "📦 Creating deployment package..."
tar -czf build/suprclaw-deploy.tar.gz \
    build/libs/*.jar \
    .env \
    setup-wildcard-ssl.sh \
    src/main/resources/

# Upload to droplet
echo "📤 Uploading to droplet..."
scp build/suprclaw-deploy.tar.gz $DEPLOY_USER@$DROPLET_IP:~/

# Setup and run on droplet
echo "⚙️  Setting up on droplet..."
ssh $DEPLOY_USER@$DROPLET_IP << 'ENDSSH'
    # Extract deployment
    cd ~
    mkdir -p SuprClaw
    tar -xzf suprclaw-deploy.tar.gz -C SuprClaw/
    cd SuprClaw

    # Create logs directory
    mkdir -p logs

    # Restart service if it exists
    if sudo systemctl is-active --quiet suprclaw-backend; then
        echo "🔄 Restarting backend service..."
        sudo systemctl restart suprclaw-backend
    else
        echo "ℹ️  Service not configured yet. See DEPLOYMENT.md for setup."
    fi

    echo "✅ Deployment complete!"
    echo ""
    echo "📊 Service status:"
    sudo systemctl status suprclaw-backend --no-pager || true
ENDSSH

echo ""
echo "✅ Deployment successful!"
echo ""
echo "📝 Next steps:"
echo "   1. Update Android app ClawConfig.DROPLET_API_URL = \"http://$DROPLET_IP:8080\""
echo "   2. Setup SSL: ssh $DEPLOY_USER@$DROPLET_IP 'cd SuprClaw && sudo ./setup-wildcard-ssl.sh'"
echo "   3. Test API: curl http://$DROPLET_IP:8080/"
