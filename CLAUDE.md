# ktor_app — agent guide

Kotlin/Ktor backend of the LauzHack 2026 train digital-twin project. **Read this first**, then the two reference docs:

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): full system diagram (source of truth for what this app must do).
- [`docs/DATA_SOURCE.md`](docs/DATA_SOURCE.md): the sbb-twin MQTT feed this app consumes (topics, payloads, rules).

## Where this app sits

```
Swiss train API + Meteo API + Twin web UI (operator edits)
        -> Train digital twin (sbb-twin) -> publishes to MQTT broker (Mosquitto, retained state, QoS 1)
        -> THIS APP (Kotlin Ktor) <- Prediction model (calls our REST API, self-triggered)
        -> Browser clients (3-4 users) over WebSocket + static HTML pages
```

The prediction model also reads "modified data" from the broker on its own; it is **not** part of this app.

## Components to build (none exist yet: the project is an empty skeleton)

| Component | Responsibility | Notes |
|---|---|---|
| MQTT client | Subscribe to **incidents** and to **trains that users subscribed to** | Eclipse Paho (`org.eclipse.paho.client.mqttv3` or `mqttv5`). Unique client id, clean session. |
| SQLite | Persist **current state + history** of what we receive | Written by the MQTT client; read by REST/WebSocket |
| REST API | Receive **predictions** from the prediction model | Model pushes to us; design `POST` endpoints |
| WebSocket (wss) | **Push/subscribe** with browsers | Browser subscribes to trains -> drives which MQTT topics we subscribe to; we push updates + predictions |
| HTTP pages | Serve the **static web UI** | Ktor `staticResources` |

## Data-source rules (from DATA_SOURCE.md — do not break)

- Broker: `ws://127.0.0.1:8787/mqtt` (local), `mqtt://127.0.0.1:1883` (TCP local/Docker), `wss://<public-host>/mqtt` (public). MQTT 3.1.1/5, anonymous.
- **Read-only.** We may only publish under `sbb-twin/v1/detections/{team}/…` and `sbb-twin/v1/ext/{team}/…`.
- All topics are under `sbb-twin/v1/`. Payloads are UTF-8 JSON with a schema version `v`.
- Times are **epoch milliseconds UTC** (local time `Europe/Zurich`); coordinates WGS84.
- **Empty retained payload = tombstone** (entity removed). Always handle zero-length payloads before parsing.
- **Never subscribe to `truth/…`** — staff/evaluation only.
- Useful topics:
  - `incidents/{incidentId}` (retained, QoS 1) + `events/incident` — incidents.
  - `trains/{trainId}/position` (QoS 0, every second) — per-train position, speed, delay, prev/next stop.
  - `trains/{trainId}/info` (retained, QoS 1) — line, operator, all stops with planned/expected/actual times, status.
  - `fleet` (QoS 0, every second) — all ~900–1,900 trains in one compact message (`fields` + `trains` rows). Heavy; only if needed.
  - `status` (retained, every 10 s) — heartbeat; no update for 30 s = twin offline.
  - `weather/…` — stations, snapshot, temperature grid (every 10 min).
- `trainId` = `{YYYYMMDD}_{GTFS trip_id without leading dot}`, stable per run.
- `continuity` increases when a train jumps; `streamId` changes when sbb-twin restarts -> don't interpolate across either.
- A train missing from a newer `fleet` message has left service.
- Position topics are 1 Hz per train: throttle/aggregate before writing history to SQLite.

## Project layout and stack

- Kotlin 2.4.21, Ktor 3.6.1 (Netty engine, `EngineMain`), kotlinx.serialization, Logback. Versions in `gradle/libs.versions.toml`.
- JVM toolchain **21** (`kotlin { jvmToolchain(21) }`).
- Package `ch.lauzhack.twin`; entry module `Application.module()` in `src/main/kotlin/ch/lauzhack/twin/Application.kt`, wired in `src/main/resources/application.yaml`.
- Config: `application.yaml` (port via `PORT`, default 8080). Put MQTT/DB settings there (placeholders are commented in the file).
- Only endpoint today: `GET /health` -> `{"status":"ok"}`. Test in `src/test/kotlin/.../ApplicationTest.kt`.

## Build and run

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
./gradlew test
./gradlew run            # http://localhost:8080/health
```

On the original dev machine the default `java` is 1.8 (too old) and the Gradle wrapper fails to download its
distribution (TLS `PKIX path building failed`); use the Homebrew `gradle` (9.9.2) instead of `./gradlew` there.
