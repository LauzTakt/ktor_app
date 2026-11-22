package ch.lauzhack.twin.query

import ch.lauzhack.twin.db.IncidentEventRow
import ch.lauzhack.twin.db.IncidentRow
import ch.lauzhack.twin.model.IncidentScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Read models returned by the query layer ([TwinQueries], [JourneyQueries], [IncidentQueries]). They are also the
 * public API v1 contract (see docs/API.md) with the web UI and the prediction model, serialized as they are by
 * `api/v1`; the assistant and the WebSocket hub reuse them in process. They are decoupled from the MQTT payloads, so
 * the feed can change without breaking clients.
 * Conventions: times are epoch milliseconds UTC, delays in seconds (+ = late), ids are the feed's ids.
 */

@Serializable
data class StopRefDto(val stopId: String, val name: String)

@Serializable
data class PositionDto(
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    val speedKmh: Double?,
    /** `moving` or `stopped`. `stopped` with `atStopId == null` = stopped on the open line. */
    val motion: String?,
    val atStopId: String?,
    /** Changes when the train jumps: don't interpolate across a change. */
    val continuity: Int,
    /** Position valid at this time. */
    val ts: Long,
)

/** State of one stop relative to the train's progress. */
@Serializable
enum class StopState { passed, current, next, upcoming }

@Serializable
data class StopDto(
    /** Index in the run (0 = origin). */
    val seq: Int,
    val stopId: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val plannedArr: Long?,
    val plannedDep: Long?,
    val expectedArr: Long?,
    val expectedDep: Long?,
    val actualArr: Long?,
    val actualDep: Long?,
    /** expectedArr − plannedArr (or actual − planned once known). */
    val arrDelaySec: Int?,
    val depDelaySec: Int?,
    /** The train passes without stopping. */
    val skipped: Boolean,
    val state: StopState,
)

@Serializable
data class TrainSummaryDto(
    val trainId: String,
    val line: String?,
    val category: String?,
    val number: String?,
    val operator: String?,
    val headsign: String?,
    /** `active` | `cancelled` (from the twin); `unknown` while only the position is known. */
    val status: String,
    val origin: StopRefDto?,
    val destination: StopRefDto?,
    /** Live delay in seconds at the next stop (from the latest position). */
    val delaySec: Int?,
    val position: PositionDto?,
    /** Station the train is standing at, if any. */
    val currentStop: StopRefDto?,
    /** Next stop the train will reach (null at terminus). */
    val nextStop: StopDto?,
    val infoUpdatedAt: Long?,
)

@Serializable
data class TrainDetailDto(
    val train: TrainSummaryDto,
    val stops: List<StopDto>,
    val incidents: List<IncidentDto>,
)

/** Live train payload pushed over the WebSocket (`train_update`): like [TrainDetailDto] without incidents. */
@Serializable
data class TrainLiveDto(
    val train: TrainSummaryDto,
    val stops: List<StopDto>,
)

/** A line with trains in service (GET /api/v1/lines). */
@Serializable
data class LineDto(val line: String, val category: String?, val trains: Int, val incidents: Int)

/** GET /api/v1/lines/{line}: its trains in service and the incidents concerning it (`match` as for line subscriptions). */
@Serializable
data class LineDetailDto(val line: String, val category: String?, val trains: List<TrainSummaryDto>, val incidents: List<IncidentDto>)

@Serializable
data class StationDto(val stopId: String, val name: String, val lat: Double?, val lon: Double?)

@Serializable
data class StationDetailDto(
    val station: StationDto,
    /** Trains standing at the station now. */
    val trainsPresent: Int,
    /** Trains in service that will still call at / pass through the station. */
    val trainsUpcoming: Int,
    val incidents: List<IncidentDto>,
)

@Serializable
enum class BoardType { departures, arrivals, all }

@Serializable
data class BoardEntryDto(
    val trainId: String,
    val line: String?,
    val category: String?,
    val number: String?,
    val operator: String?,
    val headsign: String?,
    val status: String,
    val origin: StopRefDto?,
    val destination: StopRefDto?,
    /** The train's call at this station. */
    val stop: StopDto,
    /** Time the board is sorted by: expected (else planned) departure for departures, arrival for arrivals. */
    val time: Long?,
    val plannedTime: Long?,
    val delaySec: Int?,
    val atStation: Boolean,
    val startsHere: Boolean,
    val terminatesHere: Boolean,
    /** Active incidents concerning this train (with `match`, same as `/trains/{id}/incidents`), worst first. */
    val incidents: List<IncidentDto> = emptyList(),
)

