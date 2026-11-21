# REST API v1

Read API over the live twin data ingested from MQTT. Base path **`/api/v1`**, JSON only, all `GET`.
Implementation: `src/main/kotlin/ch/lauzhack/twin/api/v1/` (`Routes.kt` routing + params, `TwinQueries.kt` logic,
`Dto.kt` response types). Tests: `src/test/kotlin/ch/lauzhack/twin/ApiTest.kt`.

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
| `GET /incidents` | `Incident[]` |
| `GET /incidents/{id}` | `Incident` with `geometry` (GeoJSON) |
| `GET /incidents/{id}/history` | `IncidentEvent[]` |

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
| `/incidents` | `all` | `false` | `true` = also removed incidents |
| | `severity` | | `info`, `minor`, `major`, `critical` |
| | `scopeKind` | | `section`, `station`, `train`, `line`, `network` |

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

// IncidentEvent (history, oldest first)
{ "seq": 1, "kind": "created", "receivedAt": ..., "updatedAt": ..., "status": "active", "severity": "critical" }
// kind: created | updated | removed (tombstone) | missing (unmarked while we were offline)

// Status
{ "now": ..., "mqttConnected": true, "trains": 682, "positions": 682, "stations": 2108, "lastFleetTs": ...,
  "twin": { "streamId": "0105a1461f934dee", "ts": ..., "tick": 70, "trainsActive": 682, "trainsSimulated": 30,
            "timetableDate": "2026-11-21", "online": true } }  // online = heartbeat ≤ 30 s old
```

### How incidents are matched (`match`)

| Scope | Train | Station |
|---|---|---|
| `train` | `train`: targets this train | — |
| `section` | `route`: both ends among the previous + remaining stops (runs through it); `endpoint`: calls at one end | `endpoint`: the station is one end |
| `station` | `station`: the train will still call there | `station` |
| `line` | `line`: same line | — |
| `network` | `network` | `network` |

## Examples

```sh
curl localhost:8080/api/v1/stations?q=bern                          # find a stopId
curl localhost:8080/api/v1/stations/8507000/board?windowMin=60       # next departures in Bern
curl "localhost:8080/api/v1/stations/8503000/board?type=arrivals&includeSkipped=false"
curl localhost:8080/api/v1/trains?stationId=8503000&minDelaySec=300  # late trains heading to Zürich HB
curl localhost:8080/api/v1/trains/20261121_ojp-91-1-D.1.TA.2207.j26 # one train, all stops, incidents
curl "localhost:8080/api/v1/trains?bbox=8.4,47.3,8.6,47.45"          # trains around Zürich
curl localhost:8080/api/v1/incidents/i-4d5811f3/history
```
