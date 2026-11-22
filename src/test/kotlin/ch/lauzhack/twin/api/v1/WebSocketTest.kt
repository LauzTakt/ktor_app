package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.T0
import ch.lauzhack.twin.awaitIncidents
import ch.lauzhack.twin.configureHttp
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.info
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.pos
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.testDb
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSocketTest {
    private lateinit var db: Database

    private fun ws(test: suspend DefaultClientWebSocketSession.() -> Unit) = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-at-b", aheadFrom = 1))                          // standing at B
        st.upsertInfo(info("t-to-c", aheadFrom = 2, delayMs = 120_000))       // between B and C
        st.upsertInfo(info("t-other", aheadFrom = 0, stops = listOf("X", "Y"), line = "IC1"))
        st.replacePositions(
            mapOf("t-at-b" to pos("t-at-b", "B"), "t-to-c" to pos("t-to-c", null, delaySec = 120), "t-other" to pos("t-other", "X")),
            T0 + 1_200_000,
        )
        db = testDb()
        upsert("i-1", IncidentScope(kind = "section", from = "C", to = "D"))
        db.awaitIncidents()
        application { configureHttp(st, db) { true } }
        wsClient().webSocket("/api/v1/ws") { test() }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private fun upsert(id: String, scope: IncidentScope, updatedAt: Long = 1) = db.incidents.upsert(
        Incident(id = id, title = "Fault $id", severity = "major", createdAt = 1, updatedAt = updatedAt, scope = scope), "{}",
    )

    private suspend fun DefaultClientWebSocketSession.req(s: String) = send(Frame.Text(s))

    /** Next message of [type], skipping others (collected into [skipped]). */
    private suspend fun DefaultClientWebSocketSession.next(type: String, skipped: MutableList<JsonObject>? = null): JsonObject =
        withTimeout(5_000) {
            while (true) {
                val m = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                if (m.s("type") == type) return@withTimeout m
                skipped?.add(m)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun trainSubscriptionGetsSnapshotUpdatesAndImmediateIncidents() = ws {
        // Very slow frequency: the incident below must still arrive immediately.
        req("""{"op":"subscribe","topic":"train","id":"t-to-c","frequencySec":3600}""")
        val sub = next("subscribed")
        assertEquals(3600, sub["frequencySec"]!!.jsonPrimitive.int)

        val snap = next("incident")
        assertEquals("active", snap.s("event"))
        assertEquals("i-1", snap["incident"]!!.jsonObject.s("id"))
        assertEquals("route", snap["incident"]!!.jsonObject.s("match"))

        val upd = next("train_update")
        assertEquals("C", upd["data"]!!.jsonObject["train"]!!.jsonObject["nextStop"]!!.jsonObject.s("stopId"))
        assertEquals(4, upd["data"]!!.jsonObject["stops"]!!.jsonArray.size)

        // New incident on the train's line -> pushed right away; one on an unrelated line -> not pushed.
        upsert("i-other", IncidentScope(kind = "line", line = "IC1"))
        upsert("i-line", IncidentScope(kind = "line", line = "S1"))
        val inc = next("incident")
        assertEquals("i-line", inc["incident"]!!.jsonObject.s("id"))
        assertEquals("created", inc.s("event"))
        assertEquals("line", inc["trains"]!!.jsonArray.single().jsonObject.s("match"))

        db.incidents.remove("i-1")
        val rm = next("incident")
        assertEquals("removed", rm.s("event"))
        assertEquals("i-1", rm["incident"]!!.jsonObject.s("id"))
    }

    @Test
    fun stationSubscriptionCoversIncidentsOfItsTrains() = ws {
        req("""{"op":"subscribe","topic":"station","id":"C","frequencySec":1,"windowMin":60}""")
        next("subscribed")
        val snap = next("incident")
        assertEquals("endpoint", snap["incident"]!!.jsonObject.s("match")) // C is one end of C-D
        assertEquals(listOf("t-at-b", "t-to-c"), snap["trains"]!!.jsonArray.map { it.jsonObject.s("trainId") })

        val upd = next("station_update")
        assertEquals(listOf("t-at-b", "t-to-c"), upd["data"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject.s("trainId") })
        // frequency 1 s: a second update follows
        next("station_update")

        // Incident targeting a train that calls at C, but not C itself -> still notified, station match null.
        upsert("i-train", IncidentScope(kind = "train", trainId = "t-to-c"))
        val inc = next("incident")
        assertEquals("i-train", inc["incident"]!!.jsonObject.s("id"))
        assertTrue(inc["incident"]!!.jsonObject["match"].toString() == "null")
        assertEquals(listOf("t-to-c"), inc["trains"]!!.jsonArray.map { it.jsonObject.s("trainId") })
    }

    @Test
    fun screenSubscriptionSendsCompactUpdatesAndRefreshesOnIncidents() = ws {
        req("""{"op":"subscribe","topic":"screen","id":"C","frequencySec":3600,"limit":1}""")
        val sub = next("subscribed")
        assertEquals(1, sub["limit"]!!.jsonPrimitive.int)

        val first = next("screen_update")["data"]!!.jsonObject
        assertEquals("C", first.s("stopId"))
        assertEquals(1, first["departures"]!!.jsonArray.size)                // limit
        assertEquals("i-1", first["incidents"]!!.jsonArray.single().jsonObject.s("id"))
        assertEquals("true", first["incidents"]!!.jsonArray.single().jsonObject.s("atStation")) // C is an end of C-D

        // frequency 3600 s, but an incident change pushes a fresh screen right away
        upsert("i-train", IncidentScope(kind = "train", trainId = "t-to-c"))
        val after = next("screen_update")["data"]!!.jsonObject
        val inc = after["incidents"]!!.jsonArray.map { it.jsonObject }.first { it.s("id") == "i-train" }
        assertEquals("false", inc.s("atStation"))
        assertEquals(1, inc["trains"]!!.jsonPrimitive.int)

        db.incidents.remove("i-1")
        val removed = next("screen_update")["data"]!!.jsonObject
        assertEquals(listOf("i-train"), removed["incidents"]!!.jsonArray.map { it.jsonObject.s("id") })

        req("""{"op":"subscribe","topic":"screen","id":"C","limit":99}""")
        assertEquals("bad_limit", next("error").s("code"))
        req("""{"op":"subscribe","topic":"screen","id":"nope"}""")
        assertEquals("station_not_found", next("error").s("code"))
    }

    @Test
    fun errorsAndUnsubscribe() = ws {
        req("""{"op":"subscribe","topic":"train","id":"nope"}""")
        assertEquals("train_not_found", next("error").s("code"))
        req("""{"op":"subscribe","topic":"station","id":"B","frequencySec":0}""")
        assertEquals("bad_frequencySec", next("error").s("code"))
        req("""{"op":"subscribe","topic":"bus","id":"B"}""")
        assertEquals("bad_topic", next("error").s("code"))
        req("not json")
        assertEquals("bad_request", next("error").s("code"))

        req("""{"op":"subscribe","topic":"train","id":"t-other"}""")
        assertEquals(5, next("subscribed")["frequencySec"]!!.jsonPrimitive.int) // default
        req("""{"op":"unsubscribe","topic":"train","id":"t-other"}""")
        next("unsubscribed")
        req("""{"op":"ping"}""")
        next("pong")
    }
}
