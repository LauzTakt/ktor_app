package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.EMPTY_ARRAY
import ch.lauzhack.twin.common.array
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.enc
import kotlin.math.roundToLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What a route brief draws and lists: map visuals, the situation on the way, the options (contract.ts shapes). */
internal class RouteExtras(val visuals: JsonArray, val situation: JsonArray, val options: JsonArray)

private val CAUSES = mapOf(
    "construction" to "construction work", "technical_problem" to "a technical problem", "accident" to "an accident",
    "weather" to "the weather", "strike" to "a strike", "maintenance" to "maintenance work",
)
private val SEVERITIES = listOf("critical", "major", "minor", "info")
private fun severityOf(s: String?) = if (s == "critical" || s == "major" || s == "minor") s else "info"
private fun colourOf(s: String) = if (s == "critical" || s == "major") "red" else if (s == "minor") "orange" else "blue"
internal fun cleanTitle(t: String) = t.replace(" - ", " – ")

private class Sit(val id: String, val title: String, val where: String?, val severity: String, val source: String?, var geometry: JsonElement?) {
    fun json() = buildJsonObject { put("id", id); put("title", title); put("where", where); put("severity", severity); put("source", source) }
}

/** A two-point LineString is the twin's straight stand-in when it has no track path: not worth drawing. */
private fun isLines(g: JsonElement?): Boolean {
    val x = g.obj() ?: return false
    return x.str("type") == "MultiLineString" || (x.str("type") == "LineString" && x.array("coordinates").size > 2)
}

internal fun focusOn(points: List<Pair<Double, Double>>): JsonObject = buildJsonObject {
    put("type", "focus")
    putJsonArray("bbox") { add(points.minOf { it.first }); add(points.minOf { it.second }); add(points.maxOf { it.first }); add(points.maxOf { it.second }) }
}

internal fun stationVisual(s: CatalogStation, role: String, label: String) = buildJsonObject {
    put("type", "station"); put("stopId", s.id); put("name", s.name); put("lon", s.lon); put("lat", s.lat); put("role", role); put("label", label)
}

private fun stopTime(stop: JsonObject?, vararg keys: String): Long? = keys.firstNotNullOfOrNull { stop.long(it) }

/**
 * The route part of sbb-client's mock answer (`server/mock.ts`, mockAsk with a from and a to): the twin's journeys
 * as routes on the real track, the disruptions on the way as sections, stations with their times, the first train,
 * a countdown and the options. Throws when the twin's planner fails (the caller then has no extras).
 */
