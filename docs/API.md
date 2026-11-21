# REST API v1

Read API over the live twin data ingested from MQTT, plus staff incident reports (the only writes). Base path
**`/api/v1`**, JSON only. A live fleet WebSocket and the staff web UI are described at the end.
Implementation: `src/main/kotlin/ch/lauzhack/twin/api/v1/` (`Routes.kt` routing + params, `TwinQueries.kt` logic,
`Dto.kt` response types). **Swagger UI: `/swagger`** (spec `src/main/resources/openapi/documentation.yaml`,
written by hand: keep it in sync with this file). Tests: `src/test/kotlin/ch/lauzhack/twin/ApiTest.kt`.

## Conventions

- **Times** are epoch milliseconds UTC (Swiss local time = `Europe/Zurich`). **Delays** are seconds, `+` = late.
- **Ids** are the feed's ids: `trainId` = `{YYYYMMDD}_{GTFS trip_id without leading dot}` (contains dots, URL-safe);
  `stopId` = 7-digit UIC/DIDOK station id (e.g. `8503000` Zürich HB, `8507000` Bern).
- `null` fields are always present (explicit nulls), so clients can rely on the shape.
- Only **trains already in service** are known: the twin publishes a train from the moment its run starts. A station
  board therefore never shows trains that have not departed from their origin yet.
- "Now" for windows is the twin clock (last `fleet` tick), not the server clock.
- Errors: HTTP status + `{"error": {"code": "train_not_found", "message": "..."}}`.
  Codes: `<what>_not_found` (404), `position_not_found` (404), `not_found` (unknown endpoint, 404),
  `bad_<param>` (400, e.g. `bad_limit`, `bad_bbox`, `bad_type`), `internal` (500).
- Bounding boxes: `bbox=minLon,minLat,maxLon,maxLat` (WGS84).

## Endpoints

| Endpoint | Returns |
|---|---|
| `GET /status` | `Status`: ingest health + twin heartbeat |
| `GET /trains` | `Page<TrainSummary>` |
| `GET /trains/{trainId}` | `TrainDetail`: summary + all stops + incidents affecting it |
| `GET /trains/{trainId}/position` | `Position` |
| `GET /trains/{trainId}/stops` | `Stop[]` |
| `GET /trains/{trainId}/incidents` | `Incident[]` (with `match`) |
| `GET /stations` | `Station[]` |
| `GET /stations/{stopId}` | `StationDetail` |
| `GET /stations/{stopId}/board` | `Board`: trains that will still call at / pass through the station |
| `GET /stations/{stopId}/incidents` | `Incident[]` (with `match`) |
| `GET /trains/nearby` | `NearbyTrain[]`: trains near a phone location, best match first (passenger view) |
| `GET /stations/nearby` | `NearbyStation[]`: stations near a location, nearest first |
| `GET /stations/{stopId}/destinations` | `Destination[]`: stations reachable from here with a train in service, most served first |
| `GET /stations/{stopId}/connections` | `Connections`: trains in service going from this station to `?to=`, by departure |
| `GET /stations/{stopId}/screen` | `Screen`: compact view for small displays (next departures + related incidents) |
| `GET /incidents` | `Incident[]` |
| `GET /incidents/{id}` | `Incident` with `geometry` (GeoJSON) |
| `GET /incidents/{id}/history` | `IncidentEvent[]` |
| `GET /reports` | `Report[]`: staff reports, newest first |
| `POST /reports` | **staff only** (see "Staff authentication"): creates a `Report` from a `CreateReport` body → `201`; `reporter` defaults to the staff username; `401 unauthorized` without a session |
| `GET /reports/{id}` | `Report` |
| `POST /reports/{id}/resolve` | `{ "resolution": "text" \| null }` (optional body) → resolved `Report`; `409 already_resolved` if not open |

### Query parameters

