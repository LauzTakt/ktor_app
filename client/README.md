# Passenger voice assistant (`/assistant/`)

## Zürich HB boarding pilot

Open `/assistant/?replay=stettbach`, or follow **Replay the Stettbach disruption** from the assistant.
Choose **Wait for the S 9** and advance the bulletin twice. The 19:37 update identifies the 19:58
S 9 (train 18975) toward Uster. **Board this one** centers the map on the published tracks 43–44
and shows the access beside ZVV-Contact in Halle Landesmuseum. **Back to rail map** returns to the trip.
The other choice shows the tram 7 boarding stop on Bahnhofstrasse.

With the replay open, turn off the network. The station map and directions are bundled with the
replay. **Simulate track change** queues a sample update when offline. Reconnect and the card and
pin move to tracks 41–42 together. This correction is labelled as a simulation.

The replay uses the 25 September 2026 SBB Liveticker incident `2195005195846` and the September
challenge stop records. The 23 September GTFS timetable supplies the published platform group
`ch:1:sloid:3000:503:43` for trip `.ojp-91-9-D.1.TA.482.j26`. The historical stop record supplies
the observed Stettbach call. A live launch needs a current platform assignment for the exact service.
[Swiss timetable archive](https://data.opentransportdata.swiss/en/dataset/timetable-2026-gtfs2020) ·
[VBZ landmark](https://www.stadt-zuerich.ch/vbz/en/beratung-service/zvv-contact/service-centres.html) ·
[SBB station plan](https://cdnsource.sbb.ch/content/dam/infrastruktur/trafimage/bahnhofplaene/plan-zuerich-hb-a4.pdf.sbbdownload.pdf)

Basemaps © swisstopo, rendered from the [Light Base Map](https://docs.geo.admin.ch/visualize-data/vector-tiles.html).
Route alignments are from [Canton Zürich and ZVV](https://geolion.zh.ch/geodatensatz/2150.html).

Migrated from [`lauzhack2026/sbb-client`](https://github.com/lauzhack2026/sbb-client). A phone-first React app:
talk to a calm travel assistant (ElevenLabs agent on Claude Haiku), and the live map shows the route, the train that
is coming, the closed section and a countdown.

Its Node server is gone: **every server call is answered by ktor_app** (Kotlin, packages `passenger/`, `assistant/`,
`api/v1/`), from the data ktor_app ingests over MQTT. Nothing calls sbb-twin's HTTP API. The browser never sees a key.

| Call | ktor_app |
|---|---|
| `POST /api/v1/assistant/ask` | back office: Claude Sonnet agent with live tools (`assistant/`) |
| `GET /api/brief`, `/api/brief/active`, `/api/brief/item` | fast place brief, no model call (`passenger/`) |
| `GET /api/voice/session` | one-time ElevenLabs conversation credentials |
| `GET /api/fleet`, `/ws/fleet` | live trains |
| `GET /api/nearest` | nearest station to the passenger |
| `GET /api/v1/situation`, `/api/v1/stations/{id}/arrivals`, `/api/v1/trains/{id}` | station card, live arrivals and tracking, train card |
| `GET /api/v1/geometry/journey/{id}` | track of a journey listed by the assistant |
| `GET /data/network-lines.geojson` | rail network (BAV, static) |

## Build

Gradle builds it (`npm ci && npm run build` -> `build/client/static/assistant/`, packaged into the jar), so
`gradle run` / `installDist` / Docker include it. Node + npm must be on the PATH; `-PskipClient=true` skips it.

## Develop with hot reload

```sh
gradle run                      # ktor_app on :8080 (in another terminal)
cd client && npm ci && npm run dev   # http://127.0.0.1:5190/assistant/, proxies /api, /data, /ws to KTOR_URL
```

## Voice agent

```sh
npm run agent        # create or update the ElevenLabs agent (prompt, get_brief + ask_backend client tools)
npm run agent:chat -- "My IC to Bern was cancelled at Zürich HB"   # talk to it in text (APP_URL, default :8080)
```

Both read `ELEVENLABS_API_KEY` from `~/.config/lauzhack/.env` and save `PASSENGER_AGENT_ID` there. The server needs
both as environment variables (`ELEVENLABS_API_KEY`, `PASSENGER_AGENT_ID`); without them the app works in text only.

## Leave-by timer

Select a journey under **Your options**, open **Set leave-by timer**, and enter your walk to its boarding point in minutes. The assistant subtracts that walk and one boarding minute from the expected departure. The timer stays visible while you compare other options and follows timetable changes for the same journey. Open the time beneath the journey to edit or remove the timer. Until you enter a walk, the assistant shows the departure countdown.

## How a question is answered (fast path first)

1. **Out of scope** (platforms, accessibility, fares, tickets, …): the agent says so at once (`src/scope.ts`).
2. **Preloaded brief**: when the passenger names a station, line or route, the app fetches `/api/brief`, draws it and
   the agent answers from it with no model call (0.1–0.4 s).
3. **Back office**: anything else goes to `ask_backend` -> `POST /api/v1/assistant/ask`; a soft hold pad plays while
   it waits (`src/hold.ts`), and the answer's `visuals` run on the map.

The look follows the twin's SBB style guide. Not an official SBB product.
