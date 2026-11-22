# ktor_app code architecture: layers and module interactions

[`ARCHITECTURE.md`](ARCHITECTURE.md) shows where this app sits in the whole system. This document describes the
inside of `src/main/kotlin/ch/lauzhack/twin/`: which package does what, which package may call which other one, and
how data moves between them at runtime. Keep it in sync when a package or a dependency between packages changes.

## Layers

```
                 HTTP / WebSocket (Ktor routes)                       <- browsers, prediction model, ESP32, wallets
 +-----------------------------------------------------------------+
 | api/v1   auth   assistant   passenger   wallet   push   web      |   1. delivery: routing, params, (de)serialization
 +-----------------------------------------------------------------+
            |            |           |          |
            v            v           v          v
 +-----------------------------------------------------------------+
 | query (TwinQueries, JourneyQueries, IncidentQueries, read models)|   2. read side: pure functions over state + incidents
 | AssistantAgent / Tools, PlaceBrief, TripWatchService, PushService|      feature services (own logic, no Ktor in them)
 +-----------------------------------------------------------------+
            |                    |                         |
            v                    v                         v
 +-------------------+  +-------------------+  +--------------------------+
 | state (LiveState) |  | db (Database)     |  | twinapi (SbbTwinClient,  |    3. data sources
 | memory, 1 Hz      |  | SQLite, 1 writer  |  |  JourneyCache)           |
 +-------------------+  +-------------------+  +--------------------------+
            ^                    ^                         |
            |  mqtt (MqttIngestor): the only writer        |  HTTP GET, read only
            +---------- of state and incidents ----        v
                       MQTT broker (sbb-twin)          sbb-twin HTTP API

 model (MQTT payloads)  and  common (errors, geo, time, JSON, text): used by every layer, depend on nothing.
```

`Application.kt` is the composition root: it creates every object once and passes it down (constructor injection,
no globals). `configureHttp(...)` takes `LiveState`, `Database` and the configs as parameters, so tests build the
same object graph over a seeded state and a temporary SQLite file.

## Packages

| Package | Responsibility | May depend on |
|---|---|---|
| `model` | MQTT payload shapes (`TrainInfo`, `TrainPosition`, `Incident`, `TwinStatus`) | — |
| `common` | Domain errors (`AppException` and subclasses), geo (`haversineM`, `distanceKm`, `move`), GeoJSON (`forEachCoord`, `coordsOf`, `BBoxBuilder`, `simplifyGeometry`), Swiss time (`ZURICH`, `hhmm`, `dayTime`, `dayMonth`, `delayMin`, `minutes`), loose JSON access (`str`, `obj`, `objects`, `geometry`, `truthy`, `opt`, `toPlain`, `toJsonElement`, …), text (`searchKey` for accent-free station search, `lineKey`, `spacedLine`, `cleanDashes`, `urlEncode`) | — |
| `state` | `LiveState`: latest train infos, fleet positions, stop index, station catalog. Memory only | `model` |
| `db` | `Database`: SQLite incidents + history, station catalog, staff reports, users, tokens. Incident change listeners | `model`, `state` (`Station`) |
| `mqtt` | `MqttIngestor`: subscribes to sbb-twin and writes into `state` and `db` | `model`, `state`, `db` |
| `twinapi` | `SbbTwinClient`: the only HTTP client for sbb-twin (GET only). `JourneyCache`: planner journeys shared by the features | `common` |
| `query` | Read side: `TwinQueries` (trains, stations, boards, screens, incident matching), `JourneyQueries` (nearby, destinations, connections), `IncidentQueries`; `Severity` (rank, colour, wording of incident severities) and `Lines.kt` (`sameLine`, `lineIn`, `mentionsLine`, `TrainState.line`): the one place for these rules; `ReadModels.kt` = the response types, which are also the public API v1 contract | `common`, `model`, `state`, `db` |
| `api/v1` | REST `/api/v1/*` routes, `ScreenText`, staff reports, WebSocket hub `LiveUpdates`, HTTP mapping of domain errors (`Errors.kt`) | `query`, `common`, `auth`, `db`, `state` |
| `auth` | Staff accounts, bearer tokens + session cookie, `staffOnly { }`, staff pages | `db`, `common` |
| `assistant` | Passenger assistant: Claude agent loop (`AssistantAgent`), tools (`AssistantTools`), `StationResolver`, `POST /api/v1/assistant/ask` | `query`, `state`, `db`, `twinapi`, `common` |
| `passenger` | Server side of the voice app: ElevenLabs session, place brief, nearest station, fleet snapshot, read-only `/twin/*` proxy | `twinapi`, `common`, `web` (`FleetFeed`) |
| `wallet` | Trip Watch: Apple / Google Wallet passes kept up to date for a planned journey (own SQLite file) | `query`, `twinapi`, `common` |
| `push` | Push notifications (Web Push + FCM for the Android app): follow a train (arrival at your stop + incidents) or a line (incidents). `PushStore` = its tables in the shared SQLite file (through `Database.withConnection`), `Senders` = Web Push / FCM HTTP clients | `query`, `state`, `db`, `common` |
| `web` | `FleetFeed` + `/ws/fleet`: whole fleet once per tick for the staff map; `AppLinks`: `/.well-known/` files for mobile deep links (env config, no data) | `state` |

