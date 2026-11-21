package ch.lauzhack.twin

import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.api.v1.apiV1
import ch.lauzhack.twin.api.v1.installApiErrors
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.mqtt.MqttConfig
import ch.lauzhack.twin.mqtt.MqttIngestor
import ch.lauzhack.twin.state.LiveState
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    installApiErrors()
    routing {
        get("/health") { call.respond(Health("ok")) }
        apiV1(TwinQueries(state) { db.incidents(activeOnly = true) }, db, mqttConnected)
    }
}
