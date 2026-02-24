# Wildcard SSL Certificate Setup for *.suprclaw.com

## One-Time Setup (Run on your backend server)

### Step 1: Install Certbot with DigitalOcean Plugin

```bash
# Install certbot and DigitalOcean DNS plugin
sudo apt-get update
sudo apt-get install -y certbot python3-certbot-dns-digitalocean
```

### Step 2: Create DigitalOcean API Credentials File

```bash
# Create credentials file
sudo mkdir -p /etc/letsencrypt/
sudo nano /etc/letsencrypt/digitalocean.ini
```

Add this content:
```ini
dns_digitalocean_token = your_digitalocean_api_token
```

```bash
# Secure the file
sudo chmod 600 /etc/letsencrypt/digitalocean.ini
```

### Step 3: Obtain Wildcard Certificate

```bash
sudo certbot certonly \
  --dns-digitalocean \
  --dns-digitalocean-credentials /etc/letsencrypt/digitalocean.ini \
  -d "*.suprclaw.com" \
  -d "suprclaw.com" \
  --non-interactive \
  --agree-tos \
  --email admin@suprclaw.com
```

**This will:**
- Create DNS TXT record for verification
- Obtain wildcard certificate
- Store cert at: `/etc/letsencrypt/live/suprclaw.com/`

### Step 4: Verify Certificate

```bash
sudo ls -la /etc/letsencrypt/live/suprclaw.com/

# Should show:
# fullchain.pem  - Full certificate chain
# privkey.pem    - Private key
# cert.pem       - Certificate only
# chain.pem      - Certificate chain
```

### Step 5: Set Up Auto-Renewal

```bash
# Certbot automatically creates renewal timer
sudo systemctl status certbot.timer

# Test renewal
sudo certbot renew --dry-run
```

## Result

✅ Wildcard certificate: `*.suprclaw.com`
✅ Auto-renews every 60 days
✅ Valid for ALL subdomains
✅ Stored at: `/etc/letsencrypt/live/suprclaw.com/`

---

## Next: Deploy to Droplets

The backend will automatically copy this certificate to each new droplet during provisioning.
