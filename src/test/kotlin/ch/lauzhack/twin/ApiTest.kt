package ch.lauzhack.twin

import ch.lauzhack.twin.common.searchKey
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiTest {
    private fun seeded(): Pair<LiveState, Database> {
        val st = LiveState()
        st.upsertInfo(info("t-at-b", aheadFrom = 1))                          // standing at B
        st.upsertInfo(info("t-to-c", aheadFrom = 2, delayMs = 120_000))       // between B and C, 2 min late
        st.upsertInfo(info("t-other", aheadFrom = 0, stops = listOf("X", "Y"), line = "IC1"))
        st.replacePositions(
            mapOf("t-at-b" to pos("t-at-b", "B"), "t-to-c" to pos("t-to-c", null, delaySec = 120), "t-other" to pos("t-other", "X")),
            T0 + 1_200_000, // twin clock = planned time at C
        )
        val db = testDb()
        db.incidents.upsert(
            Incident(id = "i-1", title = "Fault C-D", severity = "critical", createdAt = 1, updatedAt = 1,
                scope = IncidentScope(kind = "section", from = "C", to = "D")),
            "{}",
        )
        db.awaitIncidents()
        return st to db
    }

    private fun api(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val (st, db) = seeded()
        application { configureHttp(st, db) { true } }
        test()
    }

    private suspend fun ApplicationTestBuilder.json(url: String, expect: HttpStatusCode = HttpStatusCode.OK): JsonElement {
        val r: HttpResponse = client.get(url)
        assertEquals(expect, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText())
    }

    private val JsonElement.o get() = jsonObject
    private fun JsonElement.s(k: String) = jsonObject[k]!!.jsonPrimitive.content

    @Test
    fun trainDetailHasStopStatesAndIncidents() = api {
        val d = json("/api/v1/trains/t-to-c").o
        assertEquals("C", d["train"]!!.o["nextStop"]!!.s("stopId"))
        assertEquals(listOf("passed", "passed", "next", "upcoming"), d["stops"]!!.jsonArray.map { it.s("state") })
        assertEquals(120, d["stops"]!!.jsonArray[2].o["arrDelaySec"]!!.jsonPrimitive.int)
        assertEquals(listOf("i-1"), d["incidents"]!!.jsonArray.map { it.s("id") })
        assertEquals("route", d["incidents"]!!.jsonArray[0].s("match")) // C and D both ahead

        // t-other (X->Y) never touches C-D
        assertEquals(0, json("/api/v1/trains/t-other/incidents").jsonArray.size)

        val atB = json("/api/v1/trains/t-at-b").o["train"]!!.o
        assertEquals("B", atB["currentStop"]!!.s("stopId"))
        assertEquals("C", atB["nextStop"]!!.s("stopId"))
    }

    @Test
    fun trainListFilters() = api {
        assertEquals(3, json("/api/v1/trains").o["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("t-other"), json("/api/v1/trains?line=ic1").o["items"]!!.jsonArray.map { it.s("trainId") })
        assertEquals(setOf("t-at-b", "t-to-c"), json("/api/v1/trains?stationId=C").o["items"]!!.jsonArray.map { it.s("trainId") }.toSet())
        assertEquals(listOf("t-to-c"), json("/api/v1/trains?minDelaySec=60").o["items"]!!.jsonArray.map { it.s("trainId") })
        assertEquals(2, json("/api/v1/trains?atStation=true").o["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("upcoming", "upcoming"), json("/api/v1/trains/t-at-b/stops?state=upcoming").jsonArray.drop(1).map { it.s("state") })
    }

    @Test
    fun stationBoardListsOnlyTrainsStillToCome() = api {
        val b = json("/api/v1/stations/B/board").o
        val entries = b["entries"]!!.jsonArray
        assertEquals(listOf("t-at-b"), entries.map { it.s("trainId") }) // t-to-c already left B
        assertEquals(true, entries[0].o["atStation"]!!.jsonPrimitive.boolean)

        val c = json("/api/v1/stations/C/board").o["entries"]!!.jsonArray
        assertEquals(listOf("t-at-b", "t-to-c"), c.map { it.s("trainId") }) // sorted by expected time (t-to-c is 2 min late)

        // Terminus D: no departures, but arrivals
        assertEquals(0, json("/api/v1/stations/D/board?type=departures").o["entries"]!!.jsonArray.size)
        assertEquals(2, json("/api/v1/stations/D/board?type=arrivals").o["entries"]!!.jsonArray.size)

        val st = json("/api/v1/stations/C").o
        assertEquals(2, st["trainsUpcoming"]!!.jsonPrimitive.int)
        assertEquals(listOf("i-1"), st["incidents"]!!.jsonArray.map { it.s("id") })
    }

    @Test
    fun stationScreenIsCompact() = api {
        val sc = json("/api/v1/stations/C/screen").o
        assertEquals("C", sc.s("stopId"))
        val deps = sc["departures"]!!.jsonArray
        assertEquals(2, deps.size)
        assertEquals(120, deps[1].o["delaySec"]!!.jsonPrimitive.int)          // t-to-c is 2 min late
        assertEquals(setOf("plannedTime", "delaySec", "line", "category", "to", "cancelled", "atStation"), deps[0].o.keys)
        val inc = sc["incidents"]!!.jsonArray.single().o
        assertEquals("i-1", inc.s("id"))
        assertEquals(true, inc["atStation"]!!.jsonPrimitive.boolean)
        assertEquals(2, inc["trains"]!!.jsonPrimitive.int)                   // both trains still call at C, then D

        assertEquals(1, json("/api/v1/stations/C/screen?limit=1").o["departures"]!!.jsonArray.size)
        assertEquals("bad_limit", json("/api/v1/stations/C/screen?limit=50", HttpStatusCode.BadRequest).o["error"]!!.s("code"))
        assertEquals("station_not_found", json("/api/v1/stations/nope/screen", HttpStatusCode.NotFound).o["error"]!!.s("code"))
    }

    @Test
    fun stationScreenAsText() = api {
        val r = client.get("/api/v1/stations/C/screen.txt")
        assertEquals(HttpStatusCode.OK, r.status)
        val lines = r.bodyAsText().trimEnd('\n').split('\n')
        assertEquals("S", lines[0].split('|')[0])
        assertEquals(2, lines.count { it.startsWith("D|") })
        val late = lines.filter { it.startsWith("D|") }[1].split('|')
        assertEquals(8, late.size)
        assertEquals("2", late[2])                                              // t-to-c: 120 s late = 2 min
        val inc = lines.single { it.startsWith("I|") }.split('|')
        assertEquals(listOf("I", "critical", "1", "2", "Fault C-D"), inc.take(5))

        // unchanged board: 304 with no body
        val etag = r.headers[HttpHeaders.ETag]!!
        val again = client.get("/api/v1/stations/C/screen.txt") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
        assertEquals("", again.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/stations/C/screen.txt") { header(HttpHeaders.IfNoneMatch, "\"x\"") }.status)

        assertEquals(0, client.get("/api/v1/stations/C/screen.txt?incidents=0").bodyAsText().lines().count { it.startsWith("I|") })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/stations/nope/screen.txt").status)
    }

    @Test
    fun stationSearch() = api {
        val r = json("/api/v1/stations?q=station%20b").jsonArray
        assertEquals(listOf("B"), r.map { it.s("stopId") })
        // accent- and case-insensitive: "stätion b" and "STATION B" both find "Station B"
        assertEquals(listOf("B"), json("/api/v1/stations?q=st%C3%A4tion%20b").jsonArray.map { it.s("stopId") })
        assertEquals(listOf("B"), json("/api/v1/stations?q=STATION%20B").jsonArray.map { it.s("stopId") })
    }

    @Test
    fun searchKeyIgnoresAccents() {
        assertEquals("zurich hb", searchKey(" Zürich HB "))
        assertEquals("geneve", searchKey("Genève"))
        assertEquals("strasse", searchKey("Straße"))
    }

    @Test
    fun errorsAreUniform() = api {
        assertEquals("train_not_found", json("/api/v1/trains/nope", HttpStatusCode.NotFound).o["error"]!!.s("code"))
        assertEquals("station_not_found", json("/api/v1/stations/nope/board", HttpStatusCode.NotFound).o["error"]!!.s("code"))
        assertEquals("bad_limit", json("/api/v1/trains?limit=0", HttpStatusCode.BadRequest).o["error"]!!.s("code"))
        assertEquals("bad_type", json("/api/v1/stations/B/board?type=foo", HttpStatusCode.BadRequest).o["error"]!!.s("code"))
        assertEquals("bad_bbox", json("/api/v1/trains?bbox=1,2", HttpStatusCode.BadRequest).o["error"]!!.s("code"))
        assertEquals("not_found", json("/api/v1/nothing", HttpStatusCode.NotFound).o["error"]!!.s("code"))
    }

    @Test
    fun incidentEndpoints() = api {
        assertEquals(1, json("/api/v1/incidents").jsonArray.size)
        assertEquals(0, json("/api/v1/incidents?severity=minor").jsonArray.size)
        assertEquals("i-1", json("/api/v1/incidents/i-1").s("id"))
        assertEquals(listOf("created"), json("/api/v1/incidents/i-1/history").jsonArray.map { it.s("kind") })
    }

}
