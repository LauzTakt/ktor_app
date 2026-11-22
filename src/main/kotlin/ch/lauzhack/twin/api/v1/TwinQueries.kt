package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.db.IncidentRow
import ch.lauzhack.twin.model.Call
import ch.lauzhack.twin.model.StopRef
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.state.Station
import ch.lauzhack.twin.state.TrainState
import io.ktor.http.HttpStatusCode

class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

fun notFound(what: String, id: String) = ApiException(HttpStatusCode.NotFound, "${what}_not_found", "$what '$id' not found")

/** Bounding box `minLon,minLat,maxLon,maxLat` (WGS84). */
data class BBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun contains(lon: Double?, lat: Double?) = lon != null && lat != null && lon in minLon..maxLon && lat in minLat..maxLat

    companion object {
        fun parse(s: String?): BBox? {
            if (s.isNullOrBlank()) return null
            val v = s.split(",").map { it.trim().toDoubleOrNull() }
            if (v.size != 4 || v.any { it == null }) throw ApiException(HttpStatusCode.BadRequest, "bad_bbox", "bbox must be minLon,minLat,maxLon,maxLat")
            return BBox(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
        }
    }
}

data class TrainFilter(
    val line: String? = null,
    val category: String? = null,
    val status: String? = null,
    /** Only trains that will still call at / pass through this station. */
    val stationId: String? = null,
    /** Only trains standing at a station (true) / not at a station (false). */
    val atStation: Boolean? = null,
    val minDelaySec: Int? = null,
    val bbox: BBox? = null,
)

/**
 * Read model of the API: joins the in-memory train state with the SQLite incidents.
 * Pure functions of [state] + [activeIncidents], so they are cheap to test.
 */