### Dependency rules

1. **Nothing depends on a delivery package.** `api/v1`, `assistant`, `passenger`, `wallet` and `web` routes are
   leaves. A feature that needs another feature's data reads it from `query`, `state`, `db` or `twinapi`, never from
   the other feature's package. Example: the assistant, the passenger app and Trip Watch share planned journeys
   through `twinapi.JourneyCache`, not through each other.
2. **No HTTP below the routes.** Queries and services throw `common` errors (`NotFoundException`,
   `InvalidInputException`, `ConflictException`, `UnauthorizedException`, `UnavailableException`) with a stable
   `code`. Only `api/v1/Errors.kt` (`installApiErrors`) maps them to an HTTP status and the
   `{"error":{code,message}}` body.
3. **One way to reach sbb-twin**: `twinapi.SbbTwinClient`. Every failure is a `TwinException` (an `IOException`:
   status 0 = unreachable, otherwise the HTTP status), so callers handle "twin down" in one place.
4. **Writes go through one path.** Train state is written only by `MqttIngestor` (MQTT callback thread). SQLite is
   written by `Database`'s single writer thread; routes, the assistant and Trip Watch only read or call `Database`
   methods that queue writes.
5. **Shared helpers live in `common`**, not in a feature. Before adding a JSON accessor, a time format, a distance or
   a name normalisation to a feature, check `common`.

## Runtime interactions

### 1. Ingest (MQTT -> memory + SQLite)

```
broker --status, trains/+/info, incidents/+, fleet--> MqttIngestor (Paho callback thread)
   info      -> LiveState.upsertInfo()  -> new stations -> Database.saveStations()   (queued)
   info ""   -> LiveState.removeInfo()                                               (tombstone)
   fleet     -> LiveState.replacePositions()  (one immutable map per second)
   incident  -> Database.upsertIncident() / removeIncident()                         (queued)
   status    -> LiveState.updateStatus()
reconnect + 15 s -> Database.reconcileActiveIncidents(retained ids)   (incidents unmarked while offline -> `missing`)
```

At startup `Application.module()` builds `LiveState(db.stations())`, so the station catalog is complete before the
first MQTT message; trains come back within seconds from the retained `info` topics.

### 2. Read API (REST)

```
GET /api/v1/...  -> api/v1/Routes.kt (params, limits)  -> TwinQueries / JourneyQueries / IncidentQueries
                                                            |-> LiveState (trains, positions, stations)
                                                            |-> Database.incidents(activeOnly) (via a lambda)
                 <- read model (ReadModels.kt) serialized as JSON, or AppException -> installApiErrors
```

`TwinQueries` receives the active incidents as `() -> List<IncidentRow>`, which keeps it a pure function of its
inputs in tests. `/stations/{id}/screen.txt` renders the same `ScreenDto` as text (`ScreenText`, ETag + 304).

### 3. Live updates (WebSocket)

```
Database writer thread --incident change--> listeners:
   LiveUpdates (SharedFlow) --> each /api/v1/ws connection: pushes the incident if it matches a subscription
   Trip Watch worker        --> wakes up and refreshes the passes (else every 20 s)
/api/v1/ws subscription loops (every frequencySec) --> TwinQueries.trainLive / board / screen
/ws/fleet --> FleetFeed (encodes the fleet once per tick, shared by every staff browser)
```

Incident matching (`trainIncidentMatch`, `stationIncidentMatch`) lives only in `TwinQueries`; REST, WebSocket,
station screen and assistant use the same rules.

### 4. Passenger assistant

```
POST /api/v1/assistant/ask -> assistant/AssistantRoutes (validation) -> AssistantAgent (Claude loop, parallel tools)
   AssistantTools -> TwinQueries (trains, boards, incidents of ours)
                  -> Database   (open staff reports, raw incident payloads)
                  -> StationResolver -> LiveState.stations()
                  -> SbbTwinClient (plan, situation, geometry) + JourneyCache
   <- AskResponse (text, options, situation, visual commands)
```

Without `ANTHROPIC_API_KEY` the agent is not built and the route answers 503 (`UnavailableException`).

