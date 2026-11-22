package ch.lauzhack.twin.twinapi

import ch.lauzhack.twin.common.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/*
 * Journeys from the sbb-twin planner (`/api/plan`), shared in process by the assistant, the passenger app and
 * Trip Watch: whoever plans a journey first caches it, the others reuse it by id.
 */

/** How a journey was planned, so it can be planned again when the twin has dropped it. */
data class PlanQuery(val from: String, val to: String, val after: Long?, val limit: Int, val onboardTrainId: String?)

/** A planned journey as the twin returned it, with its validated (simplified) geometry per leg once known. */
data class CachedJourney(val journey: JsonObject, val query: PlanQuery, val geometry: List<JsonElement?>?, val at: Long) {
    val id: String get() = journey.str("id") ?: ""
}

/** Journeys from the planner, by id (the twin keeps them ~30 min): options and routes always use the planner's times. */
class JourneyCache(private val ttlMs: Long = 30 * 60_000L) {
    private val map = ConcurrentHashMap<String, CachedJourney>()

    fun put(entry: CachedJourney, preferExisting: Boolean = false) {
        if (entry.id.isEmpty()) return
        map.compute(entry.id) { _, previous ->
            if (preferExisting && previous != null && entry.at - previous.at <= ttlMs) previous else entry
        }
        if (map.size > 300) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now - it.value.at > ttlMs }
        }
    }

    fun entry(id: String): CachedJourney? = map[id]?.takeIf { System.currentTimeMillis() - it.at <= ttlMs }
    fun get(id: String): JsonObject? = entry(id)?.journey
}
