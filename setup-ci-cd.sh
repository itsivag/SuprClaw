#!/bin/bash

# Quick CI/CD Setup Script for SuprClaw Backend
# This script helps you set up GitHub Actions deployment

set -e

echo "🚀 SuprClaw CI/CD Setup"
echo "======================="
echo ""

# Step 1: Generate SSH key
echo "📝 Step 1: Generate SSH Key for GitHub Actions"
echo ""

if [ -f ~/.ssh/github_actions_deploy ]; then
    echo "⚠️  SSH key already exists at ~/.ssh/github_actions_deploy"
    read -p "Do you want to overwrite it? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Using existing key..."
    else
        ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/github_actions_deploy -N ""
        echo "✅ New SSH key generated"
    fi
else
    ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/github_actions_deploy -N ""
    echo "✅ SSH key generated"
fi

echo ""
echo "📋 Step 2: Copy SSH Public Key to Server"
echo ""
echo "Your public key:"
echo "----------------------------------------"
cat ~/.ssh/github_actions_deploy.pub
echo "----------------------------------------"
echo ""
read -p "Enter server IP (default: 178.128.186.69): " SERVER_IP
SERVER_IP=${SERVER_IP:-178.128.186.69}
read -p "Enter deploy user (default: suprclaw): " DEPLOY_USER
DEPLOY_USER=${DEPLOY_USER:-suprclaw}

echo ""
echo "Adding SSH key to server..."
ssh-copy-id -i ~/.ssh/github_actions_deploy.pub $DEPLOY_USER@$SERVER_IP

echo ""
echo "✅ SSH key added to server"
echo ""

# Step 3: Display GitHub Secrets
echo "🔐 Step 3: Configure GitHub Secrets"
echo ""
echo "Go to your GitHub repository:"
echo "  Settings → Secrets and variables → Actions → New repository secret"
echo ""
echo "Add these 3 secrets:"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Secret 1: SSH_PRIVATE_KEY"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cat ~/.ssh/github_actions_deploy
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Secret 2: SERVER_IP"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$SERVER_IP"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Secret 3: DEPLOY_USER"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$DEPLOY_USER"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Step 4: Verify SSH connection
echo "✅ Step 4: Verify SSH Connection"
echo ""
echo "Testing SSH connection..."
if ssh -i ~/.ssh/github_actions_deploy $DEPLOY_USER@$SERVER_IP "echo '✅ SSH connection successful!'" 2>/dev/null; then
    echo ""
else
    echo "❌ SSH connection failed. Please check your server access."
    exit 1
fi

# Step 5: Next steps
echo "📝 Next Steps:"
echo ""
echo "1. Add the secrets to GitHub (see above)"
echo "2. Push the CI/CD workflow to GitHub:"
echo "   git add .github/workflows/deploy.yml CI-CD-SETUP.md"
echo "   git commit -m 'Add CI/CD pipeline'"
echo "   git push origin main"
echo ""
echo "3. Create a release to deploy:"
echo "   - Go to GitHub → Releases → Draft a new release"
echo "   - Tag: v1.0.0"
echo "   - Click 'Publish release'"
echo ""
echo "4. Watch the deployment in the Actions tab!"
echo ""
echo "✅ Setup complete! See CI-CD-SETUP.md for detailed documentation."
