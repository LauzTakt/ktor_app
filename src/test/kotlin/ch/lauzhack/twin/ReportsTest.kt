package ch.lauzhack.twin

import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsTest {
    private fun app(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-1", aheadFrom = 1, line = "IC1"))
        st.replacePositions(mapOf("t-1" to pos("t-1", null, delaySec = 240)), T0)
        val db = Database(createTempFile("twin", ".sqlite").toString())
        application { configureHttp(st, db) { true } }
        test()
    }

    private suspend fun ApplicationTestBuilder.post(url: String, body: String): HttpResponse =
        client.post(url) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.body(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun JsonElement.s(k: String) = jsonObject[k]!!.jsonPrimitive.content

    @Test
    fun createListAndResolve() = app {
        val r = post("/api/v1/reports", """{"title":"Signal dark","category":"signalling","severity":"major","scope":"section","stopId":"B","toStopId":"C","reporter":"Driver"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val rep = r.body()
        assertEquals("Station B – Station C", rep.s("where"))
        assertEquals(47.015, rep.jsonObject["lat"]!!.jsonPrimitive.double, 1e-9) // section midpoint
        val id = rep.s("id")

        val t = post("/api/v1/reports", """{"title":"Door fault","scope":"train","trainId":"t-1"}""").body()
        assertEquals("IC1", t.s("line"))
        assertEquals("IC1 t-1 → D", t.s("where"))

        assertEquals(2, client.get("/api/v1/reports").body().jsonArray.size)

        val res = post("/api/v1/reports/$id/resolve", """{"resolution":"Fixed by technician"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("resolved", res.body().s("status"))
        assertEquals(HttpStatusCode.Conflict, post("/api/v1/reports/$id/resolve", "{}").status)

        assertEquals(1, client.get("/api/v1/reports").body().jsonArray.size)
        assertEquals(1, client.get("/api/v1/reports?status=resolved").body().jsonArray.size)
        assertEquals(2, client.get("/api/v1/reports?status=all").body().jsonArray.size)
    }

    @Test
    fun validation() = app {
        suspend fun code(body: String, status: HttpStatusCode = HttpStatusCode.BadRequest): String {
            val r = post("/api/v1/reports", body)
            assertEquals(status, r.status, r.bodyAsText())
            return r.body().jsonObject["error"]!!.s("code")
        }
        assertEquals("bad_title", code("""{"title":"  ","scope":"station","stopId":"B"}"""))
        assertEquals("bad_scope", code("""{"title":"x","scope":"planet"}"""))
        assertEquals("bad_category", code("""{"title":"x","scope":"station","stopId":"B","category":"aliens"}"""))
        assertEquals("bad_severity", code("""{"title":"x","scope":"station","stopId":"B","severity":"huge"}"""))
        assertEquals("bad_stopId", code("""{"title":"x","scope":"station"}"""))
        assertEquals("station_not_found", code("""{"title":"x","scope":"station","stopId":"nope"}""", HttpStatusCode.NotFound))
        assertEquals("train_not_found", code("""{"title":"x","scope":"train","trainId":"nope"}""", HttpStatusCode.NotFound))
        assertEquals("bad_lat", code("""{"title":"x","scope":"point","lat":99,"lon":8}"""))
        assertEquals("bad_request", code("""{"scope":"point"}""")) // missing title
        assertEquals("bad_status", client.get("/api/v1/reports?status=x").body().jsonObject["error"]!!.s("code"))
    }

    @Test
    fun staticPagesAndFleetSocket() = app {
        assertTrue(client.get("/").bodyAsText().contains("Staff overview"))
        assertTrue(client.get("/report.html").bodyAsText().contains("Report an incident"))

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/fleet") {
            val tick = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("tick", tick["type"]!!.jsonPrimitive.content)
            val row = tick["trains"]!!.jsonArray.single().jsonArray
            assertEquals("t-1", row[0].jsonPrimitive.content)
            assertEquals(240, row[6].jsonPrimitive.int)
        }
    }
}
