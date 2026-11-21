# Docker

| File | |
|---|---|
| `Dockerfile` | Multi-stage: `gradle:9.9-jdk21` builds (`installDist`), `eclipse-temurin:21-jre` runs. Build context = repo root. Multi-arch (amd64/arm64). |
| `docker-compose.yml` | One service `ktor-app` listening on 8080 **inside** the compose network (no host port published), SQLite on the named volume `twin-data`, secrets from `../.env`. |
| `certs/` | Optional `*.crt` CAs trusted only at build time (TLS-intercepting proxies such as Netskope). Git-ignored, never in the runtime image. |

## Run

```sh
cp .env.example .env              # once, at the repo root: MQTT_BROKER_URL, MQTT_USERNAME, MQTT_PASSWORD
cd docker
docker compose up -d --build      # build + start
docker compose logs -f
docker compose exec ktor-app curl -fsS localhost:8080/api/v1/status
docker compose down               # stop (data stays in the twin-data volume; add -v to wipe it)
```

Variables read by compose (shell or `docker/.env`): `KTOR_APP_IMAGE` (image tag, default `ktor-app:latest`).

No port is published on the host: reach the app from another service on the same compose network
(`http://ktor-app:8080`, e.g. a reverse proxy). For local access, add `ports: ["8080:8080"]` in a
`docker-compose.override.yml`.

## Build / ship the image only

```sh
docker build -f docker/Dockerfile -t ktor-app:latest .                     # from the repo root
docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile -t <registry>/ktor-app:<tag> --push .
```

On the target host, set `KTOR_APP_IMAGE=<registry>/ktor-app:<tag>` and run `docker compose up -d` (no `--build`).

## Runtime

- Runs as non-root user `app` (uid 10001); app in `/app`, data in `/app/data` (volume).
- Env: `PORT=8080`, `DB_PATH=/app/data/twin.sqlite`, `JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`,
  plus everything in `application.yaml` (`MQTT_*`, `MQTT_ENABLED`).
- Healthcheck: `GET /health` every 30 s. Restart policy: `unless-stopped`. Logs: json-file, 3 × 10 MB.
- Train state is in memory and rebuilds from MQTT in a few seconds after a restart; only incidents/stations persist.
- `.env` is excluded by `.dockerignore`: secrets are injected at run time, never baked into the image.
