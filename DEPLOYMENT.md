# Deploy SuprClaw Backend to DigitalOcean

## Step 1: Create Backend Droplet

### Via DigitalOcean Dashboard:

1. **Go to**: https://cloud.digitalocean.com/droplets/new
2. **Choose an image**: Ubuntu 24.04 LTS
3. **Choose size**:
   - **Development**: Basic ($6/month) - 1 vCPU, 1GB RAM
   - **Production**: Basic ($12/month) - 1 vCPU, 2GB RAM (Recommended)
4. **Choose region**: Same as your other droplets (e.g., SFO2)
5. **Authentication**: SSH Key (recommended) or Password
6. **Hostname**: `suprclaw-backend`
7. **Click**: Create Droplet

### Get Droplet IP:
```
Example: 143.198.123.45
```

---

## Step 2: Initial Server Setup

### SSH into your droplet:
```bash
ssh root@YOUR_DROPLET_IP
```

### Update system:
```bash
apt-get update && apt-get upgrade -y
```

### Install Java 17:
```bash
apt-get install -y openjdk-17-jdk
java -version
# Should show: openjdk version "17.x.x"
```

### Install Git:
```bash
apt-get install -y git
```

### Create deployment user:
```bash
useradd -m -s /bin/bash suprclaw
usermod -aG sudo suprclaw
passwd suprclaw
# Set a strong password
```

---

## Step 3: Deploy Your Application

### Switch to deployment user:
```bash
su - suprclaw
```

### Clone or upload your project:

**Option A - Git (if you have a repo):**
```bash
cd ~
git clone https://github.com/YOUR_USERNAME/suprclaw-backend.git
cd suprclaw-backend
```

**Option B - Upload from local machine:**
```bash
# On your Mac:
cd /Users/itsivag/IdeaProjects/suprclaw
tar -czf suprclaw.tar.gz SuprClaw/
scp suprclaw.tar.gz suprclaw@YOUR_DROPLET_IP:~/

# On the droplet:
tar -xzf suprclaw.tar.gz
cd SuprClaw
```

### Create .env file:
```bash
nano .env
```

Add your environment variables:
```env
DIGITALOCEAN_API_KEY=your_digitalocean_api_token
GEMINI_API_KEY=your_gemini_api_key
OPENCLAW_SNAPSHOT_ID=your_snapshot_id
DOMAIN=your_domain.com
```

### Build the application:
```bash
./gradlew build
```

---

## Step 4: Create Systemd Service

### Create service file:
```bash
sudo nano /etc/systemd/system/suprclaw-backend.service
```

### Add this content:
```ini
[Unit]
Description=SuprClaw Backend API
After=network.target

[Service]
Type=simple
User=suprclaw
WorkingDirectory=/home/suprclaw/SuprClaw
ExecStart=/usr/bin/java -jar build/libs/SuprClaw-all.jar
Restart=always
RestartSec=10
StandardOutput=append:/home/suprclaw/logs/suprclaw.log
StandardError=append:/home/suprclaw/logs/suprclaw-error.log

# Environment
Environment="JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64"

[Install]
WantedBy=multi-user.target
```

### Create log directory:
```bash
mkdir -p /home/suprclaw/logs
```

### Enable and start service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable suprclaw-backend
sudo systemctl start suprclaw-backend
```

### Check status:
```bash
sudo systemctl status suprclaw-backend

# View logs
tail -f /home/suprclaw/logs/suprclaw.log
```

---

## Step 5: Configure Firewall

### Allow necessary ports:
```bash
sudo ufw allow OpenSSH
sudo ufw allow 8080/tcp  # Backend API
sudo ufw allow 80/tcp    # HTTP (for Let's Encrypt)
sudo ufw allow 443/tcp   # HTTPS (optional, for reverse proxy)
sudo ufw --force enable
```

### Check firewall status:
```bash
sudo ufw status
```

---

## Step 6: Setup Wildcard SSL Certificate

### Run on the backend droplet as root:
```bash
cd /home/suprclaw/SuprClaw
sudo ./setup-wildcard-ssl.sh
```

This will:
- Install certbot
- Obtain wildcard certificate for `*.suprclaw.com`
- Store at `/etc/letsencrypt/live/suprclaw.com/`
- Setup auto-renewal

---

## Step 7: Test Backend

### From your Mac:
```bash
# Test API
curl http://YOUR_DROPLET_IP:8080/

# Test droplet creation (use your Android app)
# Update ClawConfig.kt:
# const val DROPLET_API_URL = "http://YOUR_DROPLET_IP:8080"
```

---

## Step 8: (Optional) Setup Nginx Reverse Proxy

### For production, add nginx in front:
```bash
sudo apt-get install -y nginx

# Create config
sudo nano /etc/nginx/sites-available/suprclaw-backend
```

```nginx
server {
    listen 80;
    server_name api.suprclaw.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
# Enable site
sudo ln -s /etc/nginx/sites-available/suprclaw-backend /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx

# Add DNS record: api.suprclaw.com → YOUR_DROPLET_IP
# Update Android app to: https://api.suprclaw.com
```

---

## Maintenance Commands

### View logs:
```bash
# Application logs
tail -f /home/suprclaw/logs/suprclaw.log

# System logs
sudo journalctl -u suprclaw-backend -f
```

### Restart service:
```bash
sudo systemctl restart suprclaw-backend
```

### Update application:
```bash
cd /home/suprclaw/SuprClaw
git pull  # or upload new files
./gradlew build
sudo systemctl restart suprclaw-backend
```

### Check SSL certificate:
```bash
sudo certbot certificates
```

### Renew SSL (automatic, but can test):
```bash
sudo certbot renew --dry-run
```

---

## Security Checklist

- ✅ Firewall enabled (UFW)
- ✅ Non-root user for application
- ✅ SSH key authentication (disable password auth)
- ✅ Regular backups of .env file
- ✅ SSL certificate with auto-renewal
- ✅ Application logs rotation

---

## Production Recommendations

1. **Use environment-specific .env files**
2. **Setup monitoring** (UptimeRobot, Datadog, etc.)
3. **Enable backups** (DigitalOcean automated backups)
4. **Use managed database** if storing data
5. **Setup CI/CD** for automated deployments
6. **Add rate limiting** to API endpoints
7. **Use nginx as reverse proxy**
8. **Setup HTTPS for API** (api.suprclaw.com)

---

## Estimated Costs

- **Backend Droplet**: $12/month (1vCPU, 2GB RAM)
- **Client Droplets**: $12/month each (as users create them)
- **Total Month 1**: $12 + (number of client droplets × $12)

---

## Quick Start Script

Save time with this automated deployment script coming in the next file!
