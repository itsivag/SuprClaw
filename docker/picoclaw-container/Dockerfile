FROM golang:1.25-bookworm AS builder

ARG PICOCLAW_REF=main

RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates make && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 --branch "${PICOCLAW_REF}" https://github.com/sipeed/picoclaw.git .
RUN make build

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV PICOCLAW_HOME=/home/picoclaw/.picoclaw
ENV PICOCLAW_CONFIG=/home/picoclaw/.picoclaw/picoclaw.json

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    jq \
    nodejs \
    npm \
    openssh-client \
    python3 \
    supervisor \
    tini \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash picoclaw && mkdir -p /home/picoclaw/.picoclaw

COPY --from=builder /src/build/picoclaw /usr/local/bin/picoclaw
COPY docker/picoclaw-container/entrypoint.sh /entrypoint.sh
COPY docker/picoclaw-container/supervisord.conf /etc/supervisor/conf.d/supervisord.conf
COPY --chown=picoclaw:picoclaw docker/picoclaw-container/workspace/ /opt/suprclaw/workspace/

RUN chmod 755 /entrypoint.sh /usr/local/bin/picoclaw

EXPOSE 18790

ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