| Endpoint | Parameter | Default | Meaning |
|---|---|---|---|
| `/trains` | `line` | | e.g. `IC1`, `S3` (case-insensitive) |
| | `category` | | e.g. `IC`, `S`, `RE` |
| | `status` | | `active`, `cancelled`, `unknown` (position known, info not yet) |
| | `stationId` | | only trains that will still call at / pass through this station |
| | `atStation` | | `true` = standing at a station, `false` = on the line |
| | `minDelaySec` | | live delay ≥ this |
| | `bbox` | | position inside the box |
| | `limit`, `offset` | 100 (max 2000), 0 | paging; sorted by `trainId` |
| `/trains/{id}/stops` | `state` | | `passed`, `current`, `next`; `upcoming` = everything not passed |
| `/stations` | `q` | | substring of the name, or exact `stopId`; prefix matches first |
| | `bbox` | | |
| | `limit` | 50 (max 5000) | |
| `/stations/{id}/board` | `type` | `departures` | `departures` (excludes trains ending here), `arrivals` (excludes trains starting here), `all` |
| | `windowMin` | 120 (1…1440) | only entries whose time ≤ now + window (trains standing at the station always included) |
| | `limit` | 50 (max 500) | |
| | `includeSkipped` | `true` | include trains that pass through without stopping (`stop.skipped = true`) |
| `/trains/nearby` | `lat`, `lon` | required | the phone's fix (WGS84) |
| | `radiusM` | 2000 (10…20000) | |
| | `headingDeg`, `speedKmh` | | the phone's heading / speed: trains going another way or speed are ranked down |
| | `ts` | | fix time (epoch ms): trains are moved along their bearing to that time (±15 s) |
| | `limit` | 5 (max 20) | |
| `/stations/nearby` | `lat`, `lon` | required | |
| | `radiusM`, `limit` | 5000 (max 50000), 8 (max 50) | |
| `/stations/{id}/destinations` | `q`, `limit` | –, 30 (max 500) | name filter |
| `/stations/{id}/connections` | `to` | required | destination `stopId` |
| | `windowMin`, `limit` | 180 (1…1440), 10 (max 100) | departures within the window (trains at the station always included) |
| `/stations/{id}/screen` | `limit` | 6 (max 20) | number of departures |
| | `windowMin` | 120 (1…1440) | departures window |
| `/incidents` | `all` | `false` | `true` = also removed incidents |
| | `severity` | | `info`, `minor`, `major`, `critical` |
| | `scopeKind` | | `section`, `station`, `train`, `line`, `network` |
| | `geometry` | `false` | `true` = include each incident's GeoJSON `geometry` (~12 KB each) |
| `/reports` | `status` | `open` | `open`, `resolved`, `all` |

## Types

