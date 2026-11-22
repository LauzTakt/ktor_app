package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.query.BoardDto
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.ScreenDto
import ch.lauzhack.twin.query.TrainLiveDto
import kotlinx.serialization.Serializable

/* Messages of the WebSocket `/api/v1/ws` (contract: docs/API.md, "WebSocket"); the hub is in LiveUpdates.kt. */

@Serializable
data class WsRequest(
    /** `subscribe` | `unsubscribe` | `ping`. */
    val op: String,
    /** `train` | `station` | `screen`. */
    val topic: String? = null,
    /** trainId or stopId. */
    val id: String? = null,
    /** Seconds between `*_update` messages (1..3600, default 5). Incident notifications ignore it. */
    val frequencySec: Int? = null,
    /** Station / screen: board window in minutes (1..1440, default 120). */
    val windowMin: Int? = null,
    /** Screen only: number of departures (1..20, default 6). */
    val limit: Int? = null,
)

@Serializable
data class WsSubscribed(
    val topic: String, val id: String, val frequencySec: Int, val windowMin: Int?, val limit: Int? = null,
    val type: String = "subscribed",
)

@Serializable
data class WsUnsubscribed(val topic: String, val id: String, val type: String = "unsubscribed")

@Serializable
data class WsTrainUpdate(val id: String, val ts: Long, val data: TrainLiveDto, val topic: String = "train", val type: String = "train_update")

@Serializable
data class WsStationUpdate(val id: String, val ts: Long, val data: BoardDto, val topic: String = "station", val type: String = "station_update")

@Serializable
data class WsScreenUpdate(val id: String, val ts: Long, val data: ScreenDto, val topic: String = "screen", val type: String = "screen_update")

/** The train left service (its run ended): the subscription is closed by the server. */
@Serializable
data class WsTrainEnded(val id: String, val ts: Long, val topic: String = "train", val type: String = "train_ended")

@Serializable
data class TrainMatchDto(val trainId: String, val match: String)

@Serializable
data class WsIncident(
    val topic: String,
    val id: String,
    /** `active` (snapshot on subscribe) | `created` | `updated` | `removed` | `missing`. */
    val event: String,
    val ts: Long,
    /** `incident.match` = relation to the subscribed train, or to the station itself (null if only via its trains). */
    val incident: IncidentDto,
    /** Affected trains with their relation: the train itself for a train subscription, upcoming trains for a station. */
    val trains: List<TrainMatchDto>,
    val type: String = "incident",
)

@Serializable
data class WsError(val code: String, val message: String, val topic: String? = null, val id: String? = null, val type: String = "error")

@Serializable
data class WsPong(val ts: Long, val type: String = "pong")
