# Docker

| File | |
|---|---|
| `Dockerfile` | Multi-stage: `gradle:9.9-jdk21` builds (`installDist`), `eclipse-temurin:21-jre` runs. Build context = repo root. Multi-arch (amd64/arm64). |
| `docker-compose.yml` | One service `ktor-app` listening on 8080 **inside** the compose network (no host port published), SQLite on the named volume `twin-data`. **All config from environment variables** (Coolify-friendly). |
| `certs/` | Optional `*.crt` CAs trusted only at build time (TLS-intercepting proxies such as Netskope). Git-ignored, never in the runtime image. |

## Environment variables

Read by the compose file from the environment (Coolify shows them in the app's *Environment Variables* tab).

| Variable | Required | Default | |
|---|---|---|---|
| `MQTT_BROKER_URL` | **yes** | — | e.g. `tcp://host:1883` (compose refuses to start without it) |
| `MQTT_USERNAME` | no | empty | |
| `MQTT_PASSWORD` | no | empty | mark as secret in Coolify |
| `MQTT_ENABLED` | no | `true` | |
| `ANTHROPIC_API_KEY` | no | empty | Claude key for `POST /api/v1/assistant/ask` (503 without it); mark as secret |
| `SBB_TWIN_URL` | no | `http://127.0.0.1:8787` | sbb-twin HTTP API (planner, situation, geometry, brief, `/twin/*` proxy) |
| `ELEVENLABS_API_KEY` | no | empty | voice of the passenger assistant at `/assistant/` (text only without it); mark as secret |
| `PASSENGER_AGENT_ID` | no | empty | ElevenLabs agent id (`cd client && npm run agent` creates it) |
| `TRIP_WATCH_ENABLED` | no | `true` | Set to `false` to turn off trip monitoring. Falls back to `WALLET_ENABLED` when unset. |
| `JAVA_OPTS` | no | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | |
| `KTOR_APP_IMAGE` | no | `ktor-app:latest` | image tag (build output / pull target) |

`PORT=8080` and `DB_PATH=/app/data/twin.sqlite` are fixed in the compose file.

## Deploy on Coolify

1. New resource -> this Git repo -> build pack **Docker Compose**, compose location `/docker/docker-compose.yml`.
2. Environment Variables: set `MQTT_BROKER_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD` (Coolify lists them from the file).
3. Domain for the `ktor-app` service: `https://<your-domain>:8080` (the `:8080` tells Coolify's proxy the container port;
   no host port is published). The WebSocket endpoints work through the proxy as-is.
4. Deploy. The `twin-data` volume keeps the SQLite file across redeploys.

## Run locally

```sh
cp .env.example .env              # once, at the repo root
docker compose -f docker/docker-compose.yml --env-file .env up -d --build
docker compose -f docker/docker-compose.yml --env-file .env exec ktor-app curl -fsS localhost:8080/api/v1/status
docker compose -f docker/docker-compose.yml --env-file .env down   # data stays in the volume; add -v to wipe it
```

No port is published on the host. For local access from the browser, add `ports: ["8080:8080"]` in a
`docker/docker-compose.override.yml` (git-ignore it), or reach it from another service at `http://ktor-app:8080`.

## Build / ship the image only

```sh
docker build -f docker/Dockerfile -t ktor-app:latest .                     # from the repo root
docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile -t <registry>/ktor-app:<tag> --push .
```

On the target host, set `KTOR_APP_IMAGE=<registry>/ktor-app:<tag>` plus the variables above and run `docker compose up -d` (no `--build`).

## Runtime

- Runs as non-root user `app` (uid 10001); app in `/app`, data in `/app/data` (volume).
- Env: `PORT=8080`, `DB_PATH=/app/data/twin.sqlite`, `JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`,
  plus everything in `application.yaml` (`MQTT_*`, `MQTT_ENABLED`).
- Healthcheck: `GET /health` every 30 s. Restart policy: `unless-stopped`. Logs: json-file, 3 × 10 MB.
- Train state is in memory and rebuilds from MQTT in a few seconds after a restart; only incidents/stations persist.
- `.env` is excluded by `.dockerignore` and the compose file never reads it implicitly: secrets come from the
  environment at run time and are never baked into the image.