```jsonc
// TrainSummary
{ "trainId": "20261121_ojp-91-1-D.1.TA.2207.j26", "line": "IC1", "category": "IC", "number": "2207",
  "operator": "Schweizerische Bundesbahnen SBB", "headsign": "Fribourg/Freiburg",
  "status": "active",                      // active | cancelled | unknown
  "origin": { "stopId": "8506302", "name": "St. Gallen" }, "destination": { "stopId": "8504100", "name": "Fribourg/Freiburg" },
  "delaySec": -3,                          // live delay at the next stop
  "position": Position | null,
  "currentStop": { "stopId": "...", "name": "..." } | null,   // standing at this station
  "nextStop": Stop | null,                 // next stop to reach (null at terminus)
  "infoUpdatedAt": 1795294200000 }

// Position
{ "lat": 47.38, "lon": 8.02, "bearing": 60.0, "speedKmh": 128.6, "motion": "moving", // moving | stopped
  "atStopId": null, "continuity": 1, "ts": 1795294228000 }  // don't interpolate across a continuity change

// Stop (one call of a run)
{ "seq": 9, "stopId": "8507000", "name": "Bern", "lat": 46.95, "lon": 7.44,
  "plannedArr": ..., "plannedDep": ..., "expectedArr": ..., "expectedDep": ..., "actualArr": null, "actualDep": null,
  "arrDelaySec": 30, "depDelaySec": 0, "skipped": false,
  "state": "next" }                        // passed | current (standing there) | next | upcoming

// TrainDetail
{ "train": TrainSummary, "stops": Stop[], "incidents": Incident[] }

// Station / StationDetail
{ "stopId": "8507000", "name": "Bern", "lat": 46.948831, "lon": 7.439129 }
{ "station": Station, "trainsPresent": 3, "trainsUpcoming": 62, "incidents": Incident[] }

// Board
{ "station": Station, "type": "departures", "now": 1795294228000, "windowMin": 120,
  "entries": [ { "trainId": "...", "line": "EC", "category": "EC", "number": "66", "operator": "...",
                 "headsign": "Basel SBB", "status": "active", "origin": {...}, "destination": {...},
                 "stop": Stop,               // the train's call at this station
                 "time": 1795294440000,      // expected (else planned) dep/arr depending on type; sort key
                 "plannedTime": 1795293150000, "delaySec": 1290,
                 "atStation": true, "startsHere": false, "terminatesHere": false } ] }
// sorted: trains standing at the station first, then by time

// Incident
{ "id": "i-4d5811f3", "title": "Overhead line fault: Zürich HB – Winterthur Hegi", "description": "",
  "severity": "critical", "type": "overhead_line_fault", "reasonCode": "EG_008",
  "scope": { "kind": "section", "from": "8503000", "to": "8516218", "stopId": null, "trainId": null, "line": null },
  "where": "Zürich HB – Winterthur Hegi", "disruptionId": "d-9afbd339",
  "status": "active", "active": true, "createdAt": ..., "updatedAt": ..., "firstSeenAt": ..., "removedAt": null,
  "match": "route",    // only on /trains/{id}/incidents, /trains/{id}, /stations/{id}[/incidents]
  "geometry": null }   // GeoJSON only on /incidents/{id}

// Screen (GET /stations/{id}/screen and WebSocket topic `screen`): only what a departure screen draws, ~1 KB.
// Departures = board type=departures without skipped trains; incidents = station-subscription matching, worst first (max 5).
{ "stopId": "8501120", "name": "Lausanne", "now": 1795294228000,
  "departures": [ { "plannedTime": 1795294440000, "delaySec": 120, "line": "IC1", "category": "IC",
                    "to": "St. Gallen", "cancelled": false, "atStation": false } ],
  "incidents": [ { "id": "i-4d5811f3", "severity": "major", "title": "Overhead line fault: Lausanne – Fribourg",
                   "where": "Lausanne – Fribourg", "atStation": true, "trains": 3, "updatedAt": 1795294200000 } ] }

// IncidentEvent (history, oldest first)
{ "seq": 1, "kind": "created", "receivedAt": ..., "updatedAt": ..., "status": "active", "severity": "critical" }
// kind: created | updated | removed (tombstone) | missing (unmarked while we were offline)

// Status
{ "now": ..., "mqttConnected": true, "trains": 682, "positions": 682, "stations": 2108, "lastFleetTs": ...,
  "twin": { "streamId": "0105a1461f934dee", "ts": ..., "tick": 70, "trainsActive": 682, "trainsSimulated": 30,
            "timetableDate": "2026-11-21", "online": true } }  // online = heartbeat ≤ 30 s old

// CreateReport (POST /reports body). Staff reports live in our SQLite only; nothing is published to the twin.
{ "title": "Door fault on coach 5",       // required, ≤ 200 chars
  "description": "",                       // ≤ 4000 chars
  "category": "rolling_stock",             // rolling_stock | infrastructure | signalling | station | person | obstacle | weather | other (default)
  "severity": "minor",                     // info | minor (default) | major | critical
  "scope": "train",                        // station | section | train | point
  "stopId": null, "toStopId": null,        // station: stopId; section: stopId + toStopId (known stations)
  "trainId": "20261121_…",                 // train: a train in service
  "lat": null, "lon": null,                // point: required; train: optional (default = its live position)
  "reporter": "Driver RE 3456" }           // optional, ≤ 100 chars

// Report
{ "id": "r-0dfc887a", "title": "...", "description": "...", "category": "rolling_stock", "severity": "minor",
  "scope": "train", "stopId": null, "toStopId": null, "trainId": "20261121_…", "line": "IC1",
  "where": "IC1 737 → Zürich HB",          // Bern | Bern – Thun | IC1 737 → Zürich HB | 46.94800, 7.44700
  "lat": 46.945, "lon": 7.407,             // station / section midpoint / train position at report time / point
  "reporter": "...", "status": "open",     // open | resolved
  "createdAt": ..., "updatedAt": ..., "resolvedAt": null, "resolution": null }
// errors: bad_title, bad_description, bad_category, bad_severity, bad_scope, bad_stopId, bad_toStopId, bad_trainId,
//         bad_lat, bad_lon (400); station_not_found, train_not_found (404); bad_request (malformed JSON, 400)
```

