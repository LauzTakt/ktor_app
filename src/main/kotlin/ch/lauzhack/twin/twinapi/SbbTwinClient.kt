package ch.lauzhack.twin.twinapi

import ch.lauzhack.twin.common.urlEncode
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * sbb-twin answered with an error status (`status` > 0) or could not be reached (`status` = 0). An [IOException], so
 * callers can treat every sbb-twin failure as "upstream unavailable" (the passenger app answers 502).
 */
class TwinException(val status: Int, message: String) : IOException(message)

/**
 * The only HTTP client for the sbb-twin API (`{baseUrl}/api/...`), shared by the assistant and the passenger app.
 * Read-only by construction: GET requests only, we never change the twin's state.
 */
class SbbTwinClient(baseUrl: String, private val timeoutMs: Long = 4_000) {
    val baseUrl: String = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1_500))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    class Raw(val status: Int, val contentType: String?, val body: ByteArray)

    /** GET [path] (full path, e.g. `/api/brief?station=…`) as bytes, whatever the status (for the read-only proxy). */
    suspend fun raw(path: String, timeoutMs: Long = this.timeoutMs): Raw {
        val r = send(path, timeoutMs, HttpResponse.BodyHandlers.ofByteArray(), json = false)
        return Raw(r.statusCode(), r.headers().firstValue("content-type").orElse(null), r.body())
    }

    /** GET [path] (full path) as JSON; non-2xx -> [TwinException]. */
    suspend fun json(path: String, timeoutMs: Long = this.timeoutMs): JsonElement {
        val r = send(path, timeoutMs, HttpResponse.BodyHandlers.ofString(), json = true)
        if (r.statusCode() !in 200..299) {
            val detail = runCatching { Json.parseToJsonElement(r.body()).toString().take(200) }.getOrDefault("")
            throw TwinException(r.statusCode(), "sbb-twin ${path.substringBefore('?')}: HTTP ${r.statusCode()} $detail".trim())
        }
        return Json.parseToJsonElement(r.body())
    }

    /** GET `/api{path}` with the non-null [params] as query string. */
    suspend fun get(path: String, vararg params: Pair<String, Any?>): JsonElement {
        val query = params.filter { it.second != null }.joinToString("&") { (k, v) -> k + "=" + urlEncode(v.toString()) }
        return json("/api$path" + if (query.isEmpty()) "" else "?$query")
    }

    private suspend fun <T> send(path: String, timeoutMs: Long, body: HttpResponse.BodyHandler<T>, json: Boolean): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .apply { if (json) header("Accept", "application/json") }
            .GET()
            .build()
        return try {
            http.sendAsync(request, body).await()
        } catch (e: IOException) {
            throw TwinException(0, "sbb-twin unreachable (${e.javaClass.simpleName})")
        }
    }

    suspend fun stations(q: String) = get("/stations", "q" to q)
    suspend fun board(stopId: String) = get("/stations/${urlEncode(stopId)}/board")
    suspend fun train(trainId: String) = get("/trains/${urlEncode(trainId)}")
    suspend fun plan(from: String, to: String, after: Long?, limit: Int, onboardTrainId: String? = null) =
        get("/plan", "from" to from, "to" to to, "after" to after, "limit" to limit, "onboardTrainId" to onboardTrainId)
    suspend fun situationAtStation(stopId: String) = get("/situation", "station" to stopId)
    suspend fun situationOfTrain(trainId: String) = get("/situation", "trainId" to trainId)
    suspend fun situationOfTrip(from: String, to: String) = get("/situation", "from" to from, "to" to to)
    suspend fun arrivals(stopId: String) = get("/stations/${urlEncode(stopId)}/arrivals")
    suspend fun bulletins() = get("/bulletins")
    suspend fun disruptions() = get("/disruptions")
    suspend fun sectionGeometry(from: String, to: String) = get("/geometry/section", "from" to from, "to" to to)
    suspend fun journeyGeometry(journeyId: String) = get("/geometry/journey/${urlEncode(journeyId)}")
    suspend fun trainGeometry(trainId: String, from: String?, to: String?) =
        get("/geometry/train/${urlEncode(trainId)}", "from" to from, "to" to to)
}