class TwinQueries(
    private val state: LiveState,
    private val activeIncidents: () -> List<IncidentRow>,
) {
    /** For sibling query classes (JourneyQueries) that need the raw state. */
    internal val liveState: LiveState get() = state

    // ------------------------------------------------------------------ trains

    fun trains(f: TrainFilter, limit: Int, offset: Int): PageDto<TrainSummaryDto> {
        val source = f.stationId?.let { state.trainsCallingAt(it) } ?: state.trains()
        val matches = source.asSequence()
            .filter { t -> f.stationId == null || upcomingIndexAt(t, f.stationId) != null }
            .filter { t -> f.line == null || t.line.equals(f.line, ignoreCase = true) }
            .filter { t -> f.category == null || t.category.equals(f.category, ignoreCase = true) }
            .filter { t -> f.status == null || t.status.equals(f.status, ignoreCase = true) }
            .filter { t -> f.atStation == null || (t.position?.atStopId != null) == f.atStation }
            .filter { t -> f.minDelaySec == null || (t.position?.delaySec ?: Int.MIN_VALUE) >= f.minDelaySec }
            .filter { t -> f.bbox == null || f.bbox.contains(t.position?.lon, t.position?.lat) }
            .sortedBy { it.trainId }
            .toList()
        return PageDto(matches.size, limit, offset, matches.drop(offset).take(limit).map { summary(it) })
    }

    fun train(trainId: String): TrainDetailDto {
        val t = state.train(trainId) ?: throw notFound("train", trainId)
        return TrainDetailDto(summary(t), stops(t), incidentsForTrain(t))
    }

    fun trainSummary(trainId: String): TrainSummaryDto = summary(state.train(trainId) ?: throw notFound("train", trainId))

    fun trainStops(trainId: String, only: StopState?): List<StopDto> {
        val t = state.train(trainId) ?: throw notFound("train", trainId)
        val all = stops(t)
        return when (only) {
            null -> all
            // "upcoming" as a filter means everything still ahead, including current/next.
            StopState.upcoming -> all.filter { it.state != StopState.passed }
            else -> all.filter { it.state == only }
        }
    }

    fun trainPosition(trainId: String): PositionDto {
        val t = state.train(trainId) ?: throw notFound("train", trainId)
        return t.position?.toDto() ?: throw ApiException(HttpStatusCode.NotFound, "position_not_found", "no position yet for train '$trainId'")
    }

    fun trainIncidents(trainId: String): List<IncidentDto> = incidentsForTrain(state.train(trainId) ?: throw notFound("train", trainId))

    // ------------------------------------------------------------------ stations

    fun stations(q: String?, bbox: BBox?, limit: Int): List<StationDto> {
        val needle = q?.let(::searchKey)?.takeIf { it.isNotEmpty() }
        return state.stations().asSequence()
            .filter { needle == null || needle in searchKey(it.name) || it.stopId == needle }
            .filter { bbox == null || bbox.contains(it.lon, it.lat) }
            // exact / prefix matches first, then alphabetical
            .sortedWith(compareBy<Station>({ needle != null && !searchKey(it.name).startsWith(needle) }, { it.name }))
            .take(limit)
            .map { it.toDto() }
            .toList()
    }

    fun station(stopId: String): StationDetailDto {
        val s = state.station(stopId) ?: throw notFound("station", stopId)
        val calling = state.trainsCallingAt(stopId)
        return StationDetailDto(
            station = s.toDto(),
            trainsPresent = calling.count { it.position?.atStopId == stopId },
            trainsUpcoming = calling.count { upcomingIndexAt(it, stopId) != null },
            incidents = incidentsForStation(stopId),
        )
    }

    fun board(stopId: String, type: BoardType, windowMin: Int, limit: Int, includeSkipped: Boolean): BoardDto {
        val s = state.station(stopId) ?: throw notFound("station", stopId)
        val now = state.now()
        val until = now + windowMin * 60_000L
        val incidents = activeIncidents()
        val entries = state.trainsCallingAt(stopId).mapNotNull { t ->
            val info = t.info ?: return@mapNotNull null
            val idx = upcomingIndexAt(t, stopId) ?: return@mapNotNull null
            val call = info.calls[idx]
            if (call.skipped && !includeSkipped) return@mapNotNull null
            val startsHere = idx == 0
            val terminatesHere = idx == info.calls.lastIndex
            if (type == BoardType.departures && terminatesHere) return@mapNotNull null
            if (type == BoardType.arrivals && startsHere) return@mapNotNull null
            val (time, planned) = when (type) {
                BoardType.departures -> (call.expectedDep ?: call.plannedDep) to call.plannedDep
                BoardType.arrivals -> (call.expectedArr ?: call.plannedArr) to call.plannedArr
                BoardType.all -> (call.expectedArr ?: call.plannedArr ?: call.expectedDep ?: call.plannedDep) to (call.plannedArr ?: call.plannedDep)
            }
            val atStation = idx == t.nextCallIndex && t.position?.atStopId == stopId
            if (!atStation && time != null && time > until) return@mapNotNull null
            BoardEntryDto(
                trainId = t.trainId, line = info.line, category = info.category, number = info.number,
                operator = info.operator, headsign = info.headsign, status = info.status,
                origin = info.origin?.toDto(), destination = info.destination?.toDto(),
                stop = call.toDto(idx, stateOf(t, idx)), time = time, plannedTime = planned,
                delaySec = if (time != null && planned != null) ((time - planned) / 1000).toInt() else null,
                atStation = atStation, startsHere = startsHere, terminatesHere = terminatesHere,
                incidents = incidents.mapNotNull { inc -> trainIncidentMatch(inc, t)?.let { inc.toDto(it) } }
                    .sortedWith(compareBy({ severityRank(it.severity) }, { -it.updatedAt })),
            )
        }.sortedWith(compareBy({ !it.atStation }, { it.time ?: Long.MAX_VALUE })).take(limit)
        return BoardDto(s.toDto(), type, now, windowMin, entries)
    }

    /**
     * Compact screen view of [stopId] (docs/API.md, "Station screen"): the next [limit] departures within [windowMin]
     * and the related active incidents (same matching as the station WebSocket subscription), worst first.
     */
    fun screen(stopId: String, limit: Int, windowMin: Int = 120, maxIncidents: Int = 5): ScreenDto {
        val b = board(stopId, BoardType.departures, windowMin, limit, includeSkipped = false)
        val upcoming = trainsUpcomingAt(stopId)
        val incidents = activeIncidents().mapNotNull { row ->
            val here = stationIncidentMatch(row, stopId) != null
            val trains = upcoming.count { trainIncidentMatch(row, it) != null }
            if (!here && trains == 0) null
            else ScreenIncidentDto(row.id, row.severity, row.title, row.where, here, trains, row.updatedAt)
        }.sortedWith(
            compareBy<ScreenIncidentDto>({ severityRank(it.severity) }, { !it.atStation }).thenByDescending { it.updatedAt },
        ).take(maxIncidents)
        return ScreenDto(
            stopId = b.station.stopId, name = b.station.name, now = b.now,
            departures = b.entries.map { e ->
                ScreenDepartureDto(
                    plannedTime = e.plannedTime ?: e.time, delaySec = e.delaySec ?: 0,
                    line = e.line ?: e.category ?: "", category = e.category,
                    to = e.destination?.name ?: e.headsign ?: "", cancelled = e.status == "cancelled",
                    atStation = e.atStation,
                )
            },
            incidents = incidents,
        )
    }

    fun stationIncidents(stopId: String): List<IncidentDto> {
        if (state.station(stopId) == null) throw notFound("station", stopId)
        return incidentsForStation(stopId)
    }

    // ------------------------------------------------------------------ status

    fun status(mqttConnected: Boolean): StatusDto {
        val now = System.currentTimeMillis()
        return StatusDto(
            now = now, mqttConnected = mqttConnected, trains = state.trainCount(), positions = state.positionCount(),
            stations = state.stations().size, lastFleetTs = state.lastFleetTs.takeIf { it > 0 },
            twin = state.status?.let {
                TwinStatusDto(it.streamId, it.ts, it.tick, it.trainsActive, it.trainsSimulated, it.timetableDate, now - it.ts <= 30_000)
            },
        )
    }

    // ------------------------------------------------------------------ mapping helpers

    internal fun summary(t: TrainState): TrainSummaryDto {
        val info = t.info
        val n = t.nextCallIndex
        val calls = info?.calls.orEmpty()
        val atStop = t.position?.atStopId
        val current = if (n != null && n < calls.size && calls[n].stopId == atStop) calls[n] else null
        val nextIdx = when {
            n == null -> null
            current != null -> n + 1
            else -> n
        }?.takeIf { it < calls.size }
        return TrainSummaryDto(
            trainId = t.trainId,
            line = info?.line ?: t.position?.line,
            category = info?.category ?: t.position?.category,
            number = info?.number, operator = info?.operator, headsign = info?.headsign,
            status = info?.status ?: "unknown",
            origin = info?.origin?.toDto(), destination = info?.destination?.toDto(),
            delaySec = t.position?.delaySec,
            position = t.position?.toDto(),
            currentStop = current?.let { StopRefDto(it.stopId, it.name) }
                ?: atStop?.let { id -> StopRefDto(id, state.station(id)?.name ?: id) },
            nextStop = nextIdx?.let { calls[it].toDto(it, stateOf(t, it)) },
            infoUpdatedAt = info?.updatedAt,
        )
    }

    internal fun stops(t: TrainState): List<StopDto> =
        t.info?.calls.orEmpty().mapIndexed { i, c -> c.toDto(i, stateOf(t, i)) }

    internal fun stateOf(t: TrainState, i: Int): StopState {
        val n = t.nextCallIndex ?: return StopState.upcoming
        val calls = t.info?.calls.orEmpty()
        return when {
            i < n -> StopState.passed
            i == n && t.position?.atStopId == calls[i].stopId -> StopState.current
            i == n -> StopState.next
            else -> StopState.upcoming
        }
    }

    /** Index of the train's first not-yet-left call at [stopId], or null if it won't call there any more. */
    internal fun upcomingIndexAt(t: TrainState, stopId: String): Int? {
        val calls = t.info?.calls ?: return null
        val from = t.nextCallIndex ?: return null
        return (from until calls.size).firstOrNull { calls[it].stopId == stopId }
    }

    internal fun incidentsForTrain(t: TrainState): List<IncidentDto> =
        activeIncidents().mapNotNull { inc -> trainIncidentMatch(inc, t)?.let { inc.toDto(it) } }

    internal fun incidentsForStation(stopId: String): List<IncidentDto> =
        activeIncidents().mapNotNull { inc -> stationIncidentMatch(inc, stopId)?.let { inc.toDto(it) } }

    // ------------------------------------------------------------------ live updates (WebSocket)

    /** Train summary + all stops, or null if the train is unknown (not in service). No incidents (they are pushed separately). */
    fun trainLive(trainId: String): TrainLiveDto? = state.train(trainId)?.let { TrainLiveDto(summary(it), stops(it)) }

    fun trainState(trainId: String): TrainState? = state.train(trainId)

    /** Trains in service that will still call at / pass through [stopId]. */
    fun trainsUpcomingAt(stopId: String): List<TrainState> =
        state.trainsCallingAt(stopId).filter { upcomingIndexAt(it, stopId) != null }

    fun activeIncidentRows(): List<IncidentRow> = activeIncidents()

    /**
     * Why [inc] concerns train [t] (see docs/API.md, "How incidents are matched"), or null if it doesn't:
     * `train`, `route` (runs through the section), `endpoint` (calls at one end), `station`, `line`, `network`.
     */
    fun trainIncidentMatch(inc: IncidentRow, t: TrainState): String? {
        val sc = inc.scope ?: return null
        val calls = t.info?.calls.orEmpty()
        val n = t.nextCallIndex ?: 0
        // Stops still to come, plus the previous one (the train may be inside a section that starts there).
        val upcoming = calls.drop(n).mapTo(HashSet()) { it.stopId }
        val withPrevious = calls.drop((n - 1).coerceAtLeast(0)).mapTo(HashSet()) { it.stopId }
        val line = t.line
        return when (sc.kind) {
            "train" -> "train".takeIf { sc.trainId == t.trainId }
            "section" -> when {
                sc.from in withPrevious && sc.to in withPrevious -> "route"
                sc.from in upcoming || sc.to in upcoming -> "endpoint"
                else -> null
            }
            "station" -> "station".takeIf { (sc.from ?: sc.stopId) in upcoming }
            "line" -> "line".takeIf { line != null && sc.line.equals(line, ignoreCase = true) }
            "network" -> "network"
            else -> null
        }
    }

    /** Why [inc] concerns station [stopId] itself (`endpoint`, `station`, `network`), or null. */
    fun stationIncidentMatch(inc: IncidentRow, stopId: String): String? {
        val sc = inc.scope ?: return null
        return when (sc.kind) {
            "section" -> "endpoint".takeIf { sc.from == stopId || sc.to == stopId }
            "station" -> "station".takeIf { (sc.from ?: sc.stopId) == stopId }
            "network" -> "network"
            else -> null
        }
    }

    // ------------------------------------------------------------------ lines

    /** Lines of the trains in service (e.g. `IC 1`, `S3`), with how many trains run and the incidents touching them. */
    fun lines(q: String? = null): List<LineDto> {
        val incidents = activeIncidents()
        val key = q?.trim()?.takeIf { it.isNotEmpty() }?.let(::searchKey)
        return state.trains().filter { it.line != null }.groupBy { it.line!!.uppercase() }
            .map { (_, trains) ->
                val line = trains.first().line!!
                LineDto(
                    line = line,
                    category = trains.firstNotNullOfOrNull { it.category },
                    trains = trains.size,
                    incidents = incidents.count { lineIncidentMatch(it, line, trains) != null },
                )
            }
            .filter { key == null || searchKey(it.line).replace(" ", "").contains(key.replace(" ", "")) }
            .sortedWith(compareBy({ it.category ?: "~" }, { it.line.length }, { it.line }))
    }

    fun line(line: String): LineDetailDto {
        val trains = trainsOnLine(line.trim())
        if (trains.isEmpty()) throw notFound("line", line)
        val name = trains.first().line!!
        val incidents = activeIncidents().mapNotNull { inc -> lineIncidentMatch(inc, name, trains)?.let { inc.toDto(it) } }
            .sortedWith(compareBy({ severityRank(it.severity) }, { -it.updatedAt }))
        return LineDetailDto(name, trains.firstNotNullOfOrNull { it.category }, trains.sortedBy { it.trainId }.map { summary(it) }, incidents)
    }

    /** Trains in service on [line] (case-insensitive). */
    fun trainsOnLine(line: String): List<TrainState> = state.trains().filter { it.line.equals(line, ignoreCase = true) }

    /**
     * Why [inc] concerns line [line] (for line subscriptions, docs/API.md "Push notifications"): `line` (scope is the
     * line), `network`, or the match of the first train in service on the line it concerns (`train`, `route`,
     * `endpoint`, `station`). Null if it concerns no train of the line.
     */
    fun lineIncidentMatch(inc: IncidentRow, line: String, trains: List<TrainState> = trainsOnLine(line)): String? {
        val sc = inc.scope ?: return null
        if (sc.kind == "line") return "line".takeIf { sc.line.equals(line, ignoreCase = true) }
        if (sc.kind == "network") return "network"
        return trains.firstNotNullOfOrNull { trainIncidentMatch(inc, it) }
    }
}