```jsonc
// NearbyTrain — score = distance + heading/speed mismatch penalties (metres, lower = better)
{ "train": TrainSummary, "distanceM": 42, "headingDiffDeg": 3, "speedDiffKmh": 5, "score": 72 }
// NearbyStation / Destination
{ "station": Station, "distanceM": 120, "trainsUpcoming": 27 }
{ "station": Station, "trains": 4, "nextArrival": 1795300740000 }
// Connections
{ "from": Station, "to": Station, "now": ..., "windowMin": 180,
  "entries": [ { "train": TrainSummary, "departure": Stop, "arrival": Stop, "departureTime": ..., "arrivalTime": ...,
                 "plannedDepartureTime": ..., "durationMin": 32, "stopsBetween": 3, "atStation": false, "incidents": Incident[] } ] }
```

### How incidents are matched (`match`)

| Scope | Train | Station |
|---|---|---|
| `train` | `train`: targets this train | — |
| `section` | `route`: both ends among the previous + remaining stops (runs through it); `endpoint`: calls at one end | `endpoint`: the station is one end |
| `station` | `station`: the train will still call there | `station` |
| `line` | `line`: same line | — |
| `network` | `network` | `network` |

## WebSocket `/api/v1/ws` (live subscriptions)

One connection, many subscriptions. Client -> server (JSON text frames):

```jsonc
{ "op": "subscribe", "topic": "train",   "id": "<trainId>", "frequencySec": 5 }
{ "op": "subscribe", "topic": "station", "id": "8507000",   "frequencySec": 10, "windowMin": 60 }
{ "op": "subscribe", "topic": "screen",  "id": "8501120",   "frequencySec": 30, "limit": 6 }
{ "op": "unsubscribe", "topic": "train", "id": "<trainId>" }
{ "op": "ping" }
```

- `frequencySec` (1…3600, default 5) = period of the `*_update` messages of **that** subscription.
- **Incident notifications are pushed immediately**, whatever the frequency.
- Subscribing again to the same `topic`+`id` replaces the subscription (e.g. to change the frequency). Max 100 per connection.
- Station only: `windowMin` (1…1440, default 120) = board window, like `/stations/{id}/board?type=all`.
- Screen only (for small displays such as an ESP32 with a TFT): `limit` (1…20, default 6) departures, `windowMin` as
  above. The server sends one compact `screen_update` (`Screen`) on subscribe, every `frequencySec`, and **again
  immediately when an incident is created / updated / removed**; no separate `incident` messages.

Server -> client (every message has `type`):

