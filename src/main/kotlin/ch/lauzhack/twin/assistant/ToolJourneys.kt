package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.twinapi.PlanQuery
import ch.lauzhack.twin.twinapi.CachedJourney
import ch.lauzhack.twin.twinapi.TwinException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import ch.lauzhack.twin.common.opt
import ch.lauzhack.twin.common.simplifyGeometry
import ch.lauzhack.twin.assistant.AssistantTools.Companion.twinItem

/* Data tools: journey planning (validated against our live data) and journey geometry. Part of [AssistantTools]. */

internal suspend fun AssistantTools.planJourney(input: JsonObject, turn: TurnState): ToolOutcome {
    val a = station(input.str("fromStopId"), "fromStopId")
    val b = station(input.str("toStopId"), "toStopId")
    if (a.stopId == b.stopId) throw ToolError("origin and destination are the same station (${a.name})")
    val q = PlanQuery(
        from = a.stopId, to = b.stopId, after = parseTime(input.str("after"), turn.now),
        limit = (input.int("limit") ?: 4).coerceIn(1, 6),
        onboardTrainId = input.str("onboardTrainId") ?: turn.request.context?.trainId?.takeIf { it.isNotBlank() },
    )
    val skip = offCorridor(a.stopId, b.stopId)
    val (r, list0) = planValidated(q)
    val list = list0.map { it.copy(journey = stripOffRoute(it.journey, skip)) }
    list.forEach { cj ->
        cj.journey.objects("legs").forEach { leg -> leg.objects("incidents").forEach { inc -> turn.note(twinItem(inc)) } }
        cj.journey.long("departure")?.let { turn.publishedDepartures += it }
    }
    val recommended = r.str("recommendedJourneyId")?.takeIf { id -> list.any { it.id == id } }
    return ok(buildJsonObject {
        put("from", buildJsonObject { put("stopId", r.obj("from")?.str("id") ?: a.stopId); put("name", r.obj("from")?.str("name") ?: a.name) })
        put("to", buildJsonObject { put("stopId", r.obj("to")?.str("id") ?: b.stopId); put("name", r.obj("to")?.str("name") ?: b.name) })
        opt("plannerRecommends", recommended)
        put("journeys", buildJsonArray { list.forEach { add(journeyJson(it.journey, recommended)) } })
        if (list.isEmpty()) put("note", "No connection found in the timetable the planner covers (the next hours).")
    })
}

/**
 * Plans and checks that every journey still has its geometry (the twin answers 404 for an expired or invalidated
 * journey); if one is missing, plans once more and keeps only journeys whose geometry exists.
 */
internal suspend fun AssistantTools.planValidated(q: PlanQuery): Pair<JsonObject, List<CachedJourney>> {
    var attempt = 0
    while (true) {
        val r = twin.plan(q.from, q.to, q.after, q.limit, q.onboardTrainId).obj() ?: throw ToolError("the planner returned nothing")
        val list = r.objects("journeys")
        val geometries = coroutineScope { list.map { j -> async { journeyGeometry(j) } }.awaitAll() }
        if (geometries.any { it is Geo.Missing } && attempt++ == 0) {
            log.info("journey geometry missing right after planning {} -> {}: planning again", q.from, q.to)
            continue
        }
        val now = System.currentTimeMillis()
        val kept = list.zip(geometries).filter { it.second !is Geo.Missing }.map { (j, g) ->
            CachedJourney(j, q, (g as? Geo.Found)?.legs, now).also { journeys.put(it) }
        }
        return r to kept
    }
}

internal sealed interface Geo {
    class Found(val legs: List<JsonElement?>) : Geo
    data object Missing : Geo
    data object Unknown : Geo
}

/** The track of each leg of a journey (simplified), from `/geometry/journey/{id}`; per-train fallback for a leg without one. */
internal suspend fun AssistantTools.journeyGeometry(j: JsonObject): Geo = coroutineScope {
    val id = j.str("id") ?: return@coroutineScope Geo.Unknown
    val geoLegs = try {
        twin.journeyGeometry(id).obj()?.objects("legs").orEmpty()
    } catch (e: TwinException) {
        return@coroutineScope if (e.status == 404) Geo.Missing else Geo.Unknown
    }
    val legs = j.objects("legs").mapIndexed { i, leg ->
        async {
            val g = geoLegs.getOrNull(i)?.get("geometry")?.takeIf { it is JsonObject }
                ?: leg.str("trainId")?.let { tid ->
                    runCatching { twin.trainGeometry(tid, leg.obj("from")?.str("id"), leg.obj("to")?.str("id")).obj()?.get("geometry") }.getOrNull()
                }
            simplifyGeometry(g)
        }
    }.awaitAll()
    Geo.Found(legs)
}

/** Same journey after a re-plan: same trains on every leg. */
internal fun AssistantTools.sameTrains(a: JsonObject, b: JsonObject) =
    a.objects("legs").map { it.str("trainId") ?: it.str("line") } == b.objects("legs").map { it.str("trainId") ?: it.str("line") }
