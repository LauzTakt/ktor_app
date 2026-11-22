package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.EMPTY_ARRAY
import ch.lauzhack.twin.common.array
import ch.lauzhack.twin.common.coordsOf
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.geometry
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.spacedLine
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.common.truthy
import ch.lauzhack.twin.common.urlEncode
import ch.lauzhack.twin.query.Severity
import ch.lauzhack.twin.query.lineIn
import ch.lauzhack.twin.twinapi.CachedJourney
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.PlanQuery
import ch.lauzhack.twin.twinapi.SbbTwinClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** contract.ts `BriefDisruption`. */
internal data class BriefDisruption(
    val id: String, val kind: String, val title: String, val where: String?, val effect: String?, val cause: String?,
    val until: String?, val text: String, val measures: List<String>, val replacementBus: Boolean, val severity: String,
    val active: Boolean, val onRoute: Boolean? = null,
) {
    fun json(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("id", id); put("kind", kind); put("title", title); put("where", where); put("effect", effect); put("cause", cause)
        put("until", until); put("text", text); putJsonArray("measures") { measures.forEach { add(it) } }
        put("replacementBus", replacementBus); put("severity", severity); put("active", active)
        if (onRoute != null) put("onRoute", onRoute)
        extra()
    }
}

/** contract.ts `BriefJourney`. */
internal data class BriefJourney(
    val journeyId: String, val durationMin: Int, val uncertain: Boolean, val departs: String, val arrives: String,
    val transfers: Int, val lateMin: Int, val legs: List<String>, val warnings: List<String>,
) {
    fun json() = buildJsonObject {
        put("durationMin", durationMin); put("uncertain", uncertain); put("journeyId", journeyId); put("departs", departs); put("arrives", arrives)
        put("transfers", transfers); put("lateMin", lateMin)
        putJsonArray("legs") { legs.forEach { add(it) } }; putJsonArray("warnings") { warnings.forEach { add(it) } }
    }
}

/** `GET /api/brief` query (station | from+to | line | q free text | lat+lon). */
data class BriefQuery(
    val station: String? = null, val from: String? = null, val to: String? = null, val line: String? = null,
    val q: String? = null, val lat: String? = null, val lon: String? = null,
)

/**
 * The place brief (port of sbb-client `server/brief.ts`): everything a passenger should know about a station, a line
 * or a route, built in well under a second from the twin, BEFORE any slow model call. Source: the twin's
 * `/api/brief` when it answers, otherwise composed from `/api/incidents`, `/api/alerts`, `/api/disruptions`,
 * `/api/plan` and `/api/situation`.
 */
