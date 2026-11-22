package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.query.StopDto
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.TwinException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class TripWatchSelection(
    val journeyId: String? = null,
    val trainId: String? = null,
    val fromStopId: String? = null,
    val toStopId: String? = null,
    val onBoard: Boolean = false,
)

@Serializable
internal data class WatchStop(val id: String, val name: String, val lat: Double?, val lon: Double?)

@Serializable
internal data class WatchLeg(
    val serviceId: String,
    val line: String,
    val from: WatchStop,
    val to: WatchStop,
    val departure: Long,
    val arrival: Long,
    val expectedDeparture: Long = departure,
    val expectedArrival: Long = arrival,
    val fromSequence: Int? = null,
    val toSequence: Int? = null,
)

internal data class ResolvedWatch(
    val legs: List<WatchLeg>,
    val onBoard: Boolean = false,
)

internal data class ObservedLeg(
    val leg: WatchLeg,
    val cancelled: Boolean,
    val boardingPassed: Boolean,
    val alightingPassed: Boolean,
    val skippedBoarding: Boolean,
    val skippedAlighting: Boolean,
) {
    fun usable(onBoard: Boolean): Boolean =
        !cancelled && !skippedAlighting && !alightingPassed && (onBoard || (!skippedBoarding && !boardingPassed))
}

/** Resolve selections from the same verified planner results and stop calls that built the passenger screens. */
internal class TripWatchJourneys(
    private val queries: TwinQueries,
    private val cache: JourneyCache,
    private val twin: SbbTwinClient?,
) {
    fun resolve(selection: TripWatchSelection, at: Long): ResolvedWatch? {
        selection.journeyId?.let { id ->
            val cached = cache.entry(id) ?: return null
            val itinerary = parseWatchLegs(cached.journey) ?: return null
            val onBoard = selection.onBoard
            if (onBoard && cached.query.onboardTrainId != itinerary.first().serviceId &&
                observe(itinerary.first())?.boardingPassed != true
            ) return null
            return ResolvedWatch(itinerary, onBoard).takeIf {
                itinerary.last().expectedArrival > at && (onBoard || itinerary.first().expectedDeparture >= at) &&
                    itinerary.withIndex().all { (i, leg) -> observe(leg)?.usable(onBoard && i == 0) != false }
            }
        }
        val train = selection.trainId?.let(queries::trainLive) ?: return null
        if (train.train.status != "active") return null
        val from = train.stops.firstOrNull {
            it.stopId == selection.fromStopId && !it.skipped && (selection.onBoard || it.state != StopState.passed)
        } ?: return null
        val to = train.stops.firstOrNull {
            it.seq > from.seq && it.stopId == selection.toStopId && !it.skipped && it.state != StopState.passed
        } ?: return null
        val departure = from.plannedDep ?: from.expectedDep ?: return null
        val arrival = to.plannedArr ?: to.expectedArr ?: return null
        val leg = WatchLeg(
            train.train.trainId, train.train.line ?: "Train", from.watchStop(), to.watchStop(), departure, arrival,
            from.expectedDep ?: departure, to.expectedArr ?: arrival, from.seq, to.seq,
        )
        return ResolvedWatch(listOf(leg), selection.onBoard).takeIf {
            (selection.onBoard || from.state == StopState.current || leg.expectedDeparture >= at) &&
                leg.expectedArrival >= leg.expectedDeparture
        }
    }

    fun observe(leg: WatchLeg): ObservedLeg? {
        val train = queries.trainLive(leg.serviceId) ?: return null
        if (train.train.status == "cancelled") {
            return ObservedLeg(leg, true, false, false, false, false)
        }
        if (train.train.status != "active" || train.stops.isEmpty()) return null
        val from = train.stops.asSequence().filter { it.stopId == leg.from.id }
            .minByOrNull { if (it.seq == leg.fromSequence) 0 else kotlin.math.abs((it.plannedDep ?: leg.departure) - leg.departure) + 1 }
        val to = train.stops.asSequence().filter { it.stopId == leg.to.id && (from == null || it.seq > from.seq) }
            .minByOrNull { if (it.seq == leg.toSequence) 0 else kotlin.math.abs((it.plannedArr ?: leg.arrival) - leg.arrival) + 1 }
        return ObservedLeg(
            leg.copy(
                departure = from?.plannedDep ?: leg.departure,
                expectedDeparture = from?.expectedDep ?: from?.plannedDep ?: leg.expectedDeparture,
                expectedArrival = to?.expectedArr ?: to?.plannedArr ?: leg.expectedArrival,
            ),
            false, from?.state == StopState.passed, to?.state == StopState.passed,
            from == null || from.skipped, to == null || to.skipped,
        )
    }

    suspend fun alternative(from: String, to: String, at: Long): List<WatchLeg>? {
        val result = try {
            twin?.plan(from, to, at, limit = 4) as? JsonObject
        } catch (_: TwinException) {
            null
        } ?: return null
        return result.objects("journeys").asSequence().mapNotNull(::parseWatchLegs).firstOrNull { legs ->
            legs.first().from.id == from && legs.last().to.id == to &&
                legs.first().expectedDeparture >= at && legs.all { observe(it)?.usable(false) != false }
        }
    }
}

/** Reject incomplete plans so a partial response can never become a boarding instruction. */
internal fun parseWatchLegs(journey: JsonObject): List<WatchLeg>? {
    val entries = journey["legs"] as? JsonArray ?: return null
    if (entries.isEmpty()) return null
    val legs = entries.map { entry ->
        val leg = entry as? JsonObject ?: return null
        val from = leg["from"] as? JsonObject ?: return null
        val to = leg["to"] as? JsonObject ?: return null
        val run = leg.str("trainId") ?: return null
        if (leg.str("status") == "cancelled") return null
        val departure = from.long("plannedDep") ?: from.long("expectedDep") ?: return null
        val arrival = to.long("plannedArr") ?: to.long("expectedArr") ?: return null
        if (departure <= 0 || arrival < departure) return null
        WatchLeg(run, leg.str("line") ?: "Service", from.watchStop() ?: return null, to.watchStop() ?: return null,
            departure, arrival, from.long("expectedDep") ?: departure, to.long("expectedArr") ?: arrival)
    }
    if (legs.any { it.expectedArrival < it.expectedDeparture } ||
        legs.zipWithNext().any { (a, b) -> a.to.id != b.from.id || a.expectedArrival > b.expectedDeparture }
    ) return null
    return legs
}

internal fun WatchLeg.boardAdvice(): TripWatchAdvice =
    TripWatchAdvice(TripWatchAction.Board(serviceId, from.id, to.id, departure),
        if (watchTime(expectedDeparture) != watchTime(departure)) "Expected departure ${watchTime(expectedDeparture)}." else null)

private fun StopDto.watchStop() = WatchStop(stopId, name, lat, lon)
private fun JsonObject.watchStop() = str("id")?.let { WatchStop(it, str("name") ?: it, double("lat"), double("lon")) }

internal fun watchTime(time: Long): String = hhmm(time)
