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
 | query (TwinQueries, JourneyQueries, IncidentQueries, StationSearch,|  2. read side: pure functions over state + incidents
 |   Severity, Lines, FleetFeed, read models)   report (ReportService)|     domain services (own rules, no Ktor in them)
 +-----------------------------------------------------------------+
            |                    |                         |
            v                    v                         v
 +-------------------+  +-------------------+  +--------------------------+
 | state (LiveState) |  | db (Sqlite +      |  | twinapi (SbbTwinClient,  |    3. data sources
 | memory, 1 Hz      |  |  repositories)    |  |  JourneyCache)           |
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
| `model` | MQTT payload shapes (`TrainInfo`, `TrainPosition`, `Incident`, `TwinStatus`) and `Station` | — |
| `common` | Domain errors (`AppException` and subclasses), geo (`haversineM`, `distanceKm`, `move`), GeoJSON (`forEachCoord`, `coordsOf`, `BBoxBuilder`, `simplifyGeometry`), Swiss time (`ZURICH`, `hhmm`, `dayTime`, `dayMonth`, `delayMin`, `minutes`), loose JSON access (`str`, `obj`, `objects`, `geometry`, `truthy`, `opt`, `toPlain`, `toJsonElement`, …), text (`searchKey` for accent-free station search, `lineKey`, `spacedLine`, `cleanDashes`, `urlEncode`) | — |
| `state` | `LiveState`: latest train infos, fleet positions, stop index, station catalog. Memory only | `model` |
| `db` | `Sqlite`: one connection, the `sqlite-writer` thread (`submit`), `withConnection`, `transaction`, `migrate`. One repository per aggregate, each owning its tables: `IncidentRepository` (incidents + history + change listeners), `StationRepository`, `ReportRepository`, `UserRepository`, `TokenRepository`. `Database` = the main file and its repositories | `model`, `common` |
| `mqtt` | `MqttIngestor`: subscribes to sbb-twin and writes into `state` and `db` | `model`, `state`, `db` |
| `twinapi` | `SbbTwinClient`: the only HTTP client for sbb-twin (GET only). `JourneyCache`: planner journeys shared by the features | `common` |
| `query` | Read side: `TwinQueries` (trains, stations, boards, screens, incident matching), `JourneyQueries` (nearby, destinations, connections), `IncidentQueries`; `Severity` (rank, colour, wording of incident severities) and `Lines.kt` (`sameLine`, `lineIn`, `mentionsLine`, `TrainState.line`): the one place for these rules, and `StationSearch` (name normalisation, aliases, to/from words, ranking) behind every station search: REST, `assistant.StationResolver`, `passenger.Places`; `FleetFeed` (the fleet encoded once per tick); `ReadModels.kt` = the response types, which are also the public API v1 contract | `common`, `model`, `state`, `db` |
| `report` | `ReportService`: staff incident reports (validation, location from the live state, atomic open -> resolved). Used by the `/api/v1/reports` routes and the assistant | `common`, `model`, `state`, `db`, `query` |
| `api/v1` | REST `/api/v1/*` routes, `ScreenText`, staff report routes (over `ReportService`), WebSocket hub `LiveUpdates`, HTTP mapping of domain errors (`Errors.kt`) | `query`, `report`, `common`, `auth`, `db` |
| `auth` | Staff accounts, bearer tokens + session cookie, `staffOnly { }`, staff pages (`AuthService` over `UserRepository` + `TokenRepository`) | `db`, `common` |
| `assistant` | Passenger assistant: Claude agent loop (`AssistantAgent`), tools (`AssistantTools`), `StationResolver`, `POST /api/v1/assistant/ask`. Staff reports through `ReportService`, raw incident payloads through `IncidentQueries` | `query`, `report`, `state`, `twinapi`, `common`, `model` |
| `passenger` | Server side of the voice app: ElevenLabs session, place brief, nearest station, fleet snapshot, read-only `/twin/*` proxy | `twinapi`, `query`, `state`, `common` |
| `wallet` | Trip Watch: Apple / Google Wallet passes kept up to date for a planned journey (own SQLite file, opened with `Sqlite.open`) | `query`, `twinapi`, `db`, `common` |
| `push` | Push notifications (Web Push + FCM for the Android app): follow a train (arrival at your stop + incidents) or a line (incidents). `PushStore` = its tables in the main SQLite file (through `Sqlite.withConnection` / `transaction`), `Senders` = Web Push / FCM HTTP clients | `query`, `state`, `db`, `common` |
| `web` | `/ws/fleet`: `query.FleetFeed` pushed once per tick to the staff map; `AppLinks`: `/.well-known/` files for mobile deep links (env config, no data) | `query` |