### 5. Passenger voice app (`/api/*`, `/twin/*`)

```
passengerApi -> PassengerService -> PlaceBrief / RouteExtras -> SbbTwinClient (+ JourneyCache when Trip Watch is on)
                                 -> StationCatalog -> sbb-twin stations.geojson, else our LiveState stations
                                 -> FleetFeed (fleet snapshot)
             -> /twin/* : read-only proxy to sbb-twin (GET/HEAD)
```

This API keeps the sbb-client error shape `{"error": "message"}` (the React client in `client/` expects it); it is
the one deliberate exception to rule 2's error body.

### 6. Trip Watch (wallet)

```
tripWatchRoutes -> TripWatchService -> TripWatchStore (own SQLite file)
                                    -> TripWatchJourneys -> TwinQueries.trainLive (our live data)
                                                         -> SbbTwinClient + JourneyCache (planned legs)
                                    -> AppleWallet / GoogleWallet (pass updates)
worker loop: refresh on every incident change (Database listener) or every 20 s
```

### 7. Push notifications

```
pushV1 (/api/v1/push/...) -> PushService -> PushStore (devices, subscriptions, notified incidents; shared SQLite)
Database incident listener --> PushService.onIncident -> TwinQueries.trainIncidentMatch / lineIncidentMatch
ticker                     --> PushService.tick      -> LiveState + TwinQueries.upcomingIndexAt (arrival at your stop)
                           -> PushSender: WebPushSender (VAPID) | FcmSender (Android app)
```

Same incident rules as the REST API and the WebSocket (`TwinQueries`), so a notification never disagrees with what
the board shows.

### 8. Staff auth

```
POST /api/v1/auth/login -> AuthService -> Database (app_user, auth_token) -> bearer token + HttpOnly cookie
staffOnly { ... } (Ktor bearer auth) and staffPages(...) (redirect to /login.html) -> AuthService.principal(token)
```

## Frontends: static pages and one React app

| Frontend | Where | Stack | Why this stack |
|---|---|---|---|
| Passenger view `/`, staff panel `/admin/`, login, Trip Watch, station QR | `src/main/resources/static/` | Plain HTML + ES modules, MapLibre from a CDN, no build | Data views and forms over our REST/WebSocket API: served as they are by Ktor, no toolchain, load fast on a phone, editable during the hack without a rebuild |
| Voice travel assistant `/assistant/` | `client/` | React + TypeScript + Vite, built by Gradle into the jar | Built first as a separate app (sbb-client) and migrated in as is; it needs npm packages (ElevenLabs voice SDK), long-lived conversation state and typed contracts with `POST /api/v1/assistant/ask` |

The split is by route (one URL = one frontend, no shared JS at runtime). They meet only at three points: the
backend API (the contract both consume), the shared `/theme.css` (fonts, palette, light / dark) and an iframe: the
passenger view's floating button (`assistant-launcher.js`) opens `/assistant/` in a panel. The Android/iOS shell
(Capacitor) loads the same deployed pages.

Costs to keep in mind: two map integrations (MapLibre 5 from the CDN, MapLibre 6 from npm), small formatting
helpers written twice (JS and TS), Node needed to build the jar (or `-PskipClient=true`). If the project continues,
the natural next step is one Vite build with several entry points (passenger, staff, assistant) sharing components,
or keeping the static pages and extracting the shared pieces (map style, formatting) into `theme.css` / one JS module.

## Threads

| Thread | Does | Must not |
|---|---|---|
| Paho callback (1) | All MQTT handling, `LiveState` writes | Block (DB writes are queued) |
| `sqlite-writer` (1) | Every SQLite write, then incident listeners | Block in a listener (`LiveUpdates` uses `tryEmit`) |
| `mqtt-reconcile` (1) | First connect retries, reconcile after reconnect | |
| Ktor/Netty + coroutines | HTTP, WebSockets, assistant, Trip Watch worker | Hold the SQLite lock for long |

`LiveState` uses concurrent maps and immutable values, so readers never lock.

## Known deviations (to fix)

- `db` depends on `state.Station` (persistence importing the in-memory model), and `Database` holds five unrelated
  aggregates (incidents, stations, reports, users, tokens): to split into repositories.
- Staff reports: validation and the resolve flow are in `api/v1/Reports.kt`, and the route calls `Database`
  directly. They should move to a `ReportService`.
- `passenger` depends on `web.FleetFeed` for `/api/fleet`.
- The assistant reads staff reports and raw payloads straight from `Database`.
- `wallet` keeps its own SQLite file (`TripWatchStore`) while `push` shares the main one (`PushStore` over
  `Database.withConnection`): pick one convention when `Database` is split into repositories (the shared-connection
  repository of `push` is the target shape).
