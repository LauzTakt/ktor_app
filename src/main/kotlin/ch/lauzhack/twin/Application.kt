package ch.lauzhack.twin

import ch.lauzhack.twin.assistant.AnthropicLlm
import ch.lauzhack.twin.assistant.AssistantAgent
import ch.lauzhack.twin.assistant.AssistantConfig
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.assistant.assistantV1
import ch.lauzhack.twin.assistant.buildAssistant
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
import ch.lauzhack.twin.passenger.localCatalog
import ch.lauzhack.twin.passenger.PassengerConfig
import ch.lauzhack.twin.passenger.PassengerService
import ch.lauzhack.twin.passenger.passengerApi
import ch.lauzhack.twin.push.PushConfig
import ch.lauzhack.twin.push.PushSender
import ch.lauzhack.twin.push.PushService
import ch.lauzhack.twin.push.PushStore
import ch.lauzhack.twin.push.pushV1
import ch.lauzhack.twin.query.IncidentQueries
import ch.lauzhack.twin.query.JourneyQueries
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.report.ReportService
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.wallet.TripWatchJourneys
import ch.lauzhack.twin.wallet.TripWatchService
import ch.lauzhack.twin.wallet.TripWatchStore
import ch.lauzhack.twin.wallet.TripWatchConfig
import ch.lauzhack.twin.wallet.tripWatchRoutes
import ch.lauzhack.twin.web.AppLinksConfig
import ch.lauzhack.twin.query.FleetFeed
import ch.lauzhack.twin.web.appLinks
import ch.lauzhack.twin.web.fleetSocket
import io.ktor.server.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Health(val status: String)

