package ch.lauzhack.twin

import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.api.v1.apiV1
import ch.lauzhack.twin.api.v1.LiveUpdates
import ch.lauzhack.twin.api.v1.installApiErrors
import ch.lauzhack.twin.api.v1.liveUpdatesWs
import ch.lauzhack.twin.api.v1.reportsV1
import ch.lauzhack.twin.auth.AuthConfig
import ch.lauzhack.twin.auth.AuthService
import ch.lauzhack.twin.auth.authV1
import ch.lauzhack.twin.auth.installStaffAuth
import ch.lauzhack.twin.auth.staffPages
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
import io.ktor.server.response.respondRedirect
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
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

    val authConfig = AuthConfig(
        adminPassword = conf.propertyOrNull("auth.adminPassword")?.getString()?.takeIf { it.isNotEmpty() } ?: "admin",
        tokenTtlHours = conf.propertyOrNull("auth.tokenTtlHours")?.getString()?.toLongOrNull() ?: 12,
    )
    configureHttp(state, db, authConfig) { ingestor?.isConnected ?: false }
}

/** HTTP layer only (plugins + routes); split from [module] so tests can inject a seeded [LiveState]. */
fun Application.configureHttp(state: LiveState, db: Database, authConfig: AuthConfig = AuthConfig(), mqttConnected: () -> Boolean) {
    install(CallLogging)
    install(Compression) // gzip/deflate: the rail network GeoJSON (~3 MB) and API lists shrink ~5x
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    installApiErrors()
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    // Staff auth: existing routes stay public; staff-only calls go inside `staffOnly { }` (see auth/Auth.kt).
    val auth = AuthService(db, authConfig).also { it.ensureDefaultAdmin() }
    installStaffAuth(auth)
    val queries = TwinQueries(state) { db.incidents(activeOnly = true) }
    val live = LiveUpdates(queries, db)
    val fleet = FleetFeed(state)
    routing {
        get("/health") { call.respond(Health("ok")) }
        // Swagger UI at /swagger, spec at /swagger/documentation.yaml (keep in sync with docs/API.md and api/v1/).
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        apiV1(queries, db, mqttConnected)
        authV1(auth)
        reportsV1(state, db)
        liveUpdatesWs(live)
        fleetSocket(fleet)
        // Staff panel (staff only, else -> /login.html): `/admin/` = live map, `/admin/report.html` = report an incident.
        staffPages(
            auth,
            mapOf(
                "/admin" to "static/admin/index.html", "/admin/" to "static/admin/index.html",
                "/admin/index.html" to "static/admin/index.html", "/admin/report.html" to "static/admin/report.html",
            ),
        )
        get("/report.html") { call.respondRedirect("/admin/report.html", permanent = true) } // old staff URL
        // `/` = public passenger view (index.html); login.html, JS, CSS, GeoJSON: public (no data in them).
        staticResources("/", "static")
    }
}