| `type` | When | Payload |
|---|---|---|
| `subscribed` / `unsubscribed` | ack | `topic`, `id`, `frequencySec`, `windowMin` |
| `train_update` | every `frequencySec` | `data`: `{ train: TrainSummary, stops: Stop[] }` |
| `station_update` | every `frequencySec` | `data`: `Board` (`type = all`, skipped trains included, max 500 entries) |
| `screen_update` | on subscribe, every `frequencySec` and on every incident change | `data`: `Screen` |
| `train_ended` | the train left service | subscription closed by the server |
| `incident` | on subscribe (`event: active`, one per active related incident), then on every change (`created`, `updated`, `removed`, `missing`) | `incident`: `Incident`, `trains`: `[{trainId, match}]` |
| `error` | bad request | `code` (`bad_request`, `bad_op`, `bad_topic`, `bad_id`, `bad_frequencySec`, `bad_windowMin`, `bad_limit`, `too_many_subscriptions`, `train_not_found`, `station_not_found`), `message`, `topic`, `id` |
| `pong` | reply to `ping` | `ts` |

Related incidents (same rules as the table above):
- **train**: incidents matching the train; `incident.match` = the relation, `trains` = `[{trainId, match}]`.
- **station**: incidents at the station itself (`incident.match` = `endpoint|station|network`, else `null`) **plus**
  incidents of any train that will still call at / pass the station (listed in `trains` with their `match`).

```jsonc
{ "type": "incident", "topic": "station", "id": "8503000", "event": "created", "ts": 1795294228000,
  "incident": { "id": "i-4d5811f3", ..., "match": "endpoint" },
  "trains": [ { "trainId": "20261121_...", "match": "route" } ] }
```

## Examples

```sh
curl localhost:8080/api/v1/stations?q=bern                          # find a stopId
curl localhost:8080/api/v1/stations/8507000/board?windowMin=60       # next departures in Bern
curl "localhost:8080/api/v1/stations/8503000/board?type=arrivals&includeSkipped=false"
curl localhost:8080/api/v1/trains?stationId=8503000&minDelaySec=300  # late trains heading to Zürich HB
curl localhost:8080/api/v1/trains/20261121_ojp-91-1-D.1.TA.2207.j26 # one train, all stops, incidents
curl "localhost:8080/api/v1/trains?bbox=8.4,47.3,8.6,47.45"          # trains around Zürich
curl localhost:8080/api/v1/incidents/i-4d5811f3/history
curl "localhost:8080/api/v1/stations/8501120/screen?limit=6"         # compact view for a small display
websocat ws://localhost:8080/api/v1/ws   # then: {"op":"subscribe","topic":"station","id":"8507000","frequencySec":5}
```

## Live fleet WebSocket

`GET /ws/fleet` (WebSocket, server push only): one text frame per `fleet` tick (~1 Hz, ~45 KB for ~700 trains),
the same row layout as the sbb-twin viewer:

```jsonc
{ "type": "tick", "ts": 1795296058000,
  "trains": [ ["20261121_ojp-91-1-D.1.TA.682.j26", 46.945, 7.407, 60.0, 128.6, "moving", 30, "IC1", "IC", null, 1], … ] }
// [trainId, lat, lon, bearing, speedKmh, motion, delaySec, line, category, atStopId, continuity]
```

## Staff authentication

The read endpoints, the WebSockets and Swagger are **public**. Staff-only: **the staff web UI** (`/`, `/index.html`,
`/report.html` redirect to `/login.html` without a session), **`POST /reports`** (create a report) and the calls below. Every account has role `staff`. On first start, when there is no user, the app creates
**`admin` / `admin`** (password from env `ADMIN_PASSWORD`, default `admin`): change it with
`PUT /staff/users/admin/password`.

