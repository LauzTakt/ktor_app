package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.state.TrainState
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Passenger-facing queries for the public user view (`/`): "where am I" (nearby trains / stations from the phone's
 * location) and "where am I going" (destinations and connections). Same data as the rest of the API: only what the
 * MQTT ingest holds in memory.
 */

@Serializable
data class NearbyTrainDto(
    val train: TrainSummaryDto,
    /** Distance from the user to the train's position extrapolated to the user's fix time. */
    val distanceM: Int,
    /** |user heading − train bearing| (0…180), when the user is moving and reports a heading. */
    val headingDiffDeg: Int?,
    /** |user speed − train speed|, when the user reports a speed. */
    val speedDiffKmh: Int?,
    /** Ranking score in metres (lower = better): distance + heading/speed mismatch penalties. */
    val score: Int,
)

@Serializable
data class NearbyStationDto(val station: StationDto, val distanceM: Int, val trainsUpcoming: Int)

@Serializable
data class DestinationDto(
    val station: StationDto,
    /** Trains in service that will go from the origin station to this one. */
    val trains: Int,
    /** Earliest expected (else planned) arrival there. */
    val nextArrival: Long?,
)

@Serializable
data class ConnectionDto(
    val train: TrainSummaryDto,
    /** The train's call at the origin station and at the destination. */
    val departure: StopDto,
    val arrival: StopDto,
    /** Expected (else planned) departure / arrival. */
    val departureTime: Long?,
    val arrivalTime: Long?,
    val plannedDepartureTime: Long?,
    val durationMin: Int?,
    /** Intermediate stops the train calls at between the two (skipped stops excluded). */
    val stopsBetween: Int,
    /** The train is standing at the origin station now. */
    val atStation: Boolean,
    val incidents: List<IncidentDto>,
)

@Serializable
data class ConnectionsDto(val from: StationDto, val to: StationDto, val now: Long, val windowMin: Int, val entries: List<ConnectionDto>)

class JourneyQueries(private val state: LiveState, private val q: TwinQueries) {

    /**
     * Trains near a phone fix. Each train is first moved along its bearing to the fix time ([fixTs]), since a train at
     * 120 km/h covers 33 m per second of GPS / feed latency. When the user is moving, trains going another way or at
     * another speed are ranked down (a passenger moves with the train).
     */
    fun nearbyTrains(
        lat: Double, lon: Double, radiusM: Int, limit: Int,
        headingDeg: Double? = null, speedKmh: Double? = null, fixTs: Long? = null,
    ): List<NearbyTrainDto> {
        val moving = speedKmh != null && speedKmh >= MOVING_KMH
        return state.positions().values.mapNotNull { p ->
            val dtSec = fixTs?.let { ((it - p.ts) / 1000.0).coerceIn(-MAX_EXTRAPOLATION_S, MAX_EXTRAPOLATION_S) } ?: 0.0
            val travelled = (p.speedKmh ?: 0.0) / 3.6 * dtSec
            val (plat, plon) = if (p.bearing != null && travelled != 0.0) move(p.lat, p.lon, p.bearing, travelled) else p.lat to p.lon
            val d = haversineM(lat, lon, plat, plon)
            if (d > radiusM) return@mapNotNull null
            val headingDiff = if (moving && headingDeg != null && p.bearing != null) angleDiff(headingDeg, p.bearing) else null
            val speedDiff = if (speedKmh != null && p.speedKmh != null) abs(speedKmh - p.speedKmh) else null
            // A train runs on a line: a heading 180° off is the opposite direction (another train on the other track).
            val penalty = (headingDiff?.let { it / 180.0 * HEADING_PENALTY_M } ?: 0.0) +
                (speedDiff?.let { min(it, 150.0) * SPEED_PENALTY_M_PER_KMH } ?: 0.0)
            val t = state.train(p.trainId) ?: return@mapNotNull null
            Triple(t, d, penalty) to (headingDiff to speedDiff)
        }
            .sortedBy { (it.first.second + it.first.third) }
            .take(limit)
            .map { (a, b) ->
                val (t, d, penalty) = a
                NearbyTrainDto(q.summary(t), d.toInt(), b.first?.toInt(), b.second?.toInt(), (d + penalty).toInt())
            }
    }

