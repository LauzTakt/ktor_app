package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.ZURICH
import ch.lauzhack.twin.common.obj
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings of the passenger app's server side (ported from sbb-client `server/env.ts`).
 * [elevenLabsKey] comes from `ELEVENLABS_API_KEY` (env only): it never reaches the browser and is never logged.
 */
data class PassengerConfig(
    /** sbb-twin base URL (`twin.url` / `SBB_TWIN_URL`, shared with the assistant). */
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
                twinUrl = s("twin.url") ?: d.twinUrl,
                elevenLabsKey = env("ELEVENLABS_API_KEY")?.trim().orEmpty(),
                agentId = s("passenger.agentId") ?: "",
                elevenLabsUrl = s("passenger.elevenLabsUrl") ?: d.elevenLabsUrl,
            )
        }
    }
}

// ---------- loose JSON access: see common/Json.kt; passenger-only extras ----------
internal fun JsonObject?.geometry(): JsonElement? = this?.get("geometry")?.takeIf { it !is JsonNull }
internal fun JsonObject?.truthy(k: String): Boolean = truthy(this?.get(k))
internal fun truthy(e: JsonElement?): Boolean = when (e) {
    null, JsonNull -> false
    is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.booleanOrNull ?: e.doubleOrNull?.let { it != 0.0 } ?: true
    else -> true
}

// ---------- wording ----------
private val DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZURICH)
internal fun dayMonth(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))
private val SPACED = Regex("^([A-Z]+)(\\d)")
/** "IC1" -> "IC 1" */
internal fun spaced(line: String) = line.replaceFirst(SPACED, "$1 $2")
internal fun lineKey(s: String) = s.uppercase().replace(Regex("\\s+"), "")
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