### Dependency rules

Rules 1 and 2 and the "May depend on" column of the lower layers are checked by `src/test/.../ArchitectureTest.kt`.


1. **Nothing depends on a delivery package.** `api/v1`, `auth`, `assistant`, `passenger`, `wallet`, `push` and `web`
   are leaves (only `auth`'s `staffOnly { }` is shared). A feature that needs another feature's data reads it from `query`, `state`, `db` or `twinapi`, never from
   the other feature's package. Example: the assistant, the passenger app and Trip Watch share planned journeys
   through `twinapi.JourneyCache`, not through each other.
2. **No HTTP below the routes.** Queries and services throw `common` errors (`NotFoundException`,
   `InvalidInputException`, `ConflictException`, `UnauthorizedException`, `UnavailableException`) with a stable
   `code`. Only `api/v1/Errors.kt` (`installApiErrors`) maps them to an HTTP status and the
   `{"error":{code,message}}` body.
3. **One way to reach sbb-twin**: `twinapi.SbbTwinClient`. Every failure is a `TwinException` (an `IOException`:
   status 0 = unreachable, otherwise the HTTP status), so callers handle "twin down" in one place.
4. **Writes go through one path.** Train state is written only by `MqttIngestor` (MQTT callback thread). SQLite is
   written by `Sqlite`'s single writer thread; routes, the assistant and Trip Watch only read or call repository
   methods that queue writes.
5. **Shared helpers live in `common`**, not in a feature. Before adding a JSON accessor, a time format, a distance or
   a name normalisation to a feature, check `common`.

## Runtime interactions

### 1. Ingest (MQTT -> memory + SQLite)

```
broker --status, trains/+/info, incidents/+, fleet--> MqttIngestor (Paho callback thread)
   info      -> LiveState.upsertInfo()  -> new stations -> StationRepository.save()    (queued)
   info ""   -> LiveState.removeInfo()                                               (tombstone)
   fleet     -> LiveState.replacePositions()  (one immutable map per second)
   incident  -> IncidentRepository.upsert() / remove()                               (queued)
   status    -> LiveState.updateStatus()
reconnect + 15 s -> IncidentRepository.reconcile(retained ids)   (incidents unmarked while offline -> `missing`)
```

At startup `Application.module()` builds `LiveState(db.stations())`, so the station catalog is complete before the
first MQTT message; trains come back within seconds from the retained `info` topics.

### 2. Read API (REST)

```
GET /api/v1/...  -> api/v1/Routes.kt (params, limits)  -> TwinQueries / JourneyQueries / IncidentQueries
                                                            |-> LiveState (trains, positions, stations)
                                                            |-> IncidentRepository.list(activeOnly) (via a lambda)
                 <- read model (ReadModels.kt) serialized as JSON, or AppException -> installApiErrors
```

`TwinQueries` receives the active incidents as `() -> List<IncidentRow>`, which keeps it a pure function of its
inputs in tests. `/stations/{id}/screen.txt` renders the same `ScreenDto` as text (`ScreenText`, ETag + 304).

### 3. Live updates (WebSocket)

```
sqlite-writer thread --incident change--> IncidentRepository listeners:
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
                  -> ReportService (open staff reports), IncidentQueries (raw incident payloads)
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
worker loop: refresh on every incident change (IncidentRepository listener) or every 20 s
```

### 7. Push notifications

```
pushV1 (/api/v1/push/...) -> PushService -> PushStore (devices, subscriptions, notified incidents; shared SQLite)
IncidentRepository listener --> PushService.onIncident -> TwinQueries.trainIncidentMatch / lineIncidentMatch
ticker                     --> PushService.tick      -> LiveState + TwinQueries.upcomingIndexAt (arrival at your stop)
                           -> PushSender: WebPushSender (VAPID) | FcmSender (Android app)
```

Same incident rules as the REST API and the WebSocket (`TwinQueries`), so a notification never disagrees with what
the board shows.

### 8. Staff auth

```
POST /api/v1/auth/login -> AuthService -> UserRepository + TokenRepository (app_user, auth_token) -> bearer token + HttpOnly cookie
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

## Deliberate choices

- `wallet` keeps its own SQLite file (`TripWatchStore`, opened with `Sqlite.open`), while every other table lives in
  the main file: wallet passes already issued must survive independently of the main database.
- `client/` (React) and `static/` repeat a few small pieces (the Loki drawing, the map dark theme, train colours):
  Docker builds `client/` on its own, so it cannot import from `static/`. Change both together.