    fun nearbyStations(lat: Double, lon: Double, radiusM: Int, limit: Int): List<NearbyStationDto> =
        state.stations().asSequence()
            .filter { it.lat != null && it.lon != null }
            .map { it to haversineM(lat, lon, it.lat!!, it.lon!!) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .take(limit)
            .map { (s, d) -> NearbyStationDto(s.toDto(), d.toInt(), q.trainsUpcomingAt(s.stopId).size) }
            .toList()

    /** Stations reachable from [fromStopId] with a train already in service, most served first. */
    fun destinations(fromStopId: String, query: String?, limit: Int): List<DestinationDto> {
        if (state.station(fromStopId) == null) throw notFound("station", fromStopId)
        val needle = query?.let(::searchKey)?.takeIf { it.isNotEmpty() }
        val count = HashMap<String, Int>()
        val earliest = HashMap<String, Long>()
        for (t in state.trainsCallingAt(fromStopId)) {
            val calls = t.info?.calls ?: continue
            val i = q.upcomingIndexAt(t, fromStopId) ?: continue
            if (calls[i].skipped) continue
            val seen = HashSet<String>()
            for (j in i + 1 until calls.size) {
                val c = calls[j]
                if (c.skipped || c.stopId == fromStopId || !seen.add(c.stopId)) continue
                count.merge(c.stopId, 1, Int::plus)
                (c.expectedArr ?: c.plannedArr)?.let { arr -> earliest.merge(c.stopId, arr, ::minOf) }
            }
        }
        return count.entries.asSequence()
            .mapNotNull { (id, n) -> state.station(id)?.let { DestinationDto(it.toDto(), n, earliest[id]) } }
            .filter { needle == null || needle in searchKey(it.station.name) }
            .sortedWith(
                compareBy<DestinationDto>({ needle != null && !searchKey(it.station.name).startsWith(needle) })
                    .thenByDescending { it.trains }.thenBy { it.station.name },
            )
            .take(limit)
            .toList()
    }

    /** Trains in service going from [fromStopId] to [toStopId] (calling at both, in that order), by departure. */
    fun connections(fromStopId: String, toStopId: String, windowMin: Int, limit: Int): ConnectionsDto {
        val from = state.station(fromStopId) ?: throw notFound("station", fromStopId)
        val to = state.station(toStopId) ?: throw notFound("station", toStopId)
        val now = state.now()
        val until = now + windowMin * 60_000L
        val entries = state.trainsCallingAt(fromStopId).mapNotNull { t -> connection(t, fromStopId, toStopId) }
            .filter { it.atStation || (it.departureTime ?: Long.MAX_VALUE) <= until }
            .sortedWith(compareBy({ !it.atStation }, { it.departureTime ?: Long.MAX_VALUE }))
            .take(limit)
        return ConnectionsDto(from.toDto(), to.toDto(), now, windowMin, entries)
    }

    private fun connection(t: TrainState, fromStopId: String, toStopId: String): ConnectionDto? {
        val calls = t.info?.calls ?: return null
        val i = q.upcomingIndexAt(t, fromStopId) ?: return null
        if (calls[i].skipped) return null
        val j = (i + 1 until calls.size).firstOrNull { calls[it].stopId == toStopId && !calls[it].skipped } ?: return null
        val dep = calls[i]; val arr = calls[j]
        val depTime = dep.expectedDep ?: dep.plannedDep ?: dep.expectedArr ?: dep.plannedArr
        val arrTime = arr.expectedArr ?: arr.plannedArr
        return ConnectionDto(
            train = q.summary(t),
            departure = dep.toDto(i, q.stateOf(t, i)),
            arrival = arr.toDto(j, q.stateOf(t, j)),
            departureTime = depTime, arrivalTime = arrTime, plannedDepartureTime = dep.plannedDep ?: dep.plannedArr,
            durationMin = if (depTime != null && arrTime != null) ((arrTime - depTime) / 60_000).toInt() else null,
            stopsBetween = (i + 1 until j).count { !calls[it].skipped },
            atStation = i == t.nextCallIndex && t.position?.atStopId == fromStopId,
            incidents = q.incidentsForTrain(t),
        )
    }

    private companion object {
        const val MOVING_KMH = 10.0
        const val MAX_EXTRAPOLATION_S = 15.0
        const val HEADING_PENALTY_M = 600.0
        const val SPEED_PENALTY_M_PER_KMH = 4.0
    }
}

private const val EARTH_M = 6_371_000.0
private fun Double.rad() = this * PI / 180

internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1).rad(); val dLon = (lon2 - lon1).rad()
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1.rad()) * cos(lat2.rad()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Point [distM] metres from (lat, lon) along [bearingDeg] (small distances: equirectangular is plenty). */
internal fun move(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
    val b = bearingDeg.rad()
    val dLat = distM * cos(b) / EARTH_M
    val dLon = distM * sin(b) / (EARTH_M * cos(lat.rad()))
    return (lat + dLat * 180 / PI) to (lon + dLon * 180 / PI)
}

/** Smallest angle between two bearings, 0…180. */
internal fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)