/** Entry point referenced from application.yaml (ktor.application.modules). */
fun Application.module() {
    val conf = environment.config
    val dbPath = conf.propertyOrNull("db.path")?.getString() ?: "data/twin.sqlite"
    val db = Database(dbPath)
    val state = LiveState(db.stations.all())
    val journeys = JourneyCache()

    val mqttEnabled = conf.propertyOrNull("mqtt.enabled")?.getString()?.toBooleanStrictOrNull() ?: true
    val ingestor = if (mqttEnabled) {
        MqttIngestor(
            MqttConfig(
                brokerUrl = conf.property("mqtt.brokerUrl").getString(),
                username = conf.propertyOrNull("mqtt.username")?.getString(),
                password = conf.propertyOrNull("mqtt.password")?.getString(),
                clientIdPrefix = conf.propertyOrNull("mqtt.clientIdPrefix")?.getString() ?: "ktor-app",
            ),
            state, db.incidents, db.stations,
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
    // Passenger assistant (POST /api/v1/assistant/ask): Claude API key from ANTHROPIC_API_KEY, the rest from assistant.*
    val assistantCfg = AssistantConfig.from(conf)
    val llm = AnthropicLlm.fromEnv(assistantCfg)
    if (llm == null) log.warn("ANTHROPIC_API_KEY is not set: /api/v1/assistant/ask will answer 503")
    else log.info("Assistant: model {} (fast mode {}, effort {}), sbb-twin at {}", assistantCfg.model, assistantCfg.fastMode, assistantCfg.effort, assistantCfg.twinUrl)
    llm?.keepWarm(assistantCfg.model)
    val assistant = llm?.let { l ->
        { q: TwinQueries ->
            buildAssistant(assistantCfg, l, q, state, IncidentQueries(db.incidents), ReportService(db.reports, state), journeys).also { a -> Thread({ Thread.sleep(8_000); a.warmUp() }, "assistant-warm-up").apply { isDaemon = true }.start() }
        }
    }

    // Passenger voice app (brief, voice session, twin proxy): ELEVENLABS_API_KEY from env, the rest from passenger.*
    val passengerCfg = PassengerConfig.from(conf)
    if (!passengerCfg.voiceEnabled) log.warn("ELEVENLABS_API_KEY or PASSENGER_AGENT_ID is not set: /api/voice/session will answer 503")

    // Push notifications (web + Android app): VAPID keys from env or generated; FCM from FCM_SERVICE_ACCOUNT_JSON.
    val pushCfg = PushConfig.from(conf)
    if (pushCfg.fcmServiceAccount == null) log.warn("FCM_SERVICE_ACCOUNT_JSON is not set: Android app push is disabled (Web Push still works)")

    configureHttp(state, db, authConfig, assistant, passengerCfg, journeys, TripWatchConfig.fromEnvironment(dbPath), pushCfg) {
        ingestor?.isConnected ?: false
    }
}

/**
 * HTTP layer only (plugins + routes); split from [module] so tests can inject a seeded [LiveState] (and a fake
 * assistant through [assistant], built from the same [TwinQueries] as the REST API).
 */
fun Application.configureHttp(
    state: LiveState,
    db: Database,
    authConfig: AuthConfig = AuthConfig(),
    assistant: ((TwinQueries) -> AssistantAgent)? = null,
    passenger: PassengerConfig = PassengerConfig(),
    journeys: JourneyCache = JourneyCache(),
    tripWatchConfig: TripWatchConfig = TripWatchConfig(enabled = false),
    push: PushConfig = PushConfig(),
    pushSenders: Map<String, PushSender>? = null,
    appLinks: AppLinksConfig = AppLinksConfig.fromEnvironment(),
    mqttConnected: () -> Boolean,
): PushService {
    install(CallLogging) {
        filter { call ->
            val path = call.request.path()
            !path.startsWith("/wallet/") && !path.startsWith("/api/v1/trip-watches") && path != "/trip-watch.html"
        }
    }
    install(Compression) // gzip/deflate: the rail network GeoJSON (~3 MB) and API lists shrink ~5x
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    installApiErrors()
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    // Staff auth: existing routes stay public; staff-only calls go inside `staffOnly { }` (see auth/Auth.kt).
    val auth = AuthService(db.users, db.tokens, authConfig).also { it.ensureDefaultAdmin() }
    installStaffAuth(auth)
    val queries = TwinQueries(state) { db.incidents.list(activeOnly = true) }
    val journeyQueries = JourneyQueries(state, queries)
    val live = LiveUpdates(queries, db.incidents)
    val fleet = FleetFeed(state)
    val agent = assistant?.invoke(queries)
    val pushService = PushService(PushStore(db.sqlite), queries, state, push, pushSenders).also { it.start(db.incidents::addListener) }
    monitor.subscribe(ApplicationStopped) { pushService.close() }
    val tripWatch = if (tripWatchConfig.enabled) {
        val apple = tripWatchConfig.apple()
        val google = tripWatchConfig.google()
        val store = TripWatchStore(tripWatchConfig.path)
        TripWatchService(
            store, TripWatchJourneys(queries, journeys, SbbTwinClient(passenger.twinUrl)),
            tripWatchConfig.site.trimEnd('/'), apple, google,
            fresh = { mqttConnected() && queries.status(true).twin?.online == true },
        ).also { service ->
            val changes = Channel<Unit>(Channel.CONFLATED)
            db.incidents.addListener { _, _ -> changes.trySend(Unit) }
            val worker = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    try {
                        service.refresh()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.warn("Trip Watch update deferred ({})", error.javaClass.simpleName)
                    }
                    withTimeoutOrNull(20_000) { changes.receive() }
                }
            }
            monitor.subscribe(ApplicationStopped) {
                runBlocking { worker.cancelAndJoin() }
                changes.close()
                store.close()
            }
        }
    } else null
    routing {
        get("/health") { call.respond(Health("ok")) }
        appLinks(appLinks) // /.well-known/ files: QR codes and links open the mobile app (web/AppLinks.kt)
        // Swagger UI at /swagger, spec at /swagger/documentation.yaml (keep in sync with docs/API.md and api/v1/).
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        apiV1(queries, journeyQueries, IncidentQueries(db.incidents), mqttConnected)
        authV1(auth)
        reportsV1(ReportService(db.reports, state))
        assistantV1(agent)
        passengerApi(PassengerService(passenger, fleet, journeys.takeIf { tripWatch != null }) { localCatalog(state) })
        tripWatchRoutes(tripWatch)
        liveUpdatesWs(live)
        fleetSocket(fleet)
        pushV1(pushService)
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
    return pushService
}
