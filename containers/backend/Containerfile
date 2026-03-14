FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN useradd --create-home --shell /bin/bash suprclaw

COPY build/libs/SuprClaw-all.jar /app/SuprClaw-all.jar

RUN chown -R suprclaw:suprclaw /app

USER suprclaw

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx1536m"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/bin/sh", "-lc", "exec java $JAVA_OPTS -jar /app/SuprClaw-all.jar"]
