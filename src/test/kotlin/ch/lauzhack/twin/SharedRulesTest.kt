package ch.lauzhack.twin

import ch.lauzhack.twin.common.BBoxBuilder
import ch.lauzhack.twin.common.coordsOf
import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.common.simplifyGeometry
import ch.lauzhack.twin.common.spacedLine
import ch.lauzhack.twin.query.Severity
import ch.lauzhack.twin.query.lineIn
import ch.lauzhack.twin.query.mentionsLine
import ch.lauzhack.twin.query.sameLine
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules every feature shares (common/, query/Severity, query/Lines): one implementation, one behaviour. */
class SharedRulesTest {
    @Test
    fun severityParsesRanksAndColours() {
        assertEquals(Severity.CRITICAL, Severity.parse("Critical"))
        assertEquals(Severity.INFO, Severity.parse("nonsense"))
        assertEquals(Severity.INFO, Severity.parse(null))
        assertNull(Severity.of("nonsense"))
        assertEquals(listOf("critical", "major", "minor", "info"), Severity.IDS)
        assertTrue(Severity.CRITICAL.rank < Severity.MINOR.rank)
        assertEquals(listOf("red", "red", "orange", "blue"), Severity.entries.map { it.colour })
        assertEquals(Severity.CRITICAL, Severity.fromEffect("interrupted"))
        assertEquals(Severity.MAJOR, Severity.fromEffect("limited"))
        assertEquals(Severity.INFO, Severity.fromEffect("resumed"))
        assertEquals(Severity.CRITICAL, Severity.fromEffect(null, unknown = Severity.CRITICAL))
    }

    @Test
    fun linesMatchIgnoringCaseAndSpaces() {
        assertEquals("IC1", lineKey(" ic 1 "))
        assertEquals("IC 1", spacedLine("IC1"))
        assertTrue(sameLine("IC1", "IC 1"))
        assertTrue(sameLine("s3", "S3"))
        assertFalse(sameLine("S3", "S30"))
        assertFalse(sameLine(null, "S3"))
        assertEquals("IC8", lineIn("Is the IC 8 to Brig on time?"))
        assertEquals("S3", lineIn("when is the next S3"))
        assertEquals("IR15", lineIn("ir15 delays"))
        assertNull(lineIn("it's 10 past"))
        assertTrue(mentionsLine("IC1", "Delays on the IC 1 between Bern and Thun"))
        assertFalse(mentionsLine("IC1", "Delays on the IC 18"))
    }

    @Test
    fun lineFilterOfTheApiIgnoresSpaces() = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-ic", aheadFrom = 0, stops = listOf("X", "Y"), line = "IC 1"))
        application { configureHttp(st, testDb()) { true } }
        for (q in listOf("IC1", "IC%201", "ic1")) {
            val r = client.get("/api/v1/trains?line=$q")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(1, Json.parseToJsonElement(r.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.int, q)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/lines/IC1").status)
    }

    @Test
    fun geometryHelpersShareOneCoordinateWalk() {
        val g = Json.parseToJsonElement(
            """{"type":"MultiLineString","coordinates":[[[7.0,46.0],[7.00001,46.00001],[7.5,46.5]],[[8.0,47.0],[8.1,47.1]]]}""",
        ) as JsonObject
        assertEquals(5, coordsOf(g).size)
        assertEquals(listOf(7.0, 46.0, 8.1, 47.1), BBoxBuilder().apply { addGeometry(g) }.raw())
        val simple = simplifyGeometry(g)!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(2, simple[0].jsonArray.size) // the 1 m wiggle is dropped
    }
}
