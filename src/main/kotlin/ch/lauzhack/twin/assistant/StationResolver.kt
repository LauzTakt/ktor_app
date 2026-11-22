package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.state.Station
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Station search for the assistant: names in any of the Swiss languages or English, without accents, with or without
 * "station"/"Hauptbahnhof". Ranking: exact name, then name starting with the query, then every query word starting a
 * name word, then substring; ties go to the busier station (more trains calling), so "Zürich" is Zürich HB.
 *
 * Catalog = stations of the trains in service ([LiveState]) plus every station seen before ([catalog], SQLite).
 */
class StationResolver(
    private val state: LiveState,
    private val catalog: () -> Collection<Station> = { emptyList() },
) {
    @Volatile private var cached: Pair<Long, Map<String, Station>> = 0L to emptyMap()

    fun all(): Map<String, Station> {
        val now = System.currentTimeMillis()
        val (at, map) = cached
        val live = state.stations()
        if (now - at < CACHE_MS && map.size >= live.size) return map
        val merged = HashMap<String, Station>()
        runCatching { catalog() }.getOrDefault(emptyList()).forEach { merged[it.stopId] = it }
        live.forEach { merged[it.stopId] = it }
        cached = now to merged
        return merged
    }

    fun byId(stopId: String): Station? = state.station(stopId) ?: all()[stopId]

    /** Best matches for a name (or an exact stopId), best first. */
    fun search(query: String, limit: Int = 5): List<Station> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        byId(raw)?.let { return listOf(it) }
        val q = cleanQuery(raw)
        if (q.isEmpty()) return emptyList()
        val stations = all().values
        var hits = rank(q, stations)
        if (hits.isEmpty() && q.endsWith(" hb")) hits = rank(q.removeSuffix(" hb"), stations)
        return hits.take(limit)
    }

    /** A stopId (7 digits or any id the catalog knows) or a name -> the station; null if nothing matches. */
    fun resolve(idOrName: String): Station? {
        val s = idOrName.trim()
        if (s.isEmpty()) return null
        byId(s)?.let { return it }
        if (UIC.matches(s)) return Station(s, s, null, null) // unknown to us; the twin may still know it
        return search(s, 1).firstOrNull()
    }

    /**
     * A station named with confidence at the start of [phrase] (as captured after "from", "nach", "entre", ...): the
     * longest leading run of words (an article may come first) that is a station's exact name, or the first words of a
     * busy station's name ("Zürich" -> Zürich HB). Null when unsure.
     */
    fun confident(phrase: String): Station? {
        val words = cleanQuery(phrase).split(' ').filter { it.isNotEmpty() }.take(8)
        val stations = all().values
        for (skip in 0..minOf(1, words.size - 1)) {
            if (skip == 1 && words[0] !in ARTICLES) break
            for (len in (words.size - skip) downTo 1) {
                val q = cleanQuery(words.subList(skip, skip + len).joinToString(" "))
                if (q.length < 3) continue
                val hit = rank(q, stations).firstOrNull() ?: continue
                val n = normalized.getOrPut(hit.name) { normalize(hit.name) }
                if (n == q || n.startsWith("$q ")) return hit
            }
        }
        return null
    }

    /**
     * Stations named in free text, in order, each with the word before it: exact names (any number of words, aliases
     * included) or a single capitalised word starting a busy station's name ("Zürich"). Overlaps go to the longest.
     */
    fun mentions(text: String): List<Pair<Station, String?>> {
        val words = WORD.findAll(text).map { it.value.trimEnd('.') }.toList()
        val stations = all().values
        val out = ArrayList<Pair<Station, String?>>()
        var i = 0
        while (i < words.size) {
            var found: Pair<Station, Int>? = null
            for (len in minOf(4, words.size - i) downTo 1) {
                val raw = words.subList(i, i + len).joinToString(" ")
                val q = cleanQuery(raw)
                if (q.length < 3 || (len == 1 && normalize(raw) in ARTICLES)) continue
                val hit = rank(q, stations).firstOrNull() ?: continue
                val n = normalized.getOrPut(hit.name) { normalize(hit.name) }
                val capitalised = words[i].first().isUpperCase()
                if (n == q || (len == 1 && capitalised && q.length >= 4 && n.startsWith("$q "))) { found = hit to len; break }
            }
            if (found != null) {
                out += found.first to words.getOrNull(i - 1)?.lowercase()
                i += found.second
            } else i++
        }
        return out.distinctBy { it.first.stopId }
    }

    /** Closest station to a point, with its distance in km, if one is within [maxKm]. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 2.0): Pair<Station, Double>? =
        all().values.asSequence()
            .filter { it.lat != null && it.lon != null }
            .map { it to distanceKm(lat, lon, it.lat!!, it.lon!!) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }

    private val normalized = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun rank(q: String, stations: Collection<Station>): List<Station> {
        val words = q.split(' ')
        return stations.asSequence()
            .mapNotNull { st ->
                val n = normalized.getOrPut(st.name) { normalize(st.name) }
                val tier = when {
                    n == q -> 0
                    n.startsWith("$q ") -> 1
                    n.startsWith(q) -> 2
                    n.split(' ').let { nw -> words.all { w -> nw.any { it.startsWith(w) } } } -> 3
                    n.contains(q) -> 4
                    else -> return@mapNotNull null
                }
                Triple(st, tier, state.trainsCallingAt(st.stopId).size)
            }
            .sortedWith(compareBy<Triple<Station, Int, Int>>({ it.second }, { -it.third }, { it.first.name.length }))
            .map { it.first }
            .toList()
    }

    companion object {
        private const val CACHE_MS = 60_000L
        private val UIC = Regex("\\d{7}")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’./-]*")
        private val ARTICLES = setOf("the", "der", "die", "das", "le", "la", "les", "l", "il", "lo", "gare", "bahnhof", "station")

        /** Words that only say "station" (the names in the catalog don't carry them). */
        private val FILLER = setOf("station", "gare", "bahnhof", "stazione", "railway", "train", "bhf")
        private val MAIN_STATION = listOf(
            "hauptbahnhof", "hbf", "main station", "central station", "gare centrale", "stazione centrale", "centrale",
        )

        /** Other-language and English names -> the catalog's name (normalized). */
        private val ALIASES = mapOf(
            "geneva" to "geneve", "genf" to "geneve", "ginevra" to "geneve", "cornavin" to "geneve",
            "geneve cornavin" to "geneve",
            "geneva airport" to "geneve aeroport", "genf flughafen" to "geneve aeroport", "geneve airport" to "geneve aeroport",
            "zurich airport" to "zurich flughafen", "zurich aeroport" to "zurich flughafen", "zuerich" to "zurich",
            "zurigo" to "zurich", "zuerich flughafen" to "zurich flughafen",
            "lucerne" to "luzern", "lucerna" to "luzern",
            "berne" to "bern", "berna" to "bern",
            "bale" to "basel sbb", "basle" to "basel sbb", "basilea" to "basel sbb", "basel" to "basel sbb",
            "losanna" to "lausanne",
            "sitten" to "sion",
            "brigue" to "brig", "briga" to "brig",
            "freiburg" to "fribourg freiburg", "fribourg" to "fribourg freiburg", "friburgo" to "fribourg freiburg",
            "biel" to "biel bienne", "bienne" to "biel bienne",
            "neuenburg" to "neuchatel",
            "saint gall" to "st gallen", "st gall" to "st gallen", "san gallo" to "st gallen", "sankt gallen" to "st gallen",
            "saint gallen" to "st gallen",
            "coire" to "chur", "coira" to "chur",
            "bellinzone" to "bellinzona",
            "schaffhouse" to "schaffhausen", "sciaffusa" to "schaffhausen",
            "soleure" to "solothurn", "soletta" to "solothurn",
            "thoune" to "thun",
            "yverdon" to "yverdon les bains",
            "winterthour" to "winterthur",
            "zoug" to "zug", "zugo" to "zug",
        )

        fun cleanQuery(raw: String): String {
            var q = " " + normalize(raw) + " "
            for (m in MAIN_STATION) q = q.replace(" $m ", " hb ")
            val words = q.trim().split(' ').filter { it.isNotEmpty() }
            val kept = words.filter { it !in FILLER }.let { if (it.isEmpty()) words else it }
                .let { if (it.size > 1 && it.first() in setOf("de", "di", "of")) it.drop(1) else it }
            val joined = kept.joinToString(" ")
            return ALIASES[joined] ?: ALIASES[joined.removeSuffix(" hb")]?.let { if (joined.endsWith(" hb")) "$it hb" else it } ?: joined
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
