# Takt Application

Kotlin Ktor backend for the LauzHack 2026 Swiss-train digital twin: consumes the sbb-twin MQTT feed, stores state and
history in SQLite, receives predictions over REST and pushes live updates to browsers over WebSocket.

- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- MQTT data source: [docs/DATA_SOURCE.md](docs/DATA_SOURCE.md)
- REST API: [docs/API.md](docs/API.md)
- Wallet disruption alerts: [docs/WALLET_TRIP_WATCH.md](docs/WALLET_TRIP_WATCH.md)
- Docker / deployment: [docker/README.md](docker/README.md)
- Contributor/agent notes: [CLAUDE.md](CLAUDE.md)

```sh
./gradlew test
./gradlew run   # GET http://localhost:8080/api/v1/status
```

Requires JDK 21.