class PlaceBrief(
    internal val twin: SbbTwinClient,
    internal val catalog: StationCatalog,
    internal val now: () -> Long = System::currentTimeMillis,
    internal val journeyCache: JourneyCache? = null,
) {
    internal val log = LoggerFactory.getLogger("brief")
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal class Picture(val incidents: List<JsonObject>, val alerts: List<JsonObject>, val disruptions: List<JsonObject>)

    // ---------- the live picture (shared by every brief, 5 s) ----------
    internal var live: Pair<Long, Deferred<Picture>>? = null
    internal val lock = Any()

    internal suspend fun livePicture(): Picture {
        val d = synchronized(lock) {
            live?.takeIf { now() - it.first <= 5_000 }?.second ?: scope.async {
                coroutineScope {
                    suspend fun list(p: String) = try { twin.json(p, 4_000).objects() } catch (e: Exception) { emptyList() }
                    val i = async { list("/api/incidents") }
                    val a = async { list("/api/alerts") }
                    val ds = async { list("/api/disruptions") }
                    Picture(i.await(), a.await(), ds.await())
                }
            }.also { live = now() to it }
        }
        return d.await()
    }

    // ---------- the twin's /api/brief ----------
    internal fun twinIncident(d: JsonObject): JsonObject {
        val effect = d.str("effect")
        val ended = d.str("lifecycle") == "ENDED" || effect == "resumed"
        return buildJsonObject {
            put("id", d.str("id")); put("title", d.str("title")); put("description", d.str("description"))
            put("severity", Severity.fromEffect(effect).id)
            put("source", if (d.str("kind") == "control") "control" else d.str("kind") ?: "control")
            put("effect", effect); put("cause", d["cause"] ?: JsonNull); put("lines", d["lines"] ?: EMPTY_ARRAY)
            put("durationType", d.str("durationType")); put("until", d.long("until")); put("lifecycle", d.str("lifecycle"))
            put("messages", d["messages"] ?: EMPTY_ARRAY); put("replacementBus", d.truthy("replacementBus"))
            put("status", if (ended) "ended" else "active"); put("updatedAt", now())
        }
    }

    /**
     * The station nearest to a position, `{id, name, distanceM}`: our own catalog (same rule as `GET /api/nearest`,
     * instant, works without sbb-twin). It used to ask the twin's brief with a 2 s timeout, which lost the place
     * whenever the twin was slow.
     */
    internal suspend fun nearestStation(lat: Double, lon: Double): JsonObject? {
        val (st, km) = Places.nearest(catalog.list(), lat, lon) ?: return null
        return buildJsonObject { put("id", st.id); put("name", st.name); put("distanceM", (km * 1000).roundToInt()) }
    }

    // ---------- resolving the place ----------
    internal suspend fun resolve(raw: String?): CatalogStation? {
        val s = raw?.trim()
        if (s.isNullOrEmpty()) return null
        val list = catalog.list()
        if (Regex("^\\d{7}$").matches(s)) return list.find { it.id == s }
        list.find { it.name.lowercase() == s.lowercase() }?.let { return it }
        return Places.placesIn(s, list).firstOrNull()?.st
    }

    internal data class Resolved(val station: CatalogStation?, val from: CatalogStation?, val to: CatalogStation?, val line: String?)

    internal suspend fun resolveQuery(q: BriefQuery): Resolved {
        var station = resolve(q.station)
        var from = resolve(q.from)
        var to = resolve(q.to)
        var line = q.line?.trim()?.takeIf { it.isNotEmpty() }
        val text = q.q?.takeIf { it.isNotEmpty() }
        if (text != null && station == null && from == null && to == null) {
            // free text (what the passenger just said): the stations it names, with the role their preceding word suggests
            val places = Places.placesIn(text, catalog.list())
            to = places.find { it.role == "to" }?.st
            from = places.find { it.role == "from" && it.st != to }?.st
            val rest = places.map { it.st }.filter { it != to && it != from }
            if (from == null && to == null && rest.size >= 2) { from = rest[0]; to = rest[1] }
            else if (from == null && to == null && rest.size == 1) station = rest[0]
            else if (to == null && rest.isNotEmpty()) to = rest.last()
            else if (from == null && rest.isNotEmpty()) from = rest[0]
            if (line == null) line = lineIn(text)
        }
        if (text != null && from != null && to == null) {
            // "How do I get to Aarau now?" with the passenger's station as the origin
            val places = Places.placesIn(text, catalog.list()).filter { it.st.id != from!!.id }
            to = (places.find { it.role == "to" } ?: places.lastOrNull())?.st
        }
        if (from != null && to == null && station == null) { station = from; from = null }
        if (to != null && from == null && station == null) { station = to; to = null }
        if (from != null && to != null && from.id == to.id) { station = from; from = null; to = null }
        return Resolved(station, from, to, line)
    }

    // ---------- the brief ----------
    internal val cache = HashMap<String, Pair<Long, Deferred<JsonObject>>>()

    /** The brief for [q], or null when no station, route or line is recognised. Cached 20 s per place. */
    suspend fun placeBrief(q: BriefQuery): JsonObject? {
        // a position: the nearest station is the place (and the origin when a destination is given)
        if (!q.lat.isNullOrEmpty() && !q.lon.isNullOrEmpty() && q.station.isNullOrEmpty() && q.from.isNullOrEmpty()) {
            val lat = q.lat.toDoubleOrNull() ?: return null
            val lon = q.lon.toDoubleOrNull() ?: return null
            val n = nearestStation(lat, lon) ?: return null
            val id = n.str("id") ?: return null
            val next = (if (!q.to.isNullOrEmpty()) q.copy(from = id) else q.copy(station = id)).copy(lat = null, lon = null)
            val b = placeBrief(next) ?: return null
            return JsonObject(b + ("nearest" to buildJsonObject { put("id", id); put("name", n.str("name")); put("distanceM", n["distanceM"] ?: JsonNull) }))
        }
        val r = resolveQuery(q)
        if (r.station == null && !(r.from != null && r.to != null) && r.line == null) return null
        val key = if (r.from != null && r.to != null) "route:${r.from.id}:${r.to.id}"
            else if (r.station != null) "station:${r.station.id}:${r.line ?: ""}" else "line:${r.line}"
        val d = synchronized(cache) {
            val t = now()
            cache[key]?.takeIf { t - it.first < 20_000 }?.second ?: scope.async { build(key, r) }.also { deferred ->
                cache[key] = t to deferred
                deferred.invokeOnCompletion { e -> if (e != null) synchronized(cache) { if (cache[key]?.second === deferred) cache.remove(key) } }
                cache.entries.removeIf { t - it.value.first > 60_000 }
            }
        }
        return d.await()
    }

    internal fun serious(d: BriefDisruption) = d.active && (d.effect == "interrupted" || d.effect == "limited" || d.severity == "critical" || d.severity == "major")

    internal suspend fun build(key: String, r: Resolved): JsonObject = coroutineScope {
        val t0 = now()
        val from = r.from
        val to = r.to
        val st = r.station
        val route = from != null && to != null
        val qs = if (route) "from=${urlEncode(from!!.id)}&to=${urlEncode(to!!.id)}" else if (st != null) "station=${urlEncode(st.id)}" else "line=${urlEncode(r.line!!)}"
        // the twin's own brief (~50 ms): its disruptions and alerts are already filtered to the place or the route
        val twinBriefD = async {
            if (r.line != null && st == null && from == null) null
            else try { twin.json("/api/brief?$qs", 1_500).obj() } catch (e: Exception) { null }
        }
        val pic = livePicture()
        val tb = twinBriefD.await()
        val fromTwin = tb?.let {
            (it.objects("disruptions").map { d -> fromIncident(twinIncident(d)) } + it.objects("alerts").filter { a -> alertActive(a) }.map { a -> fromAlert(a) })
                .map { d -> d.copy(onRoute = route) }
        }
        var disruptions: List<BriefDisruption>
        var journeys: List<BriefJourney> = emptyList()
        var visuals: JsonArray
        var situation: JsonArray
        var options: JsonArray = EMPTY_ARRAY
        val place: String

        if (route) {
            from!!; to!!
            place = "${from.name} to ${to.name}"
            val planD = async {
                val tj = tb?.get("journeys") as? JsonArray
                tj ?: try { twin.json("/api/plan?$qs&limit=4", 4_000).obj().array("journeys") } catch (e: Exception) { EMPTY_ARRAY }
            }
            val extrasD = async {
                try {
                    routeExtras(twin, from, to) { journey ->
                        journeyCache?.put(
                            CachedJourney(journey, PlanQuery(from.id, to.id, null, 4, null), null, now()),
                            preferExisting = true,
                        )
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }
            val sitD = async { try { twin.json("/api/situation?$qs", 3_000).obj() } catch (e: Exception) { null } }
            val plan = planD.await().objects()
            val extras = extrasD.await()
            val sit = sitD.await()
            val ids = plan.flatMap { j -> j.objects("legs").flatMap { l -> l.objects("incidents").mapNotNull { it.str("id") } } }.toSet()
            // what lies on the way: the journeys' incidents and warnings, and the twin's onTheWay
            val warned = plan.take(3).flatMap { it.strings("warnings") }.toSet()
            val onTheWay = sit.strings("onTheWay")
            fun onWay(d: BriefDisruption) = d.id in ids || onTheWay.any { it.replace(" - ", " – ") == d.title } ||
                warned.any { it.replace(" - ", " – ").contains(d.title) }
            disruptions = fromTwin?.map { it.copy(onRoute = onWay(it)) }
                ?: relevant(pic, listOf(from.id, to.id), r.line, ids, onTheWay.toSet())
            val usable = plan.filter { j -> j.objects("legs").none { it.str("status") == "cancelled" } }.take(3)
            // a plausibility check: a bus ride (or any trip) far slower than the distance allows is shown as uncertain
            val km = hypot((to.lon - from.lon) * 76, (to.lat - from.lat) * 111)
            fun uncertain(j: JsonObject): Boolean {
                val min = ((j.long("arrival") ?: 0) - (j.long("departure") ?: 0)) / 60_000.0
                val busSlow = j.objects("legs").any { l ->
                    val lf = l.obj("from"); val lt = l.obj("to")
                    val legKm = hypot(((lt.double("lon") ?: 0.0) - (lf.double("lon") ?: 0.0)) * 76, ((lt.double("lat") ?: 0.0) - (lf.double("lat") ?: 0.0)) * 111)
                    val legMin = ((lt.long("expectedArr") ?: lt.long("plannedArr") ?: 0) - (lf.long("expectedDep") ?: lf.long("plannedDep") ?: 0)) / 60_000.0
                    l.str("category") == "EV" && legMin > max(45.0, 2.5 * legKm * 0.75)
                }
                return busSlow || (km < 150 && min > 180 && j.objects("legs").any { it.str("category") == "EV" })
            }
            journeys = usable.sortedBy { uncertain(it) }.map { j ->
                val dep = j.long("departure") ?: 0
                val arr = j.long("arrival") ?: 0
                val late = j.double("arrivalDelayMin")?.toInt() ?: 0
                BriefJourney(
                    journeyId = j.str("id") ?: "",
                    durationMin = ((arr - dep) / 60_000.0).roundToInt(),
                    uncertain = uncertain(j),
                    departs = hhmm(dep), arrives = hhmm(arr),
                    transfers = j.double("transfers")?.toInt() ?: 0,
                    lateMin = if (late > 0) late else 0,
                    legs = j.objects("legs").map { l ->
                        val lf = l.obj("from"); val lt = l.obj("to")
                        val name = if (l.str("category") == "EV") "replacement bus (EV)" else spacedLine(l.str("line") ?: "")
                        "$name ${lf.str("name")} ${hhmm(lf.long("expectedDep") ?: lf.long("plannedDep") ?: dep)} → ${lt.str("name")} ${hhmm(lt.long("expectedArr") ?: lt.long("plannedArr") ?: arr)}"
                    },
                    warnings = j.strings("warnings").take(3),
                )
            }
            visuals = extras?.visuals ?: EMPTY_ARRAY
            situation = extras?.situation ?: EMPTY_ARRAY
            options = extras?.options ?: EMPTY_ARRAY
        } else {
            place = if (st != null) (if (r.line != null) "${spacedLine(r.line)} at ${st.name}" else st.name) else "the ${spacedLine(r.line!!)}"
            disruptions = if (fromTwin != null && r.line == null) fromTwin else relevant(pic, listOfNotNull(st?.id), r.line)
            situation = JsonArray(disruptions.map { d ->
                buildJsonObject {
                    put("id", d.id); put("title", d.title); put("where", d.where)
                    put("severity", Severity.parse(d.severity).id)
                    put("source", when (d.kind) { "operator" -> "staff"; "detected" -> "auto"; else -> "sbb" })
                }
            })
            val vs = mutableListOf<JsonObject>(buildJsonObject { put("type", "clear") })
            val coords = mutableListOf<Pair<Double, Double>>()
            fun geoOf(id: String): JsonElement? = pic.incidents.find { it.str("id") == id }.geometry() ?: pic.alerts.find { it.str("id") == id }.geometry()
            for (d in disruptions.filter { it.effect != "train" }.take(3)) {
                val g = geoOf(d.id).obj() ?: continue
                if (g.str("type") != "MultiLineString" && g.str("type") != "LineString") continue
                vs += buildJsonObject {
                    put("type", "section"); put("id", d.id)
                    put("colour", if (d.effect == "interrupted" || d.severity == "critical") "red" else if (d.active) "orange" else "green")
                    put("label", d.title); put("geometry", g)
                }
                coords += coordsOf(g)
            }
            if (st != null) {
                vs += stationVisual(st, if (disruptions.any { serious(it) }) "incident" else "origin", st.name)
                coords += st.lon to st.lat
            }
            if (coords.isNotEmpty()) {
                val pad = if (coords.size == 1) 0.04 else 0.0
                vs += buildJsonObject {
                    put("type", "focus")
                    putJsonArray("bbox") {
                        add(coords.minOf { it.first } - pad); add(coords.minOf { it.second } - pad)
                        add(coords.maxOf { it.first } + pad); add(coords.maxOf { it.second } + pad)
                    }
                }
            }
            visuals = JsonArray(vs)
        }

        // a route is concerned only by what lies on its way, not by a closure elsewhere that merely touches the origin;
        // a line alone is never enough (the back office answers "is my train affected" from the train itself)
        val related = if (route) disruptions.any { serious(it) && it.onRoute == true } else if (st != null) disruptions.any { serious(it) } else false
        if (route) disruptions = disruptions.sortedByDescending { it.onRoute == true }
        val sayList = if (route) disruptions.filter { it.onRoute == true || !serious(it) } else disruptions
        val out = buildJsonObject {
            put("key", key)
            put("place", place)
            put("station", st?.let { buildJsonObject { put("id", it.id); put("name", it.name) } } ?: JsonNull)
            put("from", from?.let { buildJsonObject { put("id", it.id); put("name", it.name) } } ?: JsonNull)
            put("to", to?.let { buildJsonObject { put("id", it.id); put("name", it.name) } } ?: JsonNull)
            put("line", r.line)
            put("related", related)
            put("disruptions", JsonArray(disruptions.map { it.json() }))
            put("journeys", JsonArray(journeys.map { it.json() }))
            put("say", sayFor(place, sayList, journeys, to?.name))
            put("shown", shownFor(visuals))
            put("situation", situation)
            put("options", options)
            put("visuals", visuals)
            put("twin", JsonNull)
            putJsonArray("departures") {
                for (d in tb.objects("departures").take(6)) addJsonObject {
                    put("line", d.str("line")); put("headsign", d.str("headsign"))
                    put("time", hhmm(d.long("expected") ?: d.long("planned") ?: 0))
                    put("delayMin", d["delayMin"]?.takeIf { it !is JsonNull } ?: JsonPrimitive(0)); put("cancelled", d.truthy("cancelled"))
                }
            }
            put("nearest", tb.obj("nearest") ?: JsonNull)
            put("source", if (tb != null) "twin-brief" else "composed")
            put("elapsedMs", now() - t0)
        }
        log.debug("brief {} built in {} ms", key, now() - t0)
        out
    }

    // ---------- the list the agent gets at the start of a conversation ----------
    @Volatile private var activeCache: Pair<Long, JsonObject>? = null

    /** `GET /api/brief/active`: the active disruptions worth mentioning, as text for the agent and as items. */
    suspend fun activeList(): JsonObject {
        activeCache?.takeIf { now() - it.first < 20_000 }?.let { return it.second }
        val pic = livePicture()
        val items = (pic.incidents.filter { isOpen(it) && it.str("source") == "control" }.map { fromIncident(it) } +
            pic.alerts.filter { alertActive(it) && it.str("severity") != "info" }.map { fromAlert(it) })
            .filter { it.effect == "interrupted" || it.effect == "limited" || it.severity == "major" || it.severity == "critical" }
            .sortedWith(compareBy<BriefDisruption> { if (it.kind == "operator") 0 else 1 }.thenBy { if (it.effect == "interrupted") 0 else 1 })
        val top = items.take(30)
        val text = if (top.isNotEmpty()) top.joinToString("\n") { d ->
            "- ${d.title}${d.cause?.let { " ($it)" } ?: ""}${d.until?.let { ", $it" } ?: ""}${if (d.replacementBus) ", replacement buses" else ""}"
        } else "- No disruption is reported on the network right now."
        val body = buildJsonObject {
            put("updatedAt", now()); put("count", top.size); put("text", text)
            putJsonArray("items") { top.forEach { d -> addJsonObject { put("id", d.id); put("title", d.title); put("until", d.until) } } }
        }
        activeCache = now() to body
        return body
    }

    /** `GET /api/brief/item?id=`: one disruption by id (a tapped section), its bulletin in plain words. */
    suspend fun disruptionById(id: String): JsonObject? {
        val pic = livePicture()
        pic.incidents.find { it.str("id") == id || it.str("disruptionId") == id }?.let { inc ->
            val lines = inc.array("lines")
            return fromIncident(inc).json { put("lines", JsonArray(lines.take(12))); put("moreLines", max(0, lines.size - 12)) }
        }
        val a = pic.alerts.find { it.str("id") == id } ?: return null
        return fromAlert(a).json { put("lines", buildJsonArray { }); put("moreLines", 0) }
    }
}
