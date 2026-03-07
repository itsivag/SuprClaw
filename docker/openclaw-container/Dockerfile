# OpenClaw Multi-Tenant Container
#
# This container runs an isolated OpenClaw instance for a single user.
# Each user gets their own container with:
# - OpenClaw gateway (port 18789)
# - MCP auth proxy (pre-baked from src/main/resources/mcp-auth-proxy.js)
# - mcporter
#
# Build context must be the project root (SuprClaw/):
#   docker build -t suprclaw/openclaw:latest -f docker/openclaw-container/Dockerfile .
# Run:
#   docker run -p 18001:18789 -e GATEWAY_TOKEN=xxx suprclaw/openclaw

FROM ubuntu:22.04

# Prevent interactive prompts during build
ENV DEBIAN_FRONTEND=noninteractive

# Install system dependencies
RUN apt-get update && apt-get install -y \
    curl \
    git \
    build-essential \
    python3 \
    python3-pip \
    ca-certificates \
    gnupg \
    lsb-release \
    supervisor \
    jq \
    net-tools \
    procps \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js 20.x
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Verify Node.js installation
RUN node --version && npm --version

# Create openclaw user
RUN useradd -m -s /bin/bash openclaw \
    && mkdir -p /home/openclaw/.openclaw \
    && mkdir -p /home/openclaw/.config/systemd/user \
    && chown -R openclaw:openclaw /home/openclaw

# Install OpenClaw globally
# Note: Using npm install -g for simplicity. 
# In production, you might want to pin a specific version
RUN npm install -g openclaw@latest \
    && npm cache clean --force

# Install additional global tools that OpenClaw might need
RUN npm install -g mcporter@latest \
    && npm cache clean --force

# Create necessary directories
RUN mkdir -p /var/log/supervisor \
    && mkdir -p /etc/supervisor/conf.d \
    && mkdir -p /home/openclaw/.openclaw

# Copy configuration files (paths relative to project root build context)
COPY docker/openclaw-container/supervisord.conf /etc/supervisor/conf.d/supervisord.conf
COPY docker/openclaw-container/entrypoint.sh /entrypoint.sh
# Copy the MCP auth proxy script (pre-built into image; no SSH deploy needed at runtime)
COPY src/main/resources/mcp-auth-proxy.js /usr/local/bin/mcp-auth-proxy.js

# Make entrypoint executable
RUN chmod +x /entrypoint.sh

# Create log files with proper permissions
RUN touch /var/log/openclaw-gateway.log \
    /var/log/openclaw-gateway.err \
    /var/log/mcp-auth-proxy.log \
    /var/log/mcp-auth-proxy.err \
    /var/log/mcporter.log \
    /var/log/mcporter.err \
    && chown -R openclaw:openclaw /var/log/openclaw-* \
    && chown -R openclaw:openclaw /home/openclaw \
    && chmod 644 /usr/local/bin/mcp-auth-proxy.js

# Switch to openclaw user for OpenClaw setup
USER openclaw
WORKDIR /home/openclaw

# Run OpenClaw setup (creates default config)
# This initializes the .openclaw directory structure
RUN openclaw setup --non-interactive || true

# Pre-configure some settings
RUN mkdir -p /home/openclaw/.openclaw \
    && echo '{}' > /home/openclaw/.openclaw/openclaw.json

# Switch back to root for supervisord
USER root

# Expose the OpenClaw gateway port
# Note: This is the container's internal port. 
# Host maps different ports (18001, 18002, etc.) to this
EXPOSE 18789

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:18789/health || exit 1

# Use entrypoint script for runtime configuration
ENTRYPOINT ["/entrypoint.sh"]

# Default command runs supervisord
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
