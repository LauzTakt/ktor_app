package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.T0
import ch.lauzhack.twin.common.angleDiff
import ch.lauzhack.twin.common.haversineM
import ch.lauzhack.twin.common.move
import ch.lauzhack.twin.configureHttp
import ch.lauzhack.twin.info
import ch.lauzhack.twin.model.TrainPosition
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.testDb
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Passenger endpoints: nearby trains/stations, destinations, connections. Stations A..D sit at 47.00+i·0.01, 8.00+i·0.01. */
class JourneyTest {
    private fun at(id: String, lat: Double, lon: Double, bearing: Double, speed: Double, atStop: String? = null) =
        TrainPosition(trainId = id, lat = lat, lon = lon, bearing = bearing, speedKmh = speed, atStopId = atStop,
            motion = if (speed > 0) "moving" else "stopped", ts = T0, streamId = "s")

    private fun api(onlyOneTrain: Boolean = false, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-ab", aheadFrom = 1))
        if (onlyOneTrain) { // the fixture places stops by index, so other runs would move the stations around
            application { configureHttp(st, testDb()) { true } }
            return@testApplication test()
        }                                  // A->B->C->D, between A and B
        st.upsertInfo(info("t-rev", aheadFrom = 1, stops = listOf("D", "C", "B", "A"), line = "S2")) // D->A
        st.upsertInfo(info("t-at-b", aheadFrom = 1, stops = listOf("B", "C"), line = "IC1"))        // standing at B, ends at C
        st.replacePositions(
            mapOf(
                // Both trains ~100 m apart near (47.005, 8.005), one heading NE (45°), one SW (225°).
                "t-ab" to at("t-ab", 47.0050, 8.0050, 45.0, 100.0),
                "t-rev" to at("t-rev", 47.0058, 8.0050, 225.0, 100.0),
                "t-at-b" to at("t-at-b", 47.01, 8.01, 0.0, 0.0, atStop = "B"),
            ),
            T0,
        )
        val db = testDb()
        application { configureHttp(st, db) { true } }
        test()
    }

    private suspend fun ApplicationTestBuilder.json(url: String, expect: HttpStatusCode = HttpStatusCode.OK): JsonElement {
        val r = client.get(url)
        assertEquals(expect, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText())
    }

    private fun JsonElement.s(k: String) = jsonObject[k]!!.jsonPrimitive.content

    @Test
    fun geoHelpers() {
        assertEquals(1112.0, haversineM(47.0, 8.0, 47.01, 8.0), 2.0) // ~1.11 km per 0.01° of latitude
        val (lat, lon) = move(47.0, 8.0, 90.0, 1000.0)
        assertEquals(47.0, lat, 1e-6)
        assertEquals(1000.0, haversineM(47.0, 8.0, lat, lon), 1.0)
        assertEquals(10.0, angleDiff(355.0, 5.0), 1e-9)
        assertEquals(180.0, angleDiff(45.0, 225.0), 1e-9)
    }

    @Test
    fun nearbyTrainsRankByHeadingWhenMoving() = api {
        // Standing user right between the two: nearest first.
        val still = json("/api/v1/trains/nearby?lat=47.0054&lon=8.005&radiusM=500").jsonArray
        assertEquals(2, still.size)
        assertTrue(still.all { it.jsonObject["headingDiffDeg"]!!.jsonPrimitive.content == "null" })

        // User moving SW at 100 km/h, slightly closer to the NE-bound train: the SW-bound one wins.
        val moving = json("/api/v1/trains/nearby?lat=47.0053&lon=8.005&radiusM=500&headingDeg=225&speedKmh=100").jsonArray
        assertEquals("t-rev", moving[0].jsonObject["train"]!!.s("trainId"))
        assertEquals(0, moving[0].jsonObject["headingDiffDeg"]!!.jsonPrimitive.int)

        // Extrapolation: 10 s later the NE-bound train is ~280 m further on.
        val later = json("/api/v1/trains/nearby?lat=47.0050&lon=8.0050&radiusM=2000&ts=${T0 + 10_000}").jsonArray
        val ab = later.first { it.jsonObject["train"]!!.s("trainId") == "t-ab" }.jsonObject
        assertTrue(ab["distanceM"]!!.jsonPrimitive.int in 260..300, ab.toString())

        assertEquals(0, json("/api/v1/trains/nearby?lat=46.0&lon=7.0&radiusM=500").jsonArray.size)
        assertEquals("bad_lat", json("/api/v1/trains/nearby?lon=8", HttpStatusCode.BadRequest).jsonObject["error"]!!.s("code"))
        assertEquals("bad_lat", json("/api/v1/trains/nearby?lat=999&lon=8", HttpStatusCode.BadRequest).jsonObject["error"]!!.s("code"))
    }

    @Test
    fun nearbyStationsSortedByDistance() = api(onlyOneTrain = true) {
        val r = json("/api/v1/stations/nearby?lat=47.0101&lon=8.0101&radiusM=3000&limit=3").jsonArray
        assertEquals(listOf("B", "A", "C").toSet(), r.map { it.jsonObject["station"]!!.s("stopId") }.toSet())
        assertEquals("B", r[0].jsonObject["station"]!!.s("stopId"))
        assertTrue(r[0].jsonObject["distanceM"]!!.jsonPrimitive.int < 30)
    }

    @Test
    fun destinationsFromAStation() = api {
        // From B: t-ab goes on to C, D; t-at-b to C; t-rev (D->C->B->A) to A.
        val d = json("/api/v1/stations/B/destinations").jsonArray
        val byId = d.associate { it.jsonObject["station"]!!.s("stopId") to it.jsonObject["trains"]!!.jsonPrimitive.int }
        assertEquals(mapOf("C" to 2, "D" to 1, "A" to 1), byId)
        assertEquals("C", d[0].jsonObject["station"]!!.s("stopId")) // most served first
        assertEquals(listOf("D"), json("/api/v1/stations/B/destinations?q=station%20d").jsonArray.map { it.jsonObject["station"]!!.s("stopId") })
        assertEquals("station_not_found", json("/api/v1/stations/nope/destinations", HttpStatusCode.NotFound).jsonObject["error"]!!.s("code"))
    }

    @Test
    fun connectionsBetweenTwoStations() = api {
        val c = json("/api/v1/stations/B/connections?to=C&windowMin=1440").jsonObject
        val entries = c["entries"]!!.jsonArray
        assertEquals(listOf("t-at-b", "t-ab"), entries.map { it.jsonObject["train"]!!.s("trainId") }) // standing at B first
        assertEquals("true", entries[0].jsonObject["atStation"]!!.jsonPrimitive.content)
        assertEquals(10, entries[1].jsonObject["durationMin"]!!.jsonPrimitive.int)
        assertEquals(0, entries[1].jsonObject["stopsBetween"]!!.jsonPrimitive.int)

        // Wrong direction: nothing goes from C back to B... except t-rev (D->C->B->A).
        assertEquals(listOf("t-rev"), json("/api/v1/stations/C/connections?to=B&windowMin=1440").jsonObject["entries"]!!.jsonArray.map { it.jsonObject["train"]!!.s("trainId") })
        assertEquals("bad_to", json("/api/v1/stations/B/connections", HttpStatusCode.BadRequest).jsonObject["error"]!!.s("code"))
    }
}