private val DIACRITICS = Regex("\\p{Mn}+")

/** Station-name search key: trimmed, lower case, accents removed ("Zürich" and "zurich" match; ß -> ss). */
internal fun searchKey(s: String): String =
    java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(DIACRITICS, "").replace("ß", "ss")

private fun severityRank(s: String?) = when (s?.lowercase()) {
    "critical" -> 0
    "major" -> 1
    "minor" -> 2
    "info" -> 3
    else -> 4
}

private val TrainState.line get() = info?.line ?: position?.line
private val TrainState.category get() = info?.category ?: position?.category
private val TrainState.status get() = info?.status ?: "unknown"

internal fun Station.toDto() = StationDto(stopId, name, lat, lon)
private fun StopRef.toDto() = StopRefDto(id, name)
private fun ch.lauzhack.twin.model.TrainPosition.toDto() = PositionDto(lat, lon, bearing, speedKmh, motion, atStopId, continuity, ts)

private fun delay(actualOrExpected: Long?, planned: Long?) =
    if (actualOrExpected != null && planned != null) ((actualOrExpected - planned) / 1000).toInt() else null

internal fun Call.toDto(seq: Int, state: StopState) = StopDto(
    seq = seq, stopId = stopId, name = name, lat = lat, lon = lon,
    plannedArr = plannedArr, plannedDep = plannedDep, expectedArr = expectedArr, expectedDep = expectedDep,
    actualArr = actualArr, actualDep = actualDep,
    arrDelaySec = delay(actualArr ?: expectedArr, plannedArr), depDelaySec = delay(actualDep ?: expectedDep, plannedDep),
    skipped = skipped, state = state,
)
