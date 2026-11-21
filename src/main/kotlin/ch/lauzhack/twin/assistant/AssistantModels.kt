package ch.lauzhack.twin.assistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Public contract of `POST /api/v1/assistant/ask` (see /workspace ASSISTANT_CONTRACT.md section 2 and docs/API.md,
 * "Passenger assistant"). Times are epoch milliseconds UTC, coordinates WGS84.
 */

@Serializable
data class AskRequest(
    /** The passenger's question, already formalised by the voice agent. */
    val question: String = "",
    val context: AskContext? = null,
    /** Last turns of the conversation, oldest first (optional). */
    val history: List<HistoryTurn> = emptyList(),
    val sessionId: String? = null,
)

@Serializable
data class AskContext(
    val fromStopId: String? = null,
    val toStopId: String? = null,
    val trainId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class HistoryTurn(val role: String = "passenger", val text: String = "")

@Serializable
data class AskResponse(
    /** Spoken reply for the voice agent: 1-3 short sentences, no markdown. */
    val answer: String,
    /** Short line for the screen. */
    val summary: String?,
    val situation: List<SituationItem>,
    val options: List<AskOption>,
    val visuals: List<VisualCommand>,
    /** Optional question for the passenger. */
    val followUp: String?,
    val elapsedMs: Long,
)

@Serializable
data class SituationItem(
    val id: String,
    val title: String,
    val where: String?,
    /** `info | minor | major | critical` */
    val severity: String,
    /** `staff` (marked or reported by staff) | `auto` (detected from train movements) | `sbb` (real SBB alert). */
    val source: String,
)

@Serializable
data class AskOption(
    /** `A`, `B`, … in the order the assistant ranked them. */
    val id: String,
    val label: String,
    /** Expected (live) departure and arrival of the journey, from the planner (never from the model). */
    val departure: Long?,
    val arrival: Long?,
    val transfers: Int?,
    val recommended: Boolean,
    val why: String?,
    val journeyId: String?,
)

/** A drawing instruction for the passenger app's map. Geometry always comes from sbb-twin, never from the model. */
@Serializable
sealed interface VisualCommand

/** Start a fresh picture. */
@Serializable
@SerialName("clear")
data object ClearVisual : VisualCommand

/** Fit the map to `[west, south, east, north]`. */
@Serializable
@SerialName("focus")
data class FocusVisual(val bbox: List<Double>) : VisualCommand

@Serializable
@SerialName("route")
data class RouteVisual(
    /** The journey id (`j-…`). */
    val id: String,
    /** `recommended | alternative` */
    val style: String,
    val label: String?,
    val legs: List<RouteLeg>,
) : VisualCommand

@Serializable
data class RouteLeg(
    val line: String?,
    val trainId: String?,
    val fromName: String?,
    val toName: String?,
    /** GeoJSON LineString / MultiLineString of the track the leg runs on (null if the twin had none). */
    val geometry: JsonElement?,
)

@Serializable
@SerialName("section")
data class SectionVisual(
    val id: String,
    /** `red | orange | green | blue` */
    val colour: String,
    val label: String?,
    val geometry: JsonElement?,
) : VisualCommand

/** Highlight a live train (outline it, never recolour it). */
@Serializable
@SerialName("train")
data class TrainVisual(val trainId: String, val label: String?, val follow: Boolean = false) : VisualCommand

@Serializable
@SerialName("station")
data class StationVisual(
    val stopId: String,
    val name: String,
    val lon: Double?,
    val lat: Double?,
    /** `origin | destination | transfer | incident` */
    val role: String,
    val label: String?,
) : VisualCommand

/** Countdown chip. */
@Serializable
@SerialName("timer")
data class TimerVisual(val label: String, val until: Long) : VisualCommand
