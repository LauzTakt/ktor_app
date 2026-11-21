package ch.lauzhack.twin

import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.api.v1.apiV1
import ch.lauzhack.twin.api.v1.installApiErrors
import ch.lauzhack.twin.api.v1.reportsV1
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.mqtt.MqttConfig
import ch.lauzhack.twin.mqtt.MqttIngestor
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.web.FleetFeed
import ch.lauzhack.twin.web.fleetSocket
import io.ktor.server.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Health(val status: String)

/** Entry point referenced from application.yaml (ktor.application.modules). */
fun Application.module() {
    val conf = environment.config
    val state = LiveState()
    val db = Database(conf.propertyOrNull("db.path")?.getString() ?: "data/twin.sqlite")

    val mqttEnabled = conf.propertyOrNull("mqtt.enabled")?.getString()?.toBooleanStrictOrNull() ?: true
    val ingestor = if (mqttEnabled) {
        MqttIngestor(
            MqttConfig(
                brokerUrl = conf.property("mqtt.brokerUrl").getString(),
                username = conf.propertyOrNull("mqtt.username")?.getString(),
                password = conf.propertyOrNull("mqtt.password")?.getString(),
                clientIdPrefix = conf.propertyOrNull("mqtt.clientIdPrefix")?.getString() ?: "ktor-app",
            ),
            state, db,
        ).also { it.start() }
    } else null

    monitor.subscribe(ApplicationStopped) {
        ingestor?.close()
        db.close()
    }

    configureHttp(state, db) { ingestor?.isConnected ?: false }
}

/** HTTP layer only (plugins + routes); split from [module] so tests can inject a seeded [LiveState]. */
fun Application.configureHttp(state: LiveState, db: Database, mqttConnected: () -> Boolean) {
    install(CallLogging)
    install(Compression) // gzip/deflate: the rail network GeoJSON (~3 MB) and API lists shrink ~5x
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    installApiErrors()
    install(WebSockets) { pingPeriod = 15.seconds }
    val fleet = FleetFeed(state)
    routing {
        get("/health") { call.respond(Health("ok")) }
        apiV1(TwinQueries(state) { db.incidents(activeOnly = true) }, db, mqttConnected)
        reportsV1(state, db)
        fleetSocket(fleet)
        // Staff web UI: `/` = live map (index.html), `/report.html` = report an incident.
        staticResources("/", "static")
    }
}