1. `POST /auth/login` `{"username":"admin","password":"admin"}` -> `{"token": "...", "tokenType": "Bearer", "expiresAt": ..., "user": User}`
2. Send `Authorization: Bearer <token>` on staff-only calls. Browsers: login also sets the `twin_staff` cookie (same token,
   HttpOnly, SameSite=Strict), which the staff pages and `POST /reports` accept too; logout clears it. Tokens last `AUTH_TOKEN_TTL_HOURS` (12 h), survive restarts
   (only their SHA-256 is stored) and end with `POST /auth/logout`.

| Endpoint | Auth | Returns |
|---|---|---|
| `POST /auth/login` | public | `LoginResult`; `401 invalid_credentials` |
| `GET /auth/me` | staff | `User` |
| `POST /auth/logout` | staff | `204` (revokes this token) |
| `GET /staff/users` | staff | `User[]` |
| `POST /staff/users` `{"username","password"}` | staff | `201 User`; `409 user_exists`, `400 bad_username` (2-32 of `A-Za-z0-9._-`), `400 bad_password` (4-200 chars) |
| `PUT /staff/users/{username}/password` `{"password"}` | staff | `204`; revokes that user's other sessions |
| `DELETE /staff/users/{username}` | staff | `204` (and its tokens); `409 cannot_delete_self` |

`User` = `{"username": "admin", "role": "staff", "createdAt": ..., "updatedAt": ...}`.
Missing / invalid / expired token on a staff call -> `401 {"error":{"code":"unauthorized",...}}`.
Passwords: PBKDF2-HMAC-SHA256, 120k iterations, per-user salt.

```sh
TOKEN=$(curl -s -XPOST localhost:8080/api/v1/auth/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/staff/users
```

## Web UI

All pages follow [`docs/STYLE.md`](STYLE.md) (SBB look: SBB Web font, SBB palette, flat, Lucide icons).

- `/` (public): **passenger view**, mobile first (`index.html`, `passenger.js`, `passenger.css`):
  - QR codes: `/?train=<trainId>` opens "on tracks" for that train; `/?station=<stopId>` asks for the destination
    (`&to=<stopId>` goes straight to the trains between both).
  - Location: a train within 200 m (`/trains/nearby`, with the phone's speed/heading) -> "Are you on this train?"
    (Yes / No / other trains nearby); else a station within 200 m (`/stations/nearby`) -> "Are you at X?"; else, or
    on No, "Plan a trip": origin and destination by text or on the map.
  - On tracks: a Three.js scene (always the same train on the same track; it moves with the real speed), the whole
    route with the ETA of every stop and where the train is, incidents on top. Live over the WebSocket `train` topic.
  - At a station: destination by text or map (reachable stations from `/stations/{id}/destinations` highlighted),
    then `/stations/{id}/connections?to=`; tap a train to follow it on tracks.

## Staff web UI

Static files in `src/main/resources/static/` (no build step; MapLibre GL from unpkg, swisstopo base map).
Rail network as in the sbb-twin viewer: `static/data/network-lines.geojson` (Swiss lines, © BAV) and
`static/data/abroad-lines.geojson` (© OpenStreetMap contributors), copied from the sbb-twin repository `data/`
(refresh them from there if the twin's network changes). Light/dark toggle on every page (saved in `localStorage`
`twin-theme`, default = system preference); the maps are recoloured in place (`common.js` `applyTheme`).
Responses are gzip-compressed (Ktor `Compression`).

- `/login.html` (public): staff login; `?next=/admin/report.html` returns there after login (default `/admin/`). Pages show the user + "Log out".
- `/admin/`: staff overview map (stripped-down sbb-twin viewer): live trains (`/ws/fleet`), stations, twin incidents,
  open staff reports. Click a train for its stops; click a station for departures; right-click to report at a point.
- `/admin/report.html` (old `/report.html` redirects here): report form (station / section / train / map point) and the list of reports (resolve them).
  Accepts `?scope=station&stopId=…`, `?scope=train&trainId=…`, `?scope=point&lat=…&lon=…` to pre-fill.
