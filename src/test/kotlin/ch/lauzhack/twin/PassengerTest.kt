package ch.lauzhack.twin

import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.passenger.PassengerConfig
import ch.lauzhack.twin.passenger.Places
import ch.lauzhack.twin.passenger.Station
import ch.lauzhack.twin.state.LiveState
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassengerTest {
    /** sbb-twin (`/api/...`) and ElevenLabs (`/v1/convai/...`) on one local HTTP server. */
    private class FakeBackend : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<String>()
        var routes: (path: String, query: String) -> Pair<Int, String>? = { _, _ -> null }
        val url get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { ex ->
                val path = ex.requestURI.path
                val query = ex.requestURI.rawQuery ?: ""
                requests += "$path?$query"
                ex.requestHeaders["xi-api-key"]?.let { headers += it.first() }
                val (status, body) = routes(path, query) ?: (404 to """{"error":"not found"}""")
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private val now = System.currentTimeMillis()

    private val stationsGeoJson = """{"type":"FeatureCollection","features":[
        ${feature("8503000", "Zürich HB", 900, 8.5402, 47.3782)},
        ${feature("8503016", "Zürich Flughafen", 300, 8.5625, 47.4504)},
        ${feature("8507000", "Bern", 600, 7.4391, 46.9490)},
        ${feature("8500218", "Olten", 400, 7.9077, 47.3519)},
        ${feature("8503020", "Zürich Hardbrücke", 120, 8.5170, 47.3851)},
        ${feature("8507082", "Ins", 40, 7.1060, 46.9990)}
    ]}"""

    private fun feature(id: String, name: String, trains: Int, lon: Double, lat: Double) =
        """{"type":"Feature","properties":{"id":"$id","name":"$name","trains":$trains},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

    private val stations = listOf(
        Station("8503000", "Zürich HB", 900, 8.5402, 47.3782),
        Station("8503016", "Zürich Flughafen", 300, 8.5625, 47.4504),
        Station("8507000", "Bern", 600, 7.4391, 46.9490),
        Station("8500218", "Olten", 400, 7.9077, 47.3519),
        Station("8507082", "Ins", 40, 7.1060, 46.9990),
    )

    private fun withApp(backend: FakeBackend, cfg: PassengerConfig = PassengerConfig(twinUrl = backend.url), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { configureHttp(LiveState(), Database(createTempFile("twin", ".sqlite").toString()), passenger = cfg) { true } }
            block()
        }

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `places in free text - roles, aliases, longest match, stopwords`() {
        val a = Places.placesIn("I'm at Zürich Flughafen and need to go to Bern", stations)
        assertEquals(listOf("Zürich Flughafen" to "from", "Bern" to "to"), a.map { it.st.name to it.role })

        val b = Places.placesIn("from zurich to berne please", stations)
        assertEquals(listOf("Zürich HB" to "from", "Bern" to "to"), b.map { it.st.name to it.role })

        // "Ins" is a stopword ("ins Kino"); Olten without a preceding role word
        val c = Places.placesIn("Ich fahre ins Olten", stations)
        assertEquals(listOf("Olten" to null), c.map { it.st.name to it.role })
    }

    @Test
    fun `nearest prefers a much busier station that is barely further`() {
        val list = listOf(
            Station("1", "Small", 20, 8.5000, 47.3800),
            Station("2", "Big", 500, 8.5050, 47.3800), // ~0.38 km further
        )
        assertEquals("Big", Places.nearest(list, 47.38, 8.4990)?.first?.name)
        assertNull(Places.nearest(list.map { it.copy(trains = 5) }, 47.38, 8.5))
    }

    @Test
    fun `station brief from the twin's brief`() = FakeBackend().use { twin ->
        twin.routes = { path, query ->
            when (path) {
                "/api/stations.geojson" -> 200 to stationsGeoJson
                "/api/incidents", "/api/alerts", "/api/disruptions" -> 200 to "[]"
                "/api/brief" -> if ("station=8503000" in query) 200 to """{
                    "station":{"id":"8503000","name":"Zürich HB"},
                    "disruptions":[{"id":"d-1","kind":"control","title":"Interrupted - Zürich HB - Olten","description":"Between Zürich HB and Olten no trains run.\nThe cause is a technical fault.",
                       "effect":"interrupted","lifecycle":"INFO","cause":{"code":"TECHNICAL","label":"a technical fault"},"until":${now + 3_600_000},"durationType":"EXPECTED",
                       "lines":["IC1"],"messages":[{"ts":$now,"title":"Replacement bus","text":"Buses run every 15 minutes, calling at Lenzburg. Allow extra time."}],"replacementBus":{"route":[]}}],
                    "alerts":[],
                    "departures":[{"line":"IC1","headsign":"Bern","planned":$now,"delayMin":2}]}""" else null
                else -> null
            }
        }
        withApp(twin) {
            val r = client.get("/api/brief?station=Z%C3%BCrich%20HB")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = parse(r.bodyAsText())
            assertEquals("station:8503000:", b["key"]!!.jsonPrimitive.content)
            assertEquals("twin-brief", b["source"]!!.jsonPrimitive.content)
            assertTrue(b["related"]!!.jsonPrimitive.boolean)
            val d = b["disruptions"]!!.jsonArray.single().jsonObject
            assertEquals("operator", d["kind"]!!.jsonPrimitive.content)
            assertEquals("Interrupted – Zürich HB – Olten", d["title"]!!.jsonPrimitive.content)
            assertTrue(d["until"]!!.jsonPrimitive.content.startsWith("expected until "))
            val say = b["say"]!!.jsonPrimitive.content
            assertTrue(say.startsWith("Between Zürich HB and Olten no trains run. The cause is a technical fault. It is expected until"), say)
            assertTrue(say.endsWith("Buses run every 15 minutes."), say)
            val station = b["visuals"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "station" }
            assertEquals("incident", station["role"]!!.jsonPrimitive.content)
            assertEquals("2", b["departures"]!!.jsonArray.single().jsonObject["delayMin"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `route brief composed from plan, situation and geometry`() = FakeBackend().use { twin ->
        val dep = now + 10 * 60_000
        val arr = dep + 56 * 60_000
        twin.routes = { path, query ->
            when (path) {
                "/api/stations.geojson" -> 200 to stationsGeoJson
                "/api/incidents" -> 200 to """[{"id":"i-1","title":"Limited service - Zürich HB - Olten","severity":"major","source":"control","status":"active","effect":"limited",
                    "scope":{"kind":"section","from":"8503000","to":"8500218"},"geometry":{"type":"MultiLineString","coordinates":[[[8.54,47.37],[8.2,47.36],[7.9,47.35]]]}}]"""
                "/api/alerts", "/api/disruptions" -> 200 to "[]"
                "/api/plan" -> 200 to """{"journeys":[{"id":"j-1","departure":$dep,"arrival":$arr,"transfers":0,"arrivalDelayMin":4,"warnings":[],
                    "legs":[{"line":"IC1","category":"IC","headsign":"Bern","trainId":"t-1","status":"ok","incidents":[{"id":"i-1","title":"Limited service - Zürich HB - Olten","severity":"major","source":"control"}],
                      "from":{"id":"8503000","name":"Zürich HB","lon":8.54,"lat":47.378,"plannedDep":$dep},"to":{"id":"8507000","name":"Bern","lon":7.439,"lat":46.949,"plannedArr":$arr}}]}]}"""
                "/api/situation" -> 200 to """{"onTheWay":[]}"""
                "/api/geometry/journey/j-1" -> 200 to """{"legs":[{"trainId":"t-1","geometry":{"type":"LineString","coordinates":[[8.54,47.378],[7.9,47.35],[7.439,46.949]]}}]}"""
                else -> null // no /api/brief on this twin
            }.also { if (path == "/api/plan") assertTrue("from=8503000" in query && "to=8507000" in query, query) }
        }
        withApp(twin) {
            val r = client.get("/api/brief?q=from%20Z%C3%BCrich%20to%20Bern")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = parse(r.bodyAsText())
            assertEquals("route:8503000:8507000", b["key"]!!.jsonPrimitive.content)
            assertEquals("composed", b["source"]!!.jsonPrimitive.content)
            val d = b["disruptions"]!!.jsonArray.single().jsonObject
            assertTrue(d["onRoute"]!!.jsonPrimitive.boolean)
            assertTrue(b["related"]!!.jsonPrimitive.boolean)
            val j = b["journeys"]!!.jsonArray.single().jsonObject
            assertEquals(56, j["durationMin"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, j["lateMin"]!!.jsonPrimitive.content.toInt())
            val types = b["visuals"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertEquals(listOf("clear", "section", "route", "station", "station", "train", "timer", "focus"), types)
            val opt = b["options"]!!.jsonArray.single().jsonObject
            assertEquals("IC 1 to Bern", opt["label"]!!.jsonPrimitive.content)
            assertEquals("arrives first, about 4 min late", opt["why"]!!.jsonPrimitive.content)
            assertTrue(b["shown"]!!.jsonPrimitive.content.contains("the recommended route (IC 1 → Bern, "))
            assertTrue(b["say"]!!.jsonPrimitive.content.contains("The best connection now leaves at"))

            // cached 20 s: a second ask does not plan again
            val plans = twin.requests.count { it.startsWith("/api/plan") }
            client.get("/api/brief?from=8503000&to=8507000")
            assertEquals(plans, twin.requests.count { it.startsWith("/api/plan") })

            assertEquals(HttpStatusCode.NotFound, client.get("/api/brief?q=nothing%20here").status)

            val active = parse(client.get("/api/brief/active").bodyAsText())
            assertEquals(1, active["count"]!!.jsonPrimitive.content.toInt())
            assertTrue(active["text"]!!.jsonPrimitive.content.startsWith("- Limited service – Zürich HB – Olten"))
            val item = parse(client.get("/api/brief/item?id=i-1").bodyAsText())
            assertEquals("limited", item["effect"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/brief/item?id=nope").status)
        }
    }

    @Test
    fun `nearest station`() = FakeBackend().use { twin ->
        twin.routes = { path, _ -> if (path == "/api/stations.geojson") 200 to stationsGeoJson else null }
        withApp(twin) {
            val n = parse(client.get("/api/nearest?lat=47.379&lon=8.539").bodyAsText())
            assertEquals("8503000", n["id"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/nearest?lat=abc&lon=8.5").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/nearest").status)
        }
    }

    @Test
    fun `twin proxy is read-only and caches the big layers`() = FakeBackend().use { twin ->
        twin.routes = { path, query ->
            when (path) {
                "/api/stations.geojson" -> 200 to stationsGeoJson
                "/api/situation" -> 200 to """{"q":"$query"}"""
                else -> null
            }
        }
        withApp(twin) {
            assertEquals(HttpStatusCode.MethodNotAllowed, client.post("/twin/api/disruptions").status)
            assertTrue(twin.requests.none { it.startsWith("/api/disruptions") })

            val s = client.get("/twin/api/situation?station=8503000")
            assertEquals(HttpStatusCode.OK, s.status)
            assertEquals("station=8503000", parse(s.bodyAsText())["q"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.NotFound, client.get("/twin/api/unknown").status)

            repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/twin/api/stations.geojson").status) }
            assertEquals(1, twin.requests.count { it.startsWith("/api/stations.geojson") })
        }
        // twin down -> 502
        withApp(twin, PassengerConfig(twinUrl = "http://127.0.0.1:1")) {
            assertEquals(HttpStatusCode.BadGateway, client.get("/twin/api/status").status)
        }
    }

    @Test
    fun `voice session needs the ElevenLabs key and agent`() = FakeBackend().use { eleven ->
        eleven.routes = { path, query ->
            when {
                "agent_id=agent-1" !in query -> 400 to "{}"
                path == "/v1/convai/conversation/token" -> 200 to """{"token":"tok-123"}"""
                path == "/v1/convai/conversation/get-signed-url" -> 500 to "{}"
                else -> null
            }
        }
        withApp(eleven) {
            val r = client.get("/api/voice/session")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals("Voice is not configured on this server", parse(r.bodyAsText())["error"]!!.jsonPrimitive.content)
            assertEquals("false", parse(client.get("/api/config").bodyAsText())["voice"]!!.jsonPrimitive.content)
        }
        val cfg = PassengerConfig(twinUrl = eleven.url, elevenLabsKey = "sk-test", agentId = "agent-1", elevenLabsUrl = eleven.url)
        withApp(eleven, cfg) {
            val body = parse(client.get("/api/voice/session").bodyAsText())
            assertEquals("tok-123", body["conversationToken"]!!.jsonPrimitive.content)
            assertEquals("null", body["signedUrl"].toString())
            assertTrue(eleven.headers.all { it == "sk-test" } && eleven.headers.isNotEmpty())
            assertEquals("true", parse(client.get("/api/config").bodyAsText())["voice"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `twin down - 502 in the app's error shape, or our own stations when we have them`() = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t-1", aheadFrom = 0)) // stations A..D at 47.00/8.00 .. 47.03/8.03
        val down = PassengerConfig(twinUrl = "http://127.0.0.1:1")
        application { configureHttp(st, Database(createTempFile("twin", ".sqlite").toString()), passenger = down) { true } }
        val r = client.get("/api/nearest?lat=47.011&lon=8.011")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("B", parse(r.bodyAsText())["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `twin down and no stations of our own - 502 with an error message`() = testApplication {
        val down = PassengerConfig(twinUrl = "http://127.0.0.1:1")
        application { configureHttp(LiveState(), Database(createTempFile("twin", ".sqlite").toString()), passenger = down) { true } }
        val r = client.get("/api/nearest?lat=47.0&lon=8.0")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(parse(r.bodyAsText())["error"]!!.jsonPrimitive.content.startsWith("sbb-twin unreachable"), r.bodyAsText())
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/brief?station=8503000").status)
    }
}
