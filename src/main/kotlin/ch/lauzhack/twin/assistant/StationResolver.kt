package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.distanceKm
import ch.lauzhack.twin.model.Station
import ch.lauzhack.twin.query.StationSearch
import ch.lauzhack.twin.state.LiveState

/**
 * Station search for the assistant over [StationSearch] (the matching shared with the REST API and the passenger app);
 * ties go to the busier station (more trains calling), so "Zürich" is Zürich HB. Adds what the assistant needs on top:
 * unknown UIC ids, a station named with confidence after "from"/"nach"/…, stations mentioned in free text.
 *
 * Catalog = [LiveState.stations]: the stations of the trains in service plus every station seen before (seeded from
 * SQLite at startup, see [LiveState.seedStations]).
 */
class StationResolver(private val state: LiveState) {
    @Volatile private var cached: Pair<Long, Map<String, Station>> = 0L to emptyMap()

    fun all(): Map<String, Station> {
        val now = System.currentTimeMillis()
        val (at, map) = cached
        val live = state.stations()
        if (now - at < CACHE_MS && map.size >= live.size) return map
        val merged = live.associateByTo(HashMap()) { it.stopId }
        cached = now to merged
        return merged
    }

    fun byId(stopId: String): Station? = state.station(stopId)

    /** Best matches for a name (or an exact stopId), best first. */
    fun search(query: String, limit: Int = 5): List<Station> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        byId(raw)?.let { return listOf(it) }
        return StationSearch.rank(raw, all().values, { it.name }, ::busy).take(limit)
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
        val words = StationSearch.cleanQuery(phrase).split(' ').filter { it.isNotEmpty() }.take(8)
        val stations = all().values
        for (skip in 0..minOf(1, words.size - 1)) {
            if (skip == 1 && words[0] !in ARTICLES) break
            for (len in (words.size - skip) downTo 1) {
                val q = StationSearch.cleanQuery(words.subList(skip, skip + len).joinToString(" "))
                if (q.length < 3) continue
                val hit = rank(q, stations).firstOrNull() ?: continue
                if (StationSearch.namesStart(q, hit.name)) return hit
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
                val q = StationSearch.cleanQuery(raw)
                if (q.length < 3 || (len == 1 && StationSearch.normalize(raw) in ARTICLES)) continue
                val hit = rank(q, stations).firstOrNull() ?: continue
                val capitalised = words[i].first().isUpperCase()
                val exact = StationSearch.tier(q, hit.name) == 0
                if (exact || (len == 1 && capitalised && q.length >= 4 && StationSearch.namesStart(q, hit.name))) { found = hit to len; break }
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

    private fun rank(q: String, stations: Collection<Station>) = StationSearch.rankClean(q, stations, { it.name }, ::busy)

    private fun busy(st: Station) = state.trainsCallingAt(st.stopId).size

    companion object {
        private const val CACHE_MS = 60_000L
        private val UIC = Regex("\\d{7}")

        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’./-]*")
        private val ARTICLES = setOf("the", "der", "die", "das", "le", "la", "les", "l", "il", "lo", "gare", "bahnhof", "station")
    }
}