@Serializable
data class BoardDto(
    val station: StationDto,
    val type: BoardType,
    /** Twin clock used for the window. */
    val now: Long,
    val windowMin: Int,
    /** Only trains already in service are known (see docs/API.md). */
    val entries: List<BoardEntryDto>,
)

@Serializable
data class IncidentDto(
    val id: String,
    val title: String,
    val description: String,
    val severity: String?,
    val type: String?,
    val reasonCode: String?,
    val scope: IncidentScope?,
    val where: String?,
    val disruptionId: String?,
    val status: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val firstSeenAt: Long,
    val removedAt: Long?,
    /**
     * Why it is listed for a train/station (null in plain lists): `train` (targets this train), `route` (the train runs
     * through the section), `endpoint` (train/station is at one end of the section), `station`, `line`, `network`.
     */
    val match: String? = null,
    /** GeoJSON, only on `GET /incidents/{id}`. */
    val geometry: JsonElement? = null,
    /** Expected end (epoch ms), from the message text; null when not announced (always for detected `auto.*` ones). */
    val expectedEnd: Long? = null,
    /** How firm [expectedEnd] is: `at_least` | `approx` | `until`. */
    val endKind: String? = null,
)

@Serializable
data class IncidentEventDto(
    val seq: Long, val kind: String, val receivedAt: Long, val updatedAt: Long?, val status: String?, val severity: String?,
)

@Serializable
data class PageDto<T>(val total: Int, val limit: Int, val offset: Int, val items: List<T>)

@Serializable
data class TwinStatusDto(
    val streamId: String,
    val ts: Long,
    val tick: Long,
    val trainsActive: Int,
    val trainsSimulated: Int,
    val timetableDate: String?,
    /** false when no heartbeat for > 30 s. */
    val online: Boolean,
)

@Serializable
data class StatusDto(
    val now: Long,
    val mqttConnected: Boolean,
    val trains: Int,
    val positions: Int,
    val stations: Int,
    val lastFleetTs: Long?,
    val twin: TwinStatusDto?,
)

fun IncidentRow.toDto(match: String? = null): IncidentDto {
    val end = IncidentEnds.parse(description, updatedAt.takeIf { it > 0 } ?: firstSeenAt)
    return IncidentDto(
        id, title, description, severity, type, reasonCode, scope, where, disruptionId, status, active,
        createdAt, updatedAt, firstSeenAt, removedAt, match, geometry, end?.at, end?.kind,
    )
}

fun IncidentEventRow.toDto() = IncidentEventDto(seq, kind, receivedAt, updatedAt, status, severity)

// ---------------------------------------------------------------- station screen (small displays)

/**
 * Compact, ready-to-draw view of a station for small displays (e.g. an ESP32 with a 320x240 TFT): only the fields a
 * departure screen shows, ~1 KB instead of a full [BoardDto]. `GET /stations/{id}/screen` and WebSocket topic `screen`.
 */
@Serializable
data class ScreenDto(
    val stopId: String,
    val name: String,
    /** Twin clock (epoch ms). */
    val now: Long,
    /** Next departures (trains standing at the station first, then by expected time); skipped trains excluded. */
    val departures: List<ScreenDepartureDto>,
    /** Active incidents concerning the station or its upcoming trains, worst first. */
    val incidents: List<ScreenIncidentDto>,
)

@Serializable
data class ScreenDepartureDto(
    /** Planned departure (epoch ms); show it with [delaySec]. */
    val plannedTime: Long?,
    /** Live delay in seconds (0 if unknown). */
    val delaySec: Int,
    /** Line name (e.g. `IC1`), else the category. */
    val line: String,
    val category: String?,
    /** Destination name (else the headsign). */
    val to: String,
    val cancelled: Boolean,
    val atStation: Boolean,
)

@Serializable
data class ScreenIncidentDto(
    val id: String,
    /** `info` | `minor` | `major` | `critical`. */
    val severity: String?,
    val title: String,
    val where: String?,
    /** true = at the station itself (section end, station, network); false = only via trains calling here. */
    val atStation: Boolean,
    /** Number of upcoming trains at the station affected by it. */
    val trains: Int,
    val updatedAt: Long,
)
