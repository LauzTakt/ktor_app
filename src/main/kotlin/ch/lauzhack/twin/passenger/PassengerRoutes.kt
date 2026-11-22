package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.array
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.urlEncode
import ch.lauzhack.twin.query.FleetFeed
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.TwinException
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Server side of the passenger voice app (port of sbb-client `server/plugin.ts` + `twin.ts`): ElevenLabs session
 * credentials, the place brief, the nearest station, a fleet snapshot and a read-only sbb-twin proxy.
 */
class PassengerService(
    val config: PassengerConfig,
    private val fleet: FleetFeed? = null,
    journeys: JourneyCache? = null,
    localStations: () -> List<CatalogStation> = { emptyList() },
) {
    private val log = LoggerFactory.getLogger("passenger")
    val twin = SbbTwinClient(config.twinUrl, timeoutMs = 15_000)
    val catalog = StationCatalog(twin, local = localStations)
    val brief = PlaceBrief(twin, catalog, journeyCache = journeys)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(3_000)).build()

    /** One-time ElevenLabs conversation credentials: WebRTC token (best echo cancelling on phones) + signed WebSocket URL. */
    suspend fun voiceSession(): Pair<HttpStatusCode, JsonObject> {
        if (!config.voiceEnabled) return HttpStatusCode.ServiceUnavailable to errorBody("Voice is not configured on this server")
        suspend fun get(path: String): JsonObject {
            val uri = URI.create("${config.elevenLabsUrl.trimEnd('/')}/v1/convai/conversation/$path?agent_id=${urlEncode(config.agentId)}")
            val req = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(8_000)).header("xi-api-key", config.elevenLabsKey).GET().build()
            val r = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            if (r.statusCode() !in 200..299) throw IllegalStateException("HTTP ${r.statusCode()}")
            return Json.parseToJsonElement(r.body()).obj() ?: throw IllegalStateException("not an object")
        }
        val (token, signed) = coroutineScope {
            val t = async { runCatching { get("token").str("token") }.onFailure { log.warn("ElevenLabs token: {}", it.message) }.getOrNull() }
            val s = async { runCatching { get("get-signed-url").str("signed_url") }.onFailure { log.warn("ElevenLabs signed URL: {}", it.message) }.getOrNull() }
            t.await() to s.await()
        }
        if (token == null && signed == null) return HttpStatusCode.BadGateway to errorBody("ElevenLabs did not answer")
        return HttpStatusCode.OK to buildJsonObject { put("conversationToken", token); put("signedUrl", signed) }
    }

    // ---------- fleet: our own /ws/fleet tick, else the twin's train list in the same rows ----------
    private val fleetMutex = Mutex()
    @Volatile private var twinFleet: Pair<Long, String>? = null

    suspend fun fleetSnapshot(): String {
        fleet?.current()?.let { return it.text }
        twinFleet?.takeIf { System.currentTimeMillis() - it.first <= 1_500 }?.let { return it.second }
        return fleetMutex.withLock {
            twinFleet?.takeIf { System.currentTimeMillis() - it.first <= 1_500 }?.let { return@withLock it.second }
            val list = try { twin.json("/api/trains", 15_000).objects() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                twinFleet?.let { return@withLock it.second }
                throw e
            }
            fun r5(x: Double?) = ((x ?: 0.0) * 1e5).roundToLong() / 1e5
            val body = buildJsonObject {
                put("type", "tick")
                put("ts", list.maxOfOrNull { it.long("ts") ?: 0 } ?: 0)
                putJsonArray("trains") {
                    for (t in list) addJsonArray {
                        add(t.str("trainId")); add(r5(t.double("lat"))); add(r5(t.double("lon")))
                        add((t.double("bearing") ?: 0.0).roundToLong()); add((t.double("speedKmh") ?: 0.0).roundToLong())
                        add(t.str("motion")); add(t["delaySec"]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToLong() } ?: 0L)
                        add(t.str("line")); add(t.str("category")); add(t.obj("atStop").str("id")); add(t.long("continuity") ?: 0)
                    }
                }
            }.toString()
            twinFleet = System.currentTimeMillis() to body
            body
        }
    }

    // ---------- read-only twin proxy ----------
    private class Cached(val at: Long, val status: Int, val type: String, val body: ByteArray)
    private val proxyCache = ConcurrentHashMap<String, Cached>()
    /** network.geojson (1 MB) and stations.geojson change at most once a day: kept 10 minutes. */
    private val cachedPaths = Regex("^/api/(network|abroad|stations)\\.geojson$")

    internal suspend fun proxy(call: ApplicationCall) {
        val method = call.request.httpMethod
        if (method != HttpMethod.Get && method != HttpMethod.Head) {
            return call.json(HttpStatusCode.MethodNotAllowed, errorBody("The passenger app only reads the twin"))
        }
        val qs = call.request.queryString()
        val path = call.request.path().removePrefix("/twin") + if (qs.isNotEmpty()) "?$qs" else ""
        proxyCache[path]?.takeIf { System.currentTimeMillis() - it.at < 600_000 }?.let { hit ->
            call.response.header("Cache-Control", "public, max-age=600")
            return call.respondBytes(hit.body, contentType(hit.type), HttpStatusCode.fromValue(hit.status))
        }
        val r = try { twin.raw(path, 20_000) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return call.json(HttpStatusCode.BadGateway, errorBody((e as? TwinException)?.message ?: "sbb-twin unreachable: ${e.message ?: e.javaClass.simpleName}"))
        }
        val type = r.contentType ?: "application/json"
        if (r.status in 200..299 && cachedPaths.matches(path.substringBefore('?'))) proxyCache[path] = Cached(System.currentTimeMillis(), r.status, type, r.body)
        call.response.header("Cache-Control", "no-store")
        call.respondBytes(r.body, contentType(type), HttpStatusCode.fromValue(r.status))
    }

    private fun contentType(s: String) = runCatching { ContentType.parse(s) }.getOrDefault(ContentType.Application.Json)
}

