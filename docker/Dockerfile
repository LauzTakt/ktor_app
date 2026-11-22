# syntax=docker/dockerfile:1
# Build context = repository root:  docker build -f docker/Dockerfile -t ktor-app .

# ---------- passenger voice assistant (client/, React + Vite) ----------
FROM node:22-slim AS client
WORKDIR /src/client
COPY docker/certs/ /tmp/certs/
# Extra CAs for npm behind a TLS-intercepting proxy (no-op when the folder is empty)
RUN cat /tmp/certs/*.crt > /tmp/extra-ca.pem 2>/dev/null || true
ENV NODE_EXTRA_CA_CERTS=/tmp/extra-ca.pem
COPY client/package.json client/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY client/ ./
RUN npm run build

# ---------- build ----------
FROM gradle:9.9-jdk21 AS build
WORKDIR /src

# Optional: trust extra CAs (docker/certs/*.crt) for TLS-intercepting proxies. No-op when the folder is empty.
COPY docker/certs/ /tmp/certs/
RUN set -e; for c in /tmp/certs/*.crt; do [ -f "$c" ] || continue; \
      cp "$c" /usr/local/share/ca-certificates/; \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$(basename "$c" .crt)" -file "$c"; \
    done; update-ca-certificates > /dev/null

# Dependencies first (cached unless build files change)
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon -q dependencies --configuration runtimeClasspath > /dev/null

COPY src src
COPY --from=client /src/build/client build/client
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon -q installDist -x test -PskipClient=true

# ---------- runtime ----------
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app app \
 && mkdir -p /app/data && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /src/build/install/ktor-app /app

USER app
ENV PORT=8080 \
    DB_PATH=/app/data/twin.sqlite \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["/app/bin/ktor-app"]
