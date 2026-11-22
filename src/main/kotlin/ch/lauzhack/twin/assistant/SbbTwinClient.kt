package ch.lauzhack.twin.assistant

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** sbb-twin answered with an error status (`status` > 0) or could not be reached (`status` = 0). */
class TwinException(val status: Int, message: String) : RuntimeException(message)

/**
 * Read-only HTTP client for the sbb-twin API (`{baseUrl}/api/...`, see ASSISTANT_CONTRACT.md section 1).
 * Only GET requests: the assistant never changes the twin's state.
 */
class SbbTwinClient(baseUrl: String, private val timeoutMs: Long = 4_000) {
    val baseUrl: String = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1_500))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    suspend fun get(path: String, vararg params: Pair<String, Any?>): JsonElement {
        val query = params.filter { it.second != null }
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), StandardCharsets.UTF_8) }
        val uri = URI.create("$baseUrl/api$path" + if (query.isEmpty()) "" else "?$query")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: Exception) {
            throw TwinException(0, "sbb-twin unreachable (${e.javaClass.simpleName})")
        }
        if (response.statusCode() !in 200..299) {
            val detail = runCatching { Json.parseToJsonElement(response.body()).toString().take(200) }.getOrDefault("")
            throw TwinException(response.statusCode(), "sbb-twin $path: HTTP ${response.statusCode()} $detail".trim())
        }
        return Json.parseToJsonElement(response.body())
    }

    suspend fun stations(q: String) = get("/stations", "q" to q)
    suspend fun board(stopId: String) = get("/stations/${seg(stopId)}/board")
    suspend fun train(trainId: String) = get("/trains/${seg(trainId)}")
    suspend fun plan(from: String, to: String, after: Long?, limit: Int, onboardTrainId: String? = null, via: String? = null) =
        get("/plan", "from" to from, "to" to to, "after" to after, "limit" to limit, "onboardTrainId" to onboardTrainId,
            "via" to via, "includeWait" to true, "maxTransfers" to 3)
    suspend fun situationAtStation(stopId: String) = get("/situation", "station" to stopId)
    suspend fun situationOfTrain(trainId: String) = get("/situation", "trainId" to trainId)
    suspend fun situationOfTrip(from: String, to: String) = get("/situation", "from" to from, "to" to to)
    suspend fun arrivals(stopId: String) = get("/stations/${seg(stopId)}/arrivals")
    suspend fun bulletins() = get("/bulletins")
    suspend fun disruptions() = get("/disruptions")
    suspend fun sectionGeometry(from: String, to: String) = get("/geometry/section", "from" to from, "to" to to)
    suspend fun journeyGeometry(journeyId: String) = get("/geometry/journey/${seg(journeyId)}")
    suspend fun trainGeometry(trainId: String, from: String?, to: String?) =
        get("/geometry/train/${seg(trainId)}", "from" to from, "to" to to)

    private fun seg(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
}
