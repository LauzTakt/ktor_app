package ch.lauzhack.twin.routing

import ch.lauzhack.twin.query.StationDto
import kotlinx.serialization.Serializable

/*
 * Response of GET /api/v1/routes (public contract, docs/API.md "Routes"): the sbb-twin planner's journeys checked
 * against our live data and ranked by the arrival you can count on.
 */

@Serializable
data class RoutePlanDto(
    val from: StationDto,
    val to: StationDto,
    val generatedAt: Long,
    /** `journeyId` of the route we recommend (first of [routes]); null when no route is feasible. */
    val recommendedId: String?,
    /** Feasible routes first, by [RouteDto.effectiveArrival]; then the infeasible ones (kept so the UI can say why). */
    val routes: List<RouteDto>,
)

@Serializable
data class RouteDto(
    /** The planner's journey id (valid ~30 min): `/twin/api/journeys/{id}`, Trip Watch `journeyId`. */
    val journeyId: String,
    val recommended: Boolean,
    /** Expected times (live when the train is in service, else the planner's). */
    val departure: Long,
    val arrival: Long,
    val plannedDeparture: Long?,
    val plannedArrival: Long?,
    val durationMin: Int,
    val transfers: Int,
    /** expected − planned arrival, minutes. */
    val arrivalDelayMin: Int,
    /** [arrival] + the risk penalty (incidents, tight changes, changes): the sort key. */
    val effectiveArrival: Long,
    /** false: a leg is cancelled, a stop is skipped, a change is already missed or the first train has left. */
    val feasible: Boolean,
    /** `low` | `medium` | `high`. */
    val risk: String,
    /** Why it is recommended (only on the recommended route), e.g. "Arrives first". */
    val why: String?,
    /** Short facts that weigh on this route, worst first (e.g. "Tight change at Olten: 2 min"). */
    val notes: List<String>,
    val legs: List<RouteLegDto>,
    /** Distinct incidents of all legs, worst first. */
    val incidents: List<RouteIncidentDto>,
)

@Serializable
data class RouteLegDto(
    val trainId: String?,
    val line: String?,
    val category: String?,
    val headsign: String?,
    val from: RouteStopDto,
    val to: RouteStopDto,
    val stopsBetween: Int?,
    val delaySec: Int?,
    val cancelled: Boolean,
    /** The train is in service and our live data was used for its times. */
    val live: Boolean,
    /** The train has already left [from] (and the passenger is not on it). */
    val departed: Boolean,
    /** Minutes between this leg's arrival and the next leg's departure (null on the last leg). */
    val changeMin: Int?,
    val incidents: List<RouteIncidentDto>,
)

@Serializable
data class RouteStopDto(
    val stopId: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val plannedTime: Long?,
    val expectedTime: Long?,
    /** The train no longer stops there. */
    val skipped: Boolean,
)

@Serializable
data class RouteIncidentDto(val id: String, val title: String, val severity: String, val source: String?)