internal fun errorBody(message: String) = buildJsonObject { put("error", message) }

internal suspend fun ApplicationCall.json(status: HttpStatusCode, body: JsonObject) {
    response.header("Cache-Control", "no-store")
    respondText(body.toString(), ContentType.Application.Json, status)
}

/** Errors as `{"error": "..."}` (the passenger app's own shape, not the `/api/v1` error envelope). */
private suspend fun ApplicationCall.guard(block: suspend ApplicationCall.() -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoggerFactory.getLogger("passenger").warn("{} failed: {}", request.path(), e.toString())
        // the twin unreachable or answering an error: 502, like the proxy; anything else is ours: 500
        val status = if (e is IOException) HttpStatusCode.BadGateway else HttpStatusCode.InternalServerError
        json(status, errorBody(if (e is TwinException) e.message ?: "sbb-twin unreachable" else if (e is IOException) "sbb-twin unreachable: ${e.message ?: e.javaClass.simpleName}" else (e.message ?: e.javaClass.simpleName)))
    }
}

/**
 * Passenger app endpoints (public, no staff login): `/api/config`, `/api/voice/session`, `/api/brief`,
 * `/api/brief/active`, `/api/brief/item`, `/api/fleet`, `/api/nearest`, `/twin/...`. See docs/API.md.
 */
fun Route.passengerApi(svc: PassengerService) {
    val log = LoggerFactory.getLogger("brief")
    get("/api/config") {
        call.json(HttpStatusCode.OK, buildJsonObject { put("voice", svc.config.voiceEnabled); put("mock", JsonNull) })
    }
    get("/api/voice/session") {
        call.guard { val (status, body) = svc.voiceSession(); json(status, body) }
    }
    get("/api/brief/active") { call.guard { json(HttpStatusCode.OK, svc.brief.activeList()) } }
    get("/api/brief/item") {
        call.guard {
            val d = svc.brief.disruptionById(request.queryParameters["id"] ?: "")
            if (d != null) json(HttpStatusCode.OK, d) else json(HttpStatusCode.NotFound, errorBody("unknown disruption"))
        }
    }
    get("/api/brief") {
        call.guard {
            val p = request.queryParameters
            val t0 = System.currentTimeMillis()
            val b = svc.brief.placeBrief(BriefQuery(p["station"], p["from"], p["to"], p["line"], p["q"], p["lat"], p["lon"]))
            log.info(
                "[brief] {} {} ms {}",
                b?.let { "${it.str("key")} related=${it["related"]} ${it.array("disruptions").size} disruptions ${it.array("journeys").size} journeys" } ?: "no place",
                System.currentTimeMillis() - t0, request.queryString().take(120),
            )
            if (b != null) json(HttpStatusCode.OK, b) else json(HttpStatusCode.NotFound, errorBody("No station, route or line recognised"))
        }
    }
    get("/api/fleet") {
        call.guard { val body = svc.fleetSnapshot(); response.header("Cache-Control", "no-store"); respondText(body, ContentType.Application.Json) }
    }
    get("/api/nearest") {
        call.guard {
            val lat = request.queryParameters["lat"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val lon = request.queryParameters["lon"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (lat == null || lon == null) return@guard json(HttpStatusCode.BadRequest, errorBody("lat and lon required"))
            val n = svc.catalog.nearestJson(lat, lon)
            if (n != null) json(HttpStatusCode.OK, n) else json(HttpStatusCode.NotFound, errorBody("no station nearby"))
        }
    }
    route("/twin/{path...}") {
        handle { call.guard { svc.proxy(this) } }
    }
}
