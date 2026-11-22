package ch.lauzhack.twin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for the sbb-twin MQTT payloads (topics under sbb-twin/v1/). Shapes verified against the live broker
 * on 2026-11-21; see docs/DATA_SOURCE.md. All times are epoch milliseconds UTC. Unknown keys are ignored.
 */

@Serializable
data class StopRef(val id: String, val name: String)

/** One stop of a train run, from `trains/{trainId}/info`. */
@Serializable
data class Call(
    val stopId: String,
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val plannedArr: Long? = null,
    val plannedDep: Long? = null,
    val expectedArr: Long? = null,
    val expectedDep: Long? = null,
    val actualArr: Long? = null,
    val actualDep: Long? = null,
    val skipped: Boolean = false,
)

/** `trains/{trainId}/info` (retained, QoS 1). Only published while the train is in service; tombstone at the end. */
@Serializable
data class TrainInfo(
    val v: Int = 1,
    val trainId: String,
    val tripId: String? = null,
    val serviceDate: String? = null,
    val line: String? = null,
    val category: String? = null,
    val number: String? = null,
    val operator: String? = null,
    val headsign: String? = null,
    val status: String = "active", // active | cancelled
    val origin: StopRef? = null,
    val destination: StopRef? = null,
    val calls: List<Call> = emptyList(),
    val updatedAt: Long = 0,
)

/** One row of `fleet` (every second, all trains). Built from the `fields` + `trains` arrays. */
@Serializable
data class TrainPosition(
    val trainId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double? = null,
    val speedKmh: Double? = null,
    val motion: String? = null, // moving | stopped
    val delaySec: Int? = null,
    val line: String? = null,
    val category: String? = null,
    val atStopId: String? = null,
    val continuity: Int = 0,
    val ts: Long,
    val streamId: String,
)

@Serializable
data class IncidentScope(
    val kind: String, // section | station | train | line | network
    val from: String? = null,
    val to: String? = null,
    val stopId: String? = null,
    val trainId: String? = null,
    val line: String? = null,
)

/** `incidents/{incidentId}` (retained, QoS 1). Tombstone (empty payload) on unmark. */
@Serializable
data class Incident(
    val v: Int = 1,
    val id: String,
    val title: String = "",
    val description: String = "",
    val severity: String? = null, // info | minor | major | critical
    val type: String? = null,
    val reasonCode: String? = null,
    val scope: IncidentScope? = null,
    val where: String? = null,
    val disruptionId: String? = null, // present on the live feed, not in DATA_SOURCE.md
    val geometry: JsonElement? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val status: String = "active",
)

/** `status` heartbeat (retained, every 10 s). Only the fields we use. */
@Serializable
data class TwinStatus(
    val ts: Long,
    val streamId: String,
    val tick: Long = 0,
    val trainsActive: Int = 0,
    val trainsSimulated: Int = 0,
    val timetableDate: String? = null,
)
