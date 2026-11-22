package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.array
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.query.StationSearch
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.twinapi.SbbTwinClient
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A station of the twin's `stations.geojson` (`trains` = how many trains call there today). */
data class CatalogStation(val id: String, val name: String, val trains: Int, val lon: Double, val lat: Double) {
    val norm: String = Places.norm(name)
}

/** A station named in a question, with the role its preceding word suggests (`to`, `from` or null). */
data class PlaceHit(val st: CatalogStation, val role: String?)

/** Station names in free text (port of sbb-client `server/mock.ts`: placesIn). Pure: the station list is passed in. */
object Places {
    /** [StationSearch.normalize] padded with one space on each side (whole-word substring matching). */
    fun norm(s: String): String = " " + StationSearch.normalize(s) + " "

    private val STOPWORDS = setOf("ins", "mies", "alle", "berg", "horn", "sins", "lax", "moos", "steg", "klus", "gais", "birr", "seon", "fey", "wila", "buix", "dorf", "au")

    private class Hit(val st: CatalogStation, val start: Int, val end: Int)

    /** Stations named in [question], in order of appearance; the longest non-overlapping names win. */
    fun placesIn(question: String, list: List<CatalogStation>): List<PlaceHit> {
        val byNorm = list.associateBy { it.norm.trim() }
        val q = norm(question)
        val hits = mutableListOf<Hit>()
        fun add(st: CatalogStation?, key: String) {
            if (st == null) return
            var i = q.indexOf(key)
            while (i >= 0) {
                hits += Hit(st, i, i + key.length)
                i = q.indexOf(key, i + 1)
            }
        }
        for (s in list) {
            val words = s.norm.trim().split(" ")
            if (words.size == 1 && (words[0] in STOPWORDS || (words[0].length < 5 && s.trains < 150))) continue
            if (s.trains < 8) continue
            add(s, s.norm)
        }
        for ((alias, name) in StationSearch.ALIASES) add(byNorm[name], " $alias ")
        // "Zürich Flughafen" over "Zürich"; the busiest station on ties
        hits.sortWith(compareByDescending<Hit> { it.end - it.start }.thenByDescending { it.st.trains })
        val kept = mutableListOf<Hit>()
        for (h in hits) if (kept.none { h.start < it.end - 1 && it.start < h.end - 1 }) kept += h
        kept.sortBy { it.start }
        return kept.map { h ->
            val before = q.substring(0, h.start).trim().split(" ").takeLast(2)
            val role = when {
                before.any { it in StationSearch.TO_WORDS } -> "to"
                before.any { it in StationSearch.FROM_WORDS } -> "from"
                else -> null
            }
            PlaceHit(h.st, role)
        }
    }

    fun distanceKm(lat: Double, lon: Double, s: CatalogStation): Double = ch.lauzhack.twin.common.distanceKm(lat, lon, s.lat, s.lon)

    /** The closest station with at least 10 trains, or a busier one when it is barely further (port of twin.ts). */
    fun nearest(list: List<CatalogStation>, lat: Double, lon: Double): Pair<CatalogStation, Double>? {
        val near = list.filter { it.trains >= 10 }.map { it to distanceKm(lat, lon, it) }.sortedBy { it.second }.take(8)
        if (near.isEmpty()) return null
        return near.reduce { b, c -> if (c.second - b.second < 0.6 && c.first.trains > b.first.trains * 2) c else b }
    }
}

/**
 * The twin's station catalog (`/api/stations.geojson`), kept 10 minutes. When the twin is unreachable, [local] (our
 * own stations learned from MQTT `info`) stands in for 30 s, so nearest station and place matching keep working.
 */
class StationCatalog(
    private val twin: SbbTwinClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val local: () -> List<CatalogStation> = { emptyList() },
) {
    private val mutex = Mutex()
    @Volatile private var cache: Pair<Long, List<CatalogStation>>? = null

    suspend fun list(): List<CatalogStation> {
        cache?.takeIf { now() - it.first < 600_000 }?.let { return it.second }
        return mutex.withLock {
            cache?.takeIf { now() - it.first < 600_000 }?.let { return@withLock it.second }
            val fromTwin = try {
                twin.json("/api/stations.geojson")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = local()
                if (fallback.isEmpty()) throw e
                cache = (now() - 570_000) to fallback // retry the twin in 30 s
                return@withLock fallback
            }
            val list = fromTwin.obj().objects("features").mapNotNull { f ->
                val p = f.obj("properties")
                val c = f.obj("geometry").array("coordinates")
                val id = p.str("id") ?: return@mapNotNull null
                val lon = (c.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = (c.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
                CatalogStation(id, p.str("name") ?: id, p.double("trains")?.toInt() ?: 0, lon, lat)
            }
            cache = now() to list
            list
        }
    }

    /** `GET /api/nearest` body: `{id, name, lon, lat, distanceKm}`. */
    suspend fun nearestJson(lat: Double, lon: Double): JsonObject? {
        val (s, d) = Places.nearest(list(), lat, lon) ?: return null
        return buildJsonObject {
            put("id", s.id); put("name", s.name); put("lon", s.lon); put("lat", s.lat)
            put("distanceKm", (d * 10).roundToInt() / 10.0)
        }
    }
}

/**
 * Our stations (from MQTT `info`) in the catalog's shape, for when sbb-twin is unreachable. `trains` is 10 + the trains
 * in service calling there (the twin counts the whole day), so every station passes the catalog's minimum and busier
 * ones still rank first.
 */
fun localCatalog(state: LiveState): List<CatalogStation> =
    state.stations().mapNotNull { s ->
        val lat = s.lat ?: return@mapNotNull null
        val lon = s.lon ?: return@mapNotNull null
        CatalogStation(s.stopId, s.name, 10 + state.trainsCallingAt(s.stopId).size, lon, lat)
    }
