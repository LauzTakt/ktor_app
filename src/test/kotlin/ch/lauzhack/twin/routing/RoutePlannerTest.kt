package ch.lauzhack.twin.routing

import ch.lauzhack.twin.common.UnavailableException
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.StopDto
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TrainLiveDto
import ch.lauzhack.twin.query.TrainSummaryDto
import ch.lauzhack.twin.twinapi.TwinException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePlannerTest {
    private val t0 = 1_800_000_000_000L
    private fun m(min: Int) = t0 + min * 60_000L

    private fun leg(train: String, from: String, dep: Int, to: String, arr: Int, status: String = "on time", incidents: String = "") = """
        {"trainId":"$train","line":"$train","category":"IC","headsign":"$to",
         "from":{"id":"$from","name":"$from","plannedDep":${m(dep)},"expectedDep":${m(dep)}},
         "to":{"id":"$to","name":"$to","plannedArr":${m(arr)},"expectedArr":${m(arr)}},
         "stopsBetween":0,"delaySec":0,"status":"$status","incidents":[$incidents]}"""

    private fun journey(id: String, vararg legs: String) = """{"id":"$id","legs":[${legs.joinToString(",")}]}"""

    private fun planOf(vararg journeys: String): JsonObject = Json.parseToJsonElement(
        """{"from":{"id":"A","name":"A"},"to":{"id":"B","name":"B"},"journeys":[${journeys.joinToString(",")}]}""",
    ).jsonObject

    private fun planner(
        plan: JsonObject,
        live: (String) -> TrainLiveDto? = { null },
        incidents: (String) -> List<IncidentDto> = { emptyList() },
    ) = RoutePlanner({ plan }, live, incidents, now = { t0 })

    @Test
    fun earliestCleanRouteIsRecommended() = runBlocking {
        val r = planner(planOf(journey("j1", leg("IC1", "A", 5, "B", 60)), journey("j2", leg("IC2", "A", 10, "B", 70)))).routes("A", "B", null, 4)
        assertEquals("j1", r.recommendedId)
        assertEquals(listOf("j1", "j2"), r.routes.map { it.journeyId })
        assertEquals("low", r.routes[0].risk)
        assertEquals("Arrives first, no known problems", r.routes[0].why)
    }

    @Test
    fun severeIncidentMakesTheSaferRouteWin() = runBlocking {
        val bad = """{"id":"i1","title":"Interrupted A - B","severity":"critical","source":"sbb"}"""
        val r = planner(planOf(journey("j1", leg("IC1", "A", 5, "B", 60, incidents = bad)), journey("j2", leg("IC2", "A", 10, "B", 70)))).routes("A", "B", null, 4)
        assertEquals("j2", r.recommendedId)
        assertTrue(r.routes[0].why!!.startsWith("Safer: 10 min later"))
        assertEquals("high", r.routes[1].risk)
    }

    @Test
    fun cancelledLegAndMissedChangeAreInfeasible() = runBlocking {
        val r = planner(
            planOf(
                journey("cancelled", leg("IC1", "A", 5, "B", 50, status = "cancelled")),
                journey("missed", leg("IR1", "A", 6, "C", 30), leg("IR2", "C", 29, "B", 55)),
                journey("ok", leg("IC3", "A", 10, "B", 80)),
            ),
        ).routes("A", "B", null, 4)
        assertEquals("ok", r.recommendedId)
        assertEquals(listOf(false, false), r.routes.drop(1).map { it.feasible })
        assertTrue(r.routes.first { it.journeyId == "missed" }.notes.any { "missed" in it })
    }

    @Test
    fun liveDataOverridesThePlanner() = runBlocking {
        fun stop(seq: Int, id: String, arr: Int?, dep: Int?, skipped: Boolean = false) = StopDto(
            seq, id, id, null, null, arr?.let(::m), dep?.let(::m), arr?.let { m(it + 4) }, dep?.let { m(it + 4) }, null, null,
            arr?.let { 240 }, dep?.let { 240 }, skipped, StopState.upcoming,
        )
        val train = TrainSummaryDto("IR1", "IR1", "IR", null, null, "B", "active", null, null, 240, null, null, null, null)
        val live = mapOf(
            // IR1 runs 4 min late: the 2 min change at C becomes a missed one.
            "IR1" to TrainLiveDto(train, listOf(stop(0, "A", null, 6), stop(1, "C", 30, 31))),
            "IC1" to TrainLiveDto(train.copy(trainId = "IC1"), listOf(stop(0, "A", null, 5), stop(1, "B", 60, null, skipped = true))),
        )
        val r = planner(
            planOf(journey("j1", leg("IC1", "A", 5, "B", 60)), journey("j2", leg("IR1", "A", 6, "C", 30), leg("IR2", "C", 32, "B", 55))),
            live = { live[it] },
        ).routes("A", "B", null, 4)
        assertTrue(r.routes.none { it.feasible })
        assertEquals(null, r.recommendedId)
        assertFalse(r.routes.first { it.journeyId == "j2" }.legs[0].changeMin!! >= 0)
        assertTrue(r.routes.first { it.journeyId == "j1" }.legs[0].to.skipped)
    }

    @Test
    fun plannerDownIsUnavailable() {
        val p = RoutePlanner({ throw TwinException(0, "down") }, { null }, { emptyList() })
        assertFailsWith<UnavailableException> { runBlocking { p.routes("A", "B", null, 4) } }
    }
}
