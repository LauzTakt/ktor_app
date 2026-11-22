package ch.lauzhack.twin.query

import ch.lauzhack.twin.configureHttp
import ch.lauzhack.twin.info
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.testDb
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one station matcher behind REST `?q=`, the assistant and the passenger app. */
class StationSearchTest {
    private val names = listOf("Zürich HB", "Zürich Flughafen", "Zürich Oerlikon", "Bern", "Genève", "Genève-Aéroport", "Biel/Bienne")
    private val busy = mapOf("Zürich HB" to 900, "Zürich Flughafen" to 300, "Zürich Oerlikon" to 400)

    private fun rank(q: String) = StationSearch.rank(q, names, { it }) { busy[it] ?: 10 }

    @Test
    fun cleansQueries() {
        assertEquals("zurich hb", StationSearch.cleanQuery("Zürich Hauptbahnhof"))
        assertEquals("zurich hb", StationSearch.cleanQuery("zuerich"))
        assertEquals("zurich hb", StationSearch.cleanQuery("Zuerich HB")) // alias already ends with hb
        assertEquals("bern", StationSearch.cleanQuery("Bern station"))
        assertEquals("geneve", StationSearch.cleanQuery("Geneva"))
        assertEquals("biel bienne", StationSearch.cleanQuery("Biel"))
    }

    @Test
    fun ranksExactThenPrefixThenWordsThenSubstring() {
        assertEquals("Zürich HB", rank("zurich").first())
        assertEquals(listOf("Zürich Flughafen"), rank("zur flug"))
        assertEquals("Genève-Aéroport", rank("Geneva airport").first())
        assertEquals("Genève", rank("genf").first())
        assertEquals(listOf("Zürich Oerlikon"), rank("oerlikon"))
        assertEquals(emptyList(), rank("Lugano"))
        assertNull(StationSearch.tier("lugano", "Bern"))
    }

    @Test
    fun restSearchUsesTheSameRules() = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 0, stops = listOf("8501008", "8501026")))
        application { configureHttp(st, testDb()) { true } }
        // the fixture names stops "Station <id>": search by a word, by id, and with filler words
        fun names(body: String) = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject["stopId"]!!.jsonPrimitive.content }
        assertEquals(listOf("8501008"), names(client.get("/api/v1/stations?q=8501008").bodyAsText()))
        assertEquals(2, names(client.get("/api/v1/stations?q=station").bodyAsText()).size)
        assertEquals(listOf("8501026"), names(client.get("/api/v1/stations?q=Station%208501026%20Bahnhof").bodyAsText()))
    }
}
