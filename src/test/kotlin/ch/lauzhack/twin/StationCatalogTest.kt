package ch.lauzhack.twin

import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.state.Station
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The persisted station catalog is loaded at startup: stations without a train in service are still found. */
class StationCatalogTest {
    @Test
    fun `catalog stations are found nearby without any train in service`() = testApplication {
        val db = testDb()
        db.saveStations(listOf(Station("8507100", "Thun", 46.754852, 7.629607)))
        eventually({ db.stations() }) { it.isNotEmpty() }
        val st = LiveState(db.stations())
        application { configureHttp(st, db) { true } }
        val r = client.get("/api/v1/stations/nearby?lat=46.75489&lon=7.629645&radiusM=500&limit=3")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("8507100" in r.bodyAsText(), r.bodyAsText())
    }

    @Test
    fun `live info still corrects a seeded station`() {
        val st = LiveState(listOf(Station("A", "Old name", 1.0, 1.0)))
        st.upsertInfo(info("t-1", aheadFrom = 0))
        assertEquals("Station A", st.station("A")!!.name)
    }
}