internal suspend fun routeExtras(
    twin: SbbTwinClient, from: CatalogStation, to: CatalogStation, onJourney: (JsonObject) -> Unit = {},
): RouteExtras = coroutineScope {
    val planD = async { twin.json("/api/plan?from=${enc(from.id)}&to=${enc(to.id)}&limit=4").obj() }
    val alertsD = async { runCatching { twin.json("/api/alerts").objects() }.getOrDefault(emptyList()) }
    val incidentsD = async { runCatching { twin.json("/api/incidents").objects() }.getOrDefault(emptyList()) }
    val plan = planD.await()
    val alerts = alertsD.await()
    val incidents = incidentsD.await()
    val journeys = plan.objects("journeys").filter { j -> j.objects("legs").none { it.str("status") == "cancelled" } }
    val rec = journeys.firstOrNull()
    val alts = journeys.drop(1).take(2)

    // what matters on the way: incidents on the legs of the shown journeys, then those at either end
    val seen = LinkedHashMap<String, Sit>()
    fun note(id: String, title: String, severity: String?, source: String?, where: String?) {
        if (id in seen) return
        val a = alerts.find { it.str("id") == id }
        val inc = incidents.find { it.str("id") == id }
        val cause = a.str("cause")?.let { CAUSES[it] }
        seen[id] = Sit(
            id, cleanTitle(title), where ?: inc.str("where") ?: cause?.capitalised(), severityOf(severity),
            source ?: if (id.startsWith("sa-")) "sbb" else inc.str("source") ?: "staff",
            a.geometry() ?: inc.geometry(),
        )
    }
    for (j in listOfNotNull(rec) + alts) for (l in j.objects("legs")) for (i in l.objects("incidents")) {
        note(i.str("id") ?: continue, i.str("title") ?: "", i.str("severity"), i.str("source"), null)
    }
    for (inc in incidents) {
        val sc = inc.obj("scope")
        if (inc.str("status") != "ended" && sc.str("kind") != "train" &&
            listOf(sc.str("stopId"), sc.str("from"), sc.str("to")).any { it == from.id || it == to.id }
        ) note(inc.str("id") ?: continue, inc.str("title") ?: "", inc.str("severity"), inc.str("source"), inc.str("where"))
    }
    val situation = seen.values.sortedBy { SEVERITIES.indexOf(it.severity) }

    val visuals = mutableListOf<JsonObject>(buildJsonObject { put("type", "clear") })
    // the track of each disruption: its own lines, else the track between the first two stations the alert names
    situation.take(2).map { s ->
        async {
            if (!isLines(s.geometry)) {
                val st = alerts.find { it.str("id") == s.id }.objects("stations").filter { it.str("id") != null }
                if (st.size >= 2) {
                    val g = runCatching { twin.json("/api/geometry/section?from=${enc(st[0].str("id")!!)}&to=${enc(st[1].str("id")!!)}").obj() }.getOrNull()
                    s.geometry = g.geometry() ?: s.geometry
                }
            }
            s
        }
    }.awaitAll().forEach { s ->
        if (isLines(s.geometry)) visuals += buildJsonObject {
            put("type", "section"); put("id", s.id); put("colour", colourOf(s.severity)); put("label", s.title); put("geometry", s.geometry!!)
        }
    }
    val situationJson = JsonArray(situation.map { it.json() })

    if (rec == null) {
        visuals += stationVisual(from, "origin", from.name)
        visuals += stationVisual(to, "destination", to.name)
        visuals += focusOn(listOf(from.lon to from.lat, to.lon to to.lat))
        return@coroutineScope RouteExtras(JsonArray(visuals), situationJson, EMPTY_ARRAY)
    }

    // real track geometry for every journey shown
    val shown = listOf(rec) + alts
    shown.forEach(onJourney)
    val geos = shown.map { j -> async { runCatching { twin.json("/api/geometry/journey/${enc(j.str("id") ?: "")}").obj() }.getOrNull() } }.awaitAll()
    fun routeOf(j: JsonObject, i: Int, style: String) = buildJsonObject {
        val legs = j.objects("legs")
        put("type", "route"); put("id", j.str("id")); put("style", style)
        put("label", "${spaced(legs.firstOrNull().str("line") ?: "")} → ${to.name}, ${hhmm(j.long("departure") ?: 0)}")
        putJsonArray("legs") {
            legs.forEachIndexed { k, l ->
                addJsonObject {
                    put("line", l.str("line")); put("trainId", l.str("trainId"))
                    put("fromName", l.obj("from").str("name")); put("toName", l.obj("to").str("name"))
                    val g = geos[i].objects("legs").getOrNull(k).geometry()
                    put("geometry", g ?: buildJsonObject {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            add(buildJsonArray { add(l.obj("from").double("lon")); add(l.obj("from").double("lat")) })
                            add(buildJsonArray { add(l.obj("to").double("lon")); add(l.obj("to").double("lat")) })
                        }
                    })
                }
            }
        }
    }
    alts.forEachIndexed { i, j -> visuals += routeOf(j, i + 1, "alternative") }
    visuals += routeOf(rec, 0, "recommended")
    // times at every stop of the recommended route: departure, each change (arrival -> departure, delays), arrival
    fun late(exp: Long?, plan: Long?) = if (exp != null && plan != null && exp != 0L && plan != 0L && exp - plan >= 120_000) " (+${((exp - plan) / 60_000.0).roundToLong()}′)" else ""
    val legs = rec.objects("legs")
    val f0 = legs.first().obj("from")
    val tN = legs.last().obj("to")
    val recDep = rec.long("departure") ?: 0
    val recArr = rec.long("arrival") ?: 0
    visuals += stationVisual(from, "origin", "${from.name} · ${hhmm(stopTime(f0, "expectedDep", "plannedDep") ?: recDep)}${late(f0.long("expectedDep"), f0.long("plannedDep"))}")
    visuals += stationVisual(to, "destination", "${to.name} · ${hhmm(stopTime(tN, "expectedArr", "plannedArr") ?: recArr)}${late(tN.long("expectedArr"), tN.long("plannedArr"))}")
    legs.drop(1).forEachIndexed { i, l ->
        val arr = legs[i].obj("to")
        val lf = l.obj("from")
        visuals += buildJsonObject {
            put("type", "station"); put("stopId", lf.str("id")); put("name", lf.str("name")); put("lon", lf.double("lon")); put("lat", lf.double("lat")); put("role", "transfer")
            put("label", "${lf.str("name")} · ${hhmm(stopTime(arr, "expectedArr", "plannedArr") ?: 0)} → ${hhmm(stopTime(lf, "expectedDep", "plannedDep") ?: 0)}${late(lf.long("expectedDep"), lf.long("plannedDep"))}")
        }
    }
    val first = legs.first()
    first.str("trainId")?.let { tid ->
        visuals += buildJsonObject { put("type", "train"); put("trainId", tid); put("label", "Your train · ${spaced(first.str("line") ?: "")}"); put("follow", false) }
    }
    visuals += buildJsonObject { put("type", "timer"); put("label", "${spaced(first.str("line") ?: "")} leaves in"); put("until", recDep) }
    val coords = mutableListOf(from.lon to from.lat, to.lon to to.lat)
    for (l in geos.firstOrNull().objects("legs")) collectCoords(l.geometry(), coords)
    visuals += focusOn(coords)

    val options = buildJsonArray {
        shown.forEachIndexed { i, j ->
            val jl = j.objects("legs")
            val transfers = j.double("transfers")?.toInt() ?: 0
            val delay = j.double("arrivalDelayMin")?.toInt() ?: 0
            addJsonObject {
                put("id", ('A' + i).toString())
                put("label", if (transfers == 0) "${spaced(jl[0].str("line") ?: "")} to ${jl[0].str("headsign")?.takeIf { it.isNotEmpty() } ?: to.name}"
                    else "${spaced(jl[0].str("line") ?: "")}, change at ${jl.getOrNull(1).obj("from").str("name")} to the ${spaced(jl.getOrNull(1).str("line") ?: "")}")
                put("departure", j.long("departure") ?: 0)
                put("arrival", j.long("arrival") ?: 0)
                put("transfers", transfers)
                put("recommended", i == 0)
                put("why", if (i == 0) (if (delay >= 3) "arrives first, about $delay min late" else "arrives first")
                    else "arrives ${(((j.long("arrival") ?: 0) - recArr) / 60_000.0).roundToLong()} min later")
                put("journeyId", j.str("id"))
            }
        }
    }
    RouteExtras(JsonArray(visuals), situationJson, options)
}
