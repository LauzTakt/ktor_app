# Passenger voice assistant (`/assistant/`)

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

## How a question is answered (fast path first)

1. **Out of scope** (platforms, accessibility, fares, tickets, …): the agent says so at once (`src/scope.ts`).
2. **Preloaded brief**: when the passenger names a station, line or route, the app fetches `/api/brief`, draws it and
   the agent answers from it with no model call (0.1–0.4 s).
3. **Back office**: anything else goes to `ask_backend` -> `POST /api/v1/assistant/ask`; a soft hold pad plays while
   it waits (`src/hold.ts`), and the answer's `visuals` run on the map.

The look follows the twin's SBB style guide. Not an official SBB product.
