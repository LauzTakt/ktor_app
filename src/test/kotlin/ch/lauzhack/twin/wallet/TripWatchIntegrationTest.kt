package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.T0
import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.assistant.CachedJourney
import ch.lauzhack.twin.assistant.JourneyCache
import ch.lauzhack.twin.assistant.PlanQuery
import ch.lauzhack.twin.configureHttp
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.info
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripWatchIntegrationTest {
    @Test
    fun passengerSelectionIsVerifiedAndOnlyItsOwnerCanStopIt() = testApplication {
        val state = LiveState()
        val offset = System.currentTimeMillis() - T0 + 10_000L
        val train = info("service-a", 0, listOf("A", "B", "C"))
        state.upsertInfo(train.copy(calls = train.calls.map {
            it.copy(
                plannedArr = it.plannedArr?.plus(offset), plannedDep = it.plannedDep?.plus(offset),
                expectedArr = it.expectedArr?.plus(offset), expectedDep = it.expectedDep?.plus(offset),
            )
        }))
        val db = Database(":memory:")
        application {
            configureHttp(state, db, wallet = WalletConfig(enabled = true, path = ":memory:")) { false }
        }
        val base = "/api/v1/trip-watches"
        suspend fun save(to: String) = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody("""{"trainId":"service-a","fromStopId":"A","toStopId":"$to"}""")
        }
        assertEquals(HttpStatusCode.Conflict, save("unserved").status)
        val response = save("C")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val cookie = response.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val view = response.bodyAsText().json()
        val url = "$base/${view["id"]!!.jsonPrimitive.content}"
        assertTrue(view["canManage"]!!.jsonPrimitive.boolean)
        assertFalse(view.containsKey("appleToken"))
        assertFalse(client.get(url).bodyAsText().json()["canManage"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, client.delete(url).status)
        val ended = client.delete(url) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, ended.status)
        assertEquals("ended", ended.bodyAsText().json()["kind"]!!.jsonPrimitive.content)
        assertEquals("[]", client.get(base) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
    }

    @Test
    fun sameServiceCanSkipOnePassengersStopAndStillServeAnother() = runBlocking {
        val state = LiveState()
        val info = info("shared-service", 0, listOf("A", "B", "C"))
        state.upsertInfo(info)
        TripWatchStore(":memory:").use { store ->
            val journeys = TripWatchJourneys(TwinQueries(state) { emptyList() }, JourneyCache(), null)
            val service = TripWatchService(store, journeys, "", null, null, { true }, { T0 - 60_000L })
            val first = assertNotNull(service.start("a", TripWatchSelection(trainId = info.trainId, fromStopId = "A", toStopId = "B")))
            val second = assertNotNull(service.start("b", TripWatchSelection(trainId = info.trainId, fromStopId = "A", toStopId = "C")))
            state.upsertInfo(info.copy(calls = info.calls.map { if (it.stopId == "B") it.copy(skipped = true) else it }))
            service.refresh()
            val changed = assertNotNull(store.find(first.id))
            assertEquals(TripWatchAction.CheckTrip, changed.advice.action)
            assertEquals(changed.revision, changed.alertRevision)
            assertEquals(second, store.find(second.id))
            service.refresh()
            assertEquals(changed, store.find(first.id))
        }
    }

    @Test
    fun connectingSelectionStoresLegsAndMovesRelevanceOnProgress() = runBlocking {
        val cache = JourneyCache()
        cache.put(CachedJourney(
            """{"id":"two-legs","legs":[
              {"trainId":"rail-21","line":"IR21","from":{"id":"L","name":"Lake","lat":46.5,"lon":6.5,"plannedDep":$T0},
               "to":{"id":"M","name":"Exchange","lat":46.7,"lon":6.7,"plannedArr":${T0+600_000}}},
              {"trainId":"bus-99","line":"EV","from":{"id":"M","name":"Exchange","lat":46.7,"lon":6.7,"plannedDep":${T0+720_000}},
               "to":{"id":"N","name":"Hill","plannedArr":${T0+1_300_000}}}
            ]}""".json(),
            PlanQuery("L", "N", null, 4, null), null, System.currentTimeMillis(),
        ))
        val file = Files.createTempFile("trip-watch", ".sqlite")
        try {
            val source = TripWatchJourneys(TwinQueries(LiveState()) { emptyList() }, cache, null)
            val id = TripWatchStore(file.toString()).use { store ->
                val service = TripWatchService(store, source, "", null, null, { true }, { T0 - 60_000 })
                val watch = assertNotNull(service.start("owner", TripWatchSelection(journeyId = "two-legs")))
                assertEquals("L", watch.relevantLeg()?.from?.id)
                watch.id
            }
            TripWatchStore(file.toString()).use { store ->
                val service = TripWatchService(store, source, "", null, null, { true }, { T0 - 60_000 })
                val first = assertNotNull(store.find(id))
                assertEquals(listOf("rail-21", "bus-99"), first.legs.map { it.serviceId })
                val onBoard = assertNotNull(service.change(id, "owner", "boarded"))
                assertIs<TripWatchAction.Alight>(onBoard.advice.action)
                assertEquals("M", onBoard.relevantLeg()?.from?.id)
                val second = assertNotNull(service.change(id, "owner", "alighted"))
                assertEquals("bus-99", (second.advice.action as TripWatchAction.Board).serviceId)
                assertEquals(0L, second.alertRevision)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
}
