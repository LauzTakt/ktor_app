package ch.lauzhack.twin.query

import ch.lauzhack.twin.common.searchKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Station-name matching shared by every search: the REST API (`/stations?q=`, destinations), the assistant's
 * resolver and the passenger app's place matching. Names in any of the Swiss languages or English, without accents,
 * with or without "station"/"Hauptbahnhof". Pure: the stations are passed in, with how busy each one is.
 *
 * Ranking: exact name, then the name followed by more words, then a name starting with the query, then every query
 * word starting a name word, then substring; ties go to the busier station, then the shorter name.
 */
object StationSearch {
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val normalized = ConcurrentHashMap<String, String>()

    /** [searchKey] with punctuation as single spaces: "Biel/Bienne" -> "biel bienne". */
    fun normalize(s: String): String = searchKey(s).replace(NON_ALNUM, " ").trim()

    private fun norm(name: String) = normalized.getOrPut(name) { normalize(name) }

    /** Other-language, English and short names (normalized) -> the catalog's name (normalized). */
    val ALIASES: Map<String, String> = mapOf(
        "zurich" to "zurich hb", "zuerich" to "zurich hb", "zurigo" to "zurich hb",
        "zurich main station" to "zurich hb", "zurich hauptbahnhof" to "zurich hb",
        "zurich airport" to "zurich flughafen", "zurich aeroport" to "zurich flughafen", "zuerich flughafen" to "zurich flughafen",
        "geneva" to "geneve", "genf" to "geneve", "ginevra" to "geneve", "ginebra" to "geneve", "cornavin" to "geneve",
        "geneve cornavin" to "geneve",
        "geneva airport" to "geneve aeroport", "genf flughafen" to "geneve aeroport", "geneve airport" to "geneve aeroport",
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
        "epfl" to "renens vd", "interlaken" to "interlaken ost", "wil" to "wil sg", "rapperswil" to "rapperswil sg",
    )

    /** Words before a station name that make it the destination ("to Bern", "nach Bern", "pour Genève", …). */
    val TO_WORDS = setOf(
        "to", "towards", "into", "for", "nach", "bis", "zum", "zur", "richtung", "a", "pour", "vers", "jusqu'à",
        "para", "hacia", "per", "verso",
    )

    /** Words before a station name that make it the origin ("from Bern", "ab Bern", "depuis Genève", …). */
    val FROM_WORDS = setOf(
        "from", "leaving", "at", "in", "von", "ab", "im", "bei", "de", "depuis", "en", "da", "dal", "dalla", "desde",
    )

    /** Words that only say "station" (the names in the catalog don't carry them). */
    private val FILLER = setOf("station", "gare", "bahnhof", "stazione", "railway", "train", "bhf")
    private val MAIN_STATION = listOf(
        "hauptbahnhof", "hbf", "main station", "central station", "gare centrale", "stazione centrale", "centrale",
    )

    /** A query as the catalog writes names: normalized, filler words off, "Hauptbahnhof" -> "hb", aliases applied. */
    fun cleanQuery(raw: String): String {
        var q = " " + normalize(raw) + " "
        for (m in MAIN_STATION) q = q.replace(" $m ", " hb ")
        val words = q.trim().split(' ').filter { it.isNotEmpty() }
        val kept = words.filter { it !in FILLER }.let { if (it.isEmpty()) words else it }
            .let { if (it.size > 1 && it.first() in setOf("de", "di", "of")) it.drop(1) else it }
        val joined = kept.joinToString(" ")
        ALIASES[joined]?.let { return it }
        if (joined.endsWith(" hb")) ALIASES[joined.removeSuffix(" hb")]?.let { return if (it.endsWith(" hb")) it else "$it hb" }
        return joined
    }

    /** How well a cleaned query [q] matches a station name: 0 = exact … 4 = substring, null = no match. */
    fun tier(q: String, name: String): Int? {
        val n = norm(name)
        return when {
            n == q -> 0
            n.startsWith("$q ") -> 1
            n.startsWith(q) -> 2
            n.split(' ').let { nw -> q.split(' ').all { w -> nw.any { it.startsWith(w) } } } -> 3
            n.contains(q) -> 4
            else -> null
        }
    }

    /** Whether the cleaned query [q] is [name] itself or its first words ("zurich" for "Zürich HB"). */
    fun namesStart(q: String, name: String): Boolean = norm(name).let { it == q || it.startsWith("$q ") }

    /** [items] matching the cleaned query [q], best first (see the class comment). */
    fun <T> rankClean(q: String, items: Collection<T>, name: (T) -> String, trains: (T) -> Int = { 0 }): List<T> =
        items.asSequence()
            .mapNotNull { item -> tier(q, name(item))?.let { Triple(item, it, trains(item)) } }
            .sortedWith(compareBy<Triple<T, Int, Int>>({ it.second }, { -it.third }, { name(it.first).length }))
            .map { it.first }
            .toList()

    /** [items] matching a raw [query], best first; "X HB" falls back to "X" when no station is called that. */
    fun <T> rank(query: String, items: Collection<T>, name: (T) -> String, trains: (T) -> Int = { 0 }): List<T> {
        val q = cleanQuery(query)
        if (q.isEmpty()) return emptyList()
        val hits = rankClean(q, items, name, trains)
        return if (hits.isEmpty() && q.endsWith(" hb")) rankClean(q.removeSuffix(" hb"), items, name, trains) else hits
    }
}
