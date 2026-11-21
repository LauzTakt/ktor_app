# ktor_app — agent guide

Kotlin/Ktor backend of the LauzHack 2026 train digital-twin project. **Read this first**, then the two reference docs:

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): full system diagram (source of truth for what this app must do).
- [`docs/DATA_SOURCE.md`](docs/DATA_SOURCE.md): the sbb-twin MQTT feed this app consumes (topics, payloads, rules).
- [`docs/API.md`](docs/API.md): our public REST API v1 (contract for the web UI and the prediction model). Keep it in sync with `api/v1/`.

## Where this app sits

```
Swiss train API + Meteo API + Twin web UI (operator edits)
        -> Train digital twin (sbb-twin) -> publishes to MQTT broker (Mosquitto, retained state, QoS 1)
        -> THIS APP (Kotlin Ktor) <- Prediction model (calls our REST API, self-triggered)
        -> Browser clients (3-4 users) over WebSocket + static HTML pages
```

The prediction model also reads "modified data" from the broker on its own; it is **not** part of this app.

## Components

| Component | Status | Where |
|---|---|---|
| MQTT client (Paho mqttv3) | **done**: `status`, `incidents/+`, `trains/+/info`, `fleet` | `mqtt/MqttIngestor.kt` |
| Last known train state (memory) | **done** | `state/LiveState.kt` |
| SQLite: incident history + station catalog | **done** | `db/Database.kt` |
| REST API v1 (read: trains, stations, boards, incidents, status) | **done** — see docs/API.md | `api/v1/` |
| REST API for predictions (model pushes to us) | todo | |
| WebSocket push/subscribe to browsers | todo (will drive per-train `trains/{id}/position` subscriptions) | |
| Static web UI | todo | |

## Data model (step 1: ingest)

**Memory only — train state is never written to disk** (≈750 trains × 1 Hz). On restart it rebuilds itself in a
few seconds from the retained `trains/+/info` topics plus the next `fleet` message.

- `LiveState.infos`: trainId -> latest `TrainInfo` (run, every stop with planned/expected/actual times).
- `LiveState.positions`: latest `fleet` snapshot, trainId -> `TrainPosition` (replaced every second; absent = left service).
- `LiveState.trainsByStop`: stopId -> trainIds whose run calls there. **This answers "which trains will pass station X"**:
  `LiveState.trainsCallingAt(stopId)` + `TrainState.nextCallIndex` (first stop not yet left) -> `TwinQueries.board`.
- `LiveState.stations`: stopId -> name/lat/lon, learned from `calls` (≈2,200 stations).

**SQLite** (`data/twin.sqlite`, WAL, single writer thread; schema in `Database.migrate()`):

- `incident`: one row per incident, current version + `first_seen_at` + `removed_at` (NULL = active).
  Scope flattened into `scope_kind/scope_from/scope_to/scope_train` (indexed) + raw `scope_json`; GeoJSON in `geometry_json`.
- `incident_event`: append-only history (`created | updated | removed | missing`) with the full payload.
  A retained re-delivery with the same `updatedAt`/`status` is NOT recorded. `missing` = active in DB but not retained by
  the broker 15 s after a reconnect (it was unmarked while we were offline).
- `station`: catalog, upserted when a new stopId appears.

REST read API: `/api/v1/...`, documented in [`docs/API.md`](docs/API.md). Layers: `Routes.kt` (routing, param
validation, `StatusPages` errors) -> `TwinQueries.kt` (joins LiveState + active incidents, pure/testable) -> `Dto.kt`
(public contract, decoupled from MQTT payloads). `Application.configureHttp(state, db, …)` lets tests inject a seeded
`LiveState` (see `ApiTest.kt`, fixtures in `Fixtures.kt`).

## Feed facts verified on the live broker (2026-11-21) — not all are in DATA_SOURCE.md

- Broker for the hack: TCP 1883 **with username/password** (in `.env`, git-ignored; template `.env.example`).
- Traffic: `trains/+/position` ≈ 750 msg/s (480 B each); `fleet` 1 msg/s ≈ 74 KB with all trains; `trains/+/info`
  ≈ 760 retained on connect + ~9 live updates/s (only `calls` and `updatedAt` change); `weather/snapshot` ≈ 50 KB.
- `info` exists **only for trains in service** (published when the run starts, tombstoned at the end). Station
  queries therefore only know trains already running, not the full day's timetable.
- In `calls`, the stops with `expectedArr/expectedDep` set are always a **suffix** = stops still ahead.
  `actual*` is only set for stops seen since the twin started tracking (missing for earlier stops): don't use it to
  decide "already passed".
- `info` lags the position by one stop for ~20 % of trains. `nextCallIndex` corrects it with `fleet.atStopId`
  (standing at X -> X; seen leaving X -> the stop after X).
- Trains at their terminus have no `expected*` left; they show up as `atStation` until they leave service.
- `stopId` is always a 7-digit UIC/DIDOK id (e.g. `8503000` Zürich HB). `position.atStop` is `{id, name}`; in `fleet` it is `atStopId`.
- Incidents carry an extra `disruptionId` (e.g. `d-9afbd339`). Geometry is a MultiLineString (~12 KB).
- `status` has more fields than documented (`trainsSimulated`, `realtime`, `weather`, `broker`, `timetableDate`, …).

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
- Config: `application.yaml`, every value overridable by env: `PORT` (8080), `MQTT_BROKER_URL`, `MQTT_USERNAME`,
  `MQTT_PASSWORD`, `MQTT_ENABLED` (true), `DB_PATH` (`data/twin.sqlite`). `./gradlew run` loads `.env` automatically.
- **Never commit `.env`** or any broker credential.
- Tests (`src/test`) run with `mqtt.enabled=false` and a temp SQLite file.

## Build and run

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
./gradlew test
./gradlew run            # http://localhost:8080/api/v1/status
```

On the original dev machine the default `java` is 1.8 (too old) and the Gradle wrapper fails to download its
distribution (TLS `PKIX path building failed`); use the Homebrew `gradle` (9.9.2) instead of `./gradlew` there.
