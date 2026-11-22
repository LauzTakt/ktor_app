package ch.lauzhack.twin.routing

import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.common.UnavailableException
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.Severity
import ch.lauzhack.twin.query.StationDto
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TrainLiveDto
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.twinapi.CachedJourney
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.PlanQuery
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.TwinException
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Best route from A to B. The sbb-twin planner (`/api/plan`, earliest arrival first, already aware of the twin's
 * disruptions) gives the candidates; we then check every leg against our live data (latest expected times,
 * cancellations, skipped stops, trains already gone, our active incidents), recompute the changes with those times
 * and rank by the arrival you can count on: expected arrival + a penalty per risk (see [Penalty]).
 *
 * The data sources are functions so tests run without HTTP or MQTT ([of] wires the real ones).
 */
class RoutePlanner(
    private val plan: suspend (PlanQuery) -> JsonObject,
    private val liveTrain: (String) -> TrainLiveDto?,
    private val trainIncidents: (String) -> List<IncidentDto>,
    private val journeys: JourneyCache? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Minutes added to the expected arrival, so a slightly later but safer route can win. */
    object Penalty {
        fun incident(s: Severity) = when (s) { Severity.CRITICAL -> 30; Severity.MAJOR -> 12; Severity.MINOR -> 4; Severity.INFO -> 0 }
        const val PER_CHANGE = 3
        const val TIGHT_CHANGE = 8
        const val DELAYED_LEG = 2 // a train already > 5 min late tends to lose more
    }

    /** A change shorter than this (minutes, on expected times) is at risk. */
    private val tightChangeMin = 3
    private val delayedLegSec = 5 * 60

    suspend fun routes(from: String, to: String, after: Long?, limit: Int): RoutePlanDto {
        if (from.isBlank() || to.isBlank()) throw InvalidInputException("bad_route", "from and to are required")
        if (from == to) throw InvalidInputException("same_station", "from and to are the same station")
        // Ask for a few more than shown: a candidate the planner ranks lower may be the safer one.
        val q = PlanQuery(from, to, after, (limit + 2).coerceAtMost(8), null)
        val r = try {
            plan(q)
        } catch (e: TwinException) {
            when (e.status) {
                404 -> throw NotFoundException("station_not_found", "the planner does not know '$from' or '$to'")
                400 -> throw InvalidInputException("bad_route", "the planner refused this route")
                else -> throw UnavailableException("planner_unavailable", "the sbb-twin planner is unavailable")
            }
        }
        val at = now()
        val journeyJson = r.objects("journeys")
        journeyJson.forEach { j -> journeys?.put(CachedJourney(j, q, null, at), preferExisting = true) }
        val ranked = rank(journeyJson.mapNotNull { evaluate(it, at) }).take(limit)
        return RoutePlanDto(
            from = station(r.obj("from"), from),
            to = station(r.obj("to"), to),
            generatedAt = at,
            recommendedId = ranked.firstOrNull { it.recommended }?.journeyId,
            routes = ranked,
        )
    }

    // ------------------------------------------------------------------ one journey

    private class Evaluated(val route: RouteDto, val penaltyMin: Int)

    private fun evaluate(j: JsonObject, now: Long): Evaluated? {
        val id = j.str("id") ?: return null
        val legsJson = j.objects("legs").ifEmpty { return null }
        val notes = mutableListOf<Pair<Int, String>>() // (weight, text): heavier first
        var feasible = true
        var penalty = 0
        var tight = false
        var severe = false
        var mild = false

        val legs = legsJson.map { leg(it) }.toMutableList()
        val allIncidents = LinkedHashMap<String, RouteIncidentDto>()
        legs.forEach { l -> l.incidents.forEach { allIncidents.putIfAbsent(it.id, it) } }

        legs.forEachIndexed { i, l ->
            val what = l.line ?: l.category ?: "The train"
            if (l.cancelled) { feasible = false; notes += 100 to "$what to ${l.headsign ?: l.to.name} is cancelled" }
            if (l.from.skipped) { feasible = false; notes += 95 to "$what no longer stops at ${l.from.name}" }
            if (l.to.skipped) { feasible = false; notes += 95 to "$what no longer stops at ${l.to.name}" }
            if (l.departed) { feasible = false; notes += 90 to "$what has already left ${l.from.name}" }
            if ((l.delaySec ?: 0) > delayedLegSec) {
                penalty += Penalty.DELAYED_LEG; mild = true
                notes += 30 to "$what is ${(l.delaySec!! / 60.0).roundToInt()} min late"
            }
            val next = legs.getOrNull(i + 1) ?: return@forEachIndexed
            val arr = l.to.expectedTime ?: l.to.plannedTime
            val dep = next.from.expectedTime ?: next.from.plannedTime
            if (arr != null && dep != null) {
                val change = floor((dep - arr) / 60_000.0).toInt()
                legs[i] = l.copy(changeMin = change)
                when {
                    change < 0 -> { feasible = false; notes += 92 to "Change at ${l.to.name} is missed (${-change} min short)" }
                    change < tightChangeMin -> {
                        penalty += Penalty.TIGHT_CHANGE; tight = true
                        notes += 60 to "Tight change at ${l.to.name}: $change min"
                    }
                }
            }
        }
        allIncidents.values.forEach { inc ->
            val s = Severity.parse(inc.severity)
            penalty += Penalty.incident(s)
            if (s.rank <= Severity.MAJOR.rank) severe = true else if (s == Severity.MINOR) mild = true
            if (s != Severity.INFO) notes += (80 - s.rank * 10) to "${s.word}: ${inc.title}"
        }
        val transfers = legs.size - 1
        penalty += transfers * Penalty.PER_CHANGE

        val first = legs.first()
        val last = legs.last()
        val departure = first.from.expectedTime ?: j.long("departure") ?: first.from.plannedTime ?: return null
        val arrival = last.to.expectedTime ?: j.long("arrival") ?: last.to.plannedTime ?: return null
        val plannedArrival = j.long("plannedArrival") ?: last.to.plannedTime
        val delayMin = plannedArrival?.let { ((arrival - it) / 60_000.0).roundToInt() } ?: (j.int("arrivalDelayMin") ?: 0)
        val risk = when {
            !feasible || severe || (tight && legs.any { (it.changeMin ?: 99) < 2 }) -> "high"
            tight || mild -> "medium"
            else -> "low"
        }
        val route = RouteDto(
            journeyId = id, recommended = false,
            departure = departure, arrival = arrival,
            plannedDeparture = j.long("plannedDeparture") ?: first.from.plannedTime, plannedArrival = plannedArrival,
            durationMin = ((arrival - departure) / 60_000.0).roundToInt(), transfers = transfers,
            arrivalDelayMin = delayMin.coerceAtLeast(0),
            effectiveArrival = arrival + penalty * 60_000L,
            feasible = feasible, risk = risk, why = null,
            notes = notes.sortedByDescending { it.first }.map { it.second }.distinct(),
            legs = legs,
            incidents = allIncidents.values.sortedBy { Severity.parse(it.severity).rank },
        )
        return Evaluated(route, penalty)
    }

    /** One leg: the planner's data, overlaid with our live train state when the train is in service. */
    private fun leg(leg: JsonObject): RouteLegDto {
        val trainId = leg.str("trainId")
        val f = leg.obj("from")
        val t = leg.obj("to")
        val fromId = f.str("id") ?: ""
        val toId = t.str("id") ?: ""
        val live = trainId?.let { runCatching { liveTrain(it) }.getOrNull() }
        val stops = live?.stops.orEmpty()
        // The boarding stop still ahead (a run may call twice at a stop), then the alighting stop after it.
        val fromIdx = stops.indexOfFirst { it.stopId == fromId && it.state != StopState.passed }
            .takeIf { it >= 0 } ?: stops.indexOfFirst { it.stopId == fromId }
        val toIdx = if (fromIdx < 0) -1 else stops.withIndex().indexOfFirst { (k, s) -> k > fromIdx && s.stopId == toId }
        val ls = stops.getOrNull(fromIdx)
        val lt = stops.getOrNull(toIdx)
        val departed = ls != null && ls.state == StopState.passed && live?.train?.currentStop?.stopId != fromId
        val planned = leg.objects("incidents").mapNotNull { i ->
            val id = i.str("id") ?: return@mapNotNull null
            RouteIncidentDto(id, i.str("title") ?: "Incident", Severity.parse(i.str("severity")).id, i.str("source"))
        }
        val ours = if (live == null) emptyList() else runCatching { trainIncidents(trainId) }.getOrDefault(emptyList()).map {
            RouteIncidentDto(it.id, it.title, Severity.parse(it.severity).id, "twin")
        }
        val incidents = (planned + ours).distinctBy { it.id }.sortedBy { Severity.parse(it.severity).rank }
        return RouteLegDto(
            trainId = trainId,
            line = leg.str("line") ?: live?.train?.line,
            category = leg.str("category") ?: live?.train?.category,
            headsign = leg.str("headsign") ?: live?.train?.headsign,
            from = RouteStopDto(
                stopId = fromId, name = f.str("name") ?: ls?.name ?: fromId, lat = f.double("lat"), lon = f.double("lon"),
                plannedTime = ls?.plannedDep ?: f.long("plannedDep"),
                expectedTime = ls?.let { it.expectedDep ?: it.actualDep } ?: f.long("expectedDep") ?: f.long("plannedDep"),
                skipped = ls?.skipped ?: false,
            ),
            to = RouteStopDto(
                stopId = toId, name = t.str("name") ?: lt?.name ?: toId, lat = t.double("lat"), lon = t.double("lon"),
                plannedTime = lt?.plannedArr ?: t.long("plannedArr"),
                expectedTime = lt?.let { it.expectedArr ?: it.actualArr } ?: t.long("expectedArr") ?: t.long("plannedArr"),
                skipped = lt?.skipped ?: false,
            ),
            stopsBetween = leg.int("stopsBetween"),
            delaySec = lt?.arrDelaySec ?: leg.int("delaySec"),
            cancelled = leg.str("status") == "cancelled" || live?.train?.status == "cancelled",
            live = live != null,
            departed = departed,
            changeMin = null,
            incidents = incidents,
        )
    }

    // ------------------------------------------------------------------ ranking

    private fun rank(list: List<Evaluated>): List<RouteDto> {
        val sorted = list.sortedWith(
            compareBy<Evaluated>({ !it.route.feasible }, { it.route.effectiveArrival }, { it.route.arrival }, { it.route.transfers }),
        )
        val best = sorted.firstOrNull()?.takeIf { it.route.feasible } ?: return sorted.map { it.route }
        val earliest = list.filter { it.route.feasible }.minWith(compareBy({ it.route.arrival }, { it.route.transfers }))
        val why = when {
            earliest === best || earliest.route.arrival >= best.route.arrival ->
                if (best.route.risk == "low") "Arrives first, no known problems" else "Arrives first"
            else -> {
                val later = ((best.route.arrival - earliest.route.arrival) / 60_000.0).roundToInt()
                val cause = earliest.route.notes.firstOrNull()?.let { ": avoids \"$it\"" } ?: ""
                "Safer: $later min later than the earliest arrival$cause"
            }
        }
        return sorted.map { e -> if (e === best) e.route.copy(recommended = true, why = why) else e.route }
    }

    private fun station(s: JsonObject?, fallback: String) = StationDto(
        stopId = s.str("id") ?: fallback, name = s.str("name") ?: fallback, lat = s.double("lat"), lon = s.double("lon"),
    )

    companion object {
        /** The real sources: the sbb-twin planner and our live state. */
        fun of(twin: SbbTwinClient, queries: TwinQueries, journeys: JourneyCache?) = RoutePlanner(
            plan = { q -> twin.plan(q.from, q.to, q.after, q.limit, q.onboardTrainId).obj() ?: JsonObject(emptyMap()) },
            liveTrain = queries::trainLive,
            trainIncidents = queries::trainIncidents,
            journeys = journeys,
        )
    }
}
