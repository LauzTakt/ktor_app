package ch.lauzhack.twin.passenger

import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings of the passenger app's server side (ported from sbb-client `server/env.ts`).
 * [elevenLabsKey] comes from `ELEVENLABS_API_KEY` (env only): it never reaches the browser and is never logged.
 */
data class PassengerConfig(
    /** sbb-twin base URL (`SBB_TWIN_URL`, shared with the assistant). */
    val twinUrl: String = "http://127.0.0.1:8787",
    val elevenLabsKey: String = "",
    /** ElevenLabs conversational agent (`PASSENGER_AGENT_ID`). */
    val agentId: String = "",
    val elevenLabsUrl: String = "https://api.elevenlabs.io",
) {
    val voiceEnabled get() = elevenLabsKey.isNotBlank() && agentId.isNotBlank()

    companion object {
        fun from(conf: ApplicationConfig, env: (String) -> String? = System::getenv): PassengerConfig {
            fun s(key: String) = conf.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            val d = PassengerConfig()
            return PassengerConfig(
                twinUrl = s("assistant.twinUrl") ?: d.twinUrl,
                elevenLabsKey = env("ELEVENLABS_API_KEY")?.trim().orEmpty(),
                agentId = s("passenger.agentId") ?: "",
                elevenLabsUrl = s("passenger.elevenLabsUrl") ?: d.elevenLabsUrl,
            )
        }
    }
}

/** Plain GET client for sbb-twin (full paths, e.g. `/api/brief?station=…`). Read-only by construction. */
class TwinHttp(baseUrl: String) {
    val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1_500))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    class Raw(val status: Int, val contentType: String?, val body: ByteArray)

    suspend fun raw(path: String, timeoutMs: Long = 20_000): Raw {
        val req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs)).GET().build()
        val r = http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).await()
        return Raw(r.statusCode(), r.headers().firstValue("content-type").orElse(null), r.body())
    }

    suspend fun json(path: String, timeoutMs: Long = 15_000): JsonElement {
        val r = raw(path, timeoutMs)
        if (r.status !in 200..299) throw IOException("twin $path: HTTP ${r.status}")
        return Json.parseToJsonElement(r.body.decodeToString())
    }
}

// ---------- loose JSON access (the twin's payloads are read field by field, like the TypeScript did) ----------
internal val EMPTY_ARRAY = JsonArray(emptyList())
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: EMPTY_ARRAY
internal fun JsonElement?.objs(): List<JsonObject> = arr().mapNotNull { it as? JsonObject }
internal fun JsonObject?.str(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonObject?.num(k: String): Double? = (this?.get(k) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject?.lng(k: String): Long? = num(k)?.toLong()
internal fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
internal fun JsonObject?.a(k: String): JsonArray = this?.get(k).arr()
internal fun JsonObject?.objs(k: String): List<JsonObject> = this?.get(k).objs()
internal fun JsonObject?.strs(k: String): List<String> = a(k).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
internal fun JsonObject?.geometry(): JsonElement? = this?.get("geometry")?.takeIf { it !is JsonNull }
internal fun JsonObject?.truthy(k: String): Boolean = truthy(this?.get(k))
internal fun truthy(e: JsonElement?): Boolean = when (e) {
    null, JsonNull -> false
    is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.booleanOrNull ?: e.doubleOrNull?.let { it != 0.0 } ?: true
    else -> true
}

// ---------- wording ----------
private val ZONE = ZoneId.of("Europe/Zurich")
private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE)
private val DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZONE)
internal fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms))
internal fun dayMonth(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))
private val SPACED = Regex("^([A-Z]+)(\\d)")
/** "IC1" -> "IC 1" */
internal fun spaced(line: String) = line.replaceFirst(SPACED, "$1 $2")
internal fun lineKey(s: String) = s.uppercase().replace(Regex("\\s+"), "")
internal fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
internal fun String.capitalised() = replaceFirstChar { it.uppercaseChar() }

/** Every [lon, lat] pair of a GeoJSON geometry. */
internal fun collectCoords(g: JsonElement?, out: MutableList<Pair<Double, Double>>) {
    fun walk(c: JsonElement?) {
        val a = c as? JsonArray ?: return
        val x = (a.firstOrNull() as? JsonPrimitive)?.doubleOrNull
        val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        if (x != null && y != null) out += x to y else a.forEach { walk(it) }
    }
    walk(g.obj()?.get("coordinates"))
}
