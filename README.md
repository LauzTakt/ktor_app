# ktor_app

Kotlin Ktor backend for the LauzHack 2026 Swiss-train digital twin: consumes the sbb-twin MQTT feed, stores state and
history in SQLite, receives predictions over REST and pushes live updates to browsers over WebSocket.

- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- MQTT data source: [docs/DATA_SOURCE.md](docs/DATA_SOURCE.md)
- Contributor/agent notes: [CLAUDE.md](CLAUDE.md)

```sh
./gradlew test
./gradlew run   # GET http://localhost:8080/health
```

Requires JDK 21.
