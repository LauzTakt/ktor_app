package ch.lauzhack.twin

import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Station screen "Next departures": every board entry carries the incidents concerning its train. */
class BoardIncidentsTest {
    @Test
    fun departuresCarryTheirTrainsIncidents() = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-hit", aheadFrom = 1))                                          // A B C D, at B
        st.upsertInfo(info("t-ok", aheadFrom = 0, stops = listOf("B", "X"), line = "IC 1"))  // B -> X
        st.replacePositions(mapOf("t-hit" to pos("t-hit", "B"), "t-ok" to pos("t-ok", "B")), T0 + 600_000)
        val db = testDb()
        db.upsertIncident(Incident(id = "i-cd", title = "Fault C-D", severity = "major", createdAt = 1, updatedAt = 1, scope = IncidentScope(kind = "section", from = "C", to = "D")), "{}")
        db.awaitIncidents()
        application { configureHttp(st, db) { true } }

        val entries = Json.parseToJsonElement(client.get("/api/v1/stations/B/board?type=departures").bodyAsText()).jsonObject["entries"]!!.jsonArray
        val byId = entries.associate { it.jsonObject["trainId"]!!.jsonPrimitive.content to it.jsonObject["incidents"]!!.jsonArray }
        assertEquals(listOf("i-cd"), byId["t-hit"]!!.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("route", byId["t-hit"]!![0].jsonObject["match"]!!.jsonPrimitive.content)
        assertEquals(0, byId["t-ok"]!!.size)
    }
}
