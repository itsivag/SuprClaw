# CI/CD Setup with GitHub Actions

This repository uses GitHub Actions to automatically deploy the backend to production when you create a release.

## Setup Instructions

### 1. Generate SSH Key for GitHub Actions

On your local machine, generate a dedicated SSH key for CI/CD:

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/github_actions_deploy
```

When prompted for a passphrase, **press Enter** (leave it empty).

### 2. Add SSH Public Key to Server

Copy the public key to your server:

```bash
ssh-copy-id -i ~/.ssh/github_actions_deploy.pub suprclaw@178.128.186.69
```

Or manually:

```bash
# Copy the public key
cat ~/.ssh/github_actions_deploy.pub

# SSH into server
ssh suprclaw@178.128.186.69

# Add to authorized_keys
echo "PASTE_PUBLIC_KEY_HERE" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### 3. Configure GitHub Secrets

Go to your GitHub repository:
1. **Settings** → **Secrets and variables** → **Actions**
2. Click **New repository secret**
3. Add these secrets:

| Secret Name | Value | Example |
|------------|-------|---------|
| `SSH_PRIVATE_KEY` | Contents of `~/.ssh/github_actions_deploy` | `-----BEGIN OPENSSH PRIVATE KEY-----...` |
| `SERVER_IP` | Your droplet IP address | `178.128.186.69` |
| `DEPLOY_USER` | SSH username | `suprclaw` |

**To get the private key:**
```bash
cat ~/.ssh/github_actions_deploy
# Copy the ENTIRE output including BEGIN and END lines
```

### 4. Push Workflow to GitHub

```bash
git add .github/workflows/deploy.yml
git commit -m "Add GitHub Actions CI/CD pipeline"
git push origin main
```

### 5. Environment File (.env)

The `.env` file is **NOT** included in the deployment package by default (for security).

**Option A: Commit .env to private repo (if repo is private)**
```bash
# Make sure your repo is private first!
git add .env
git commit -m "Add environment config"
git push
```

**Option B: Keep .env only on server (recommended)**
The workflow will use the existing `.env` file on the server. Make sure it exists:
```bash
ssh suprclaw@178.128.186.69
ls -l ~/SuprClaw/.env
```

---

## How to Deploy

### Create a Release

1. **Go to GitHub repository** → **Releases** → **Draft a new release**

2. **Create a new tag:**
   - Tag version: `v1.0.0` (or any version number)
   - Target: `main` branch

3. **Fill in release details:**
   - Release title: `v1.0.0 - Initial Production Release`
   - Description: Describe what changed in this release

4. **Click "Publish release"**

5. **GitHub Actions will automatically:**
   - ✅ Build the application
   - ✅ Create deployment package
   - ✅ Upload to server
   - ✅ Fix SSL permissions
   - ✅ Restart the backend service
   - ✅ Verify deployment

### Monitor Deployment

1. Go to **Actions** tab in GitHub
2. Click on the running workflow
3. Watch the deployment progress in real-time

---

## Troubleshooting

### Deployment Failed

Check the GitHub Actions logs:
1. Go to **Actions** tab
2. Click on the failed workflow run
3. Expand the failed step to see error details

### SSH Connection Failed

```bash
# Test SSH key locally
ssh -i ~/.ssh/github_actions_deploy suprclaw@178.128.186.69 "echo Connection successful"
```

### Service Not Starting

SSH into server and check logs:
```bash
ssh suprclaw@178.128.186.69
sudo journalctl -u suprclaw-backend -n 100 --no-pager
```

### SSL Permission Issues

The workflow automatically fixes SSL permissions, but you can manually run:
```bash
ssh suprclaw@178.128.186.69
sudo chmod 755 /etc/letsencrypt/live /etc/letsencrypt/archive
sudo chmod 755 /etc/letsencrypt/live/suprclaw.com /etc/letsencrypt/archive/suprclaw.com
sudo chmod 644 /etc/letsencrypt/archive/suprclaw.com/*.pem
sudo chmod 640 /etc/letsencrypt/archive/suprclaw.com/privkey*.pem
sudo chgrp suprclaw /etc/letsencrypt/archive/suprclaw.com/privkey*.pem
```

---

## Rollback to Previous Version

If a deployment fails, the workflow keeps backups:

```bash
ssh suprclaw@178.128.186.69
ls -la ~ | grep SuprClaw.backup

# Rollback to previous version
cd ~
sudo systemctl stop suprclaw-backend
rm -rf SuprClaw
mv SuprClaw.backup.YYYYMMDD_HHMMSS SuprClaw
sudo systemctl start suprclaw-backend
```

---

## Security Best Practices

- ✅ Use dedicated SSH key for CI/CD (not your personal key)
- ✅ Keep repository private if it contains sensitive data
- ✅ Never commit `.env` file to public repositories
- ✅ Use GitHub Secrets for all sensitive values
- ✅ Regularly rotate SSH keys (every 90 days)
- ✅ Enable branch protection on `main` branch
- ✅ Require pull request reviews before merging

---

## Advanced: Manual Deployment

If you need to deploy without creating a release:

```bash
# Build locally
./gradlew clean build

# Deploy using the script
./deploy.sh 178.128.186.69
```
