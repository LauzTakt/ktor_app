package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.T0
import ch.lauzhack.twin.awaitIncidents
import ch.lauzhack.twin.configureHttp
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.info
import ch.lauzhack.twin.model.Call
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.model.StopRef
import ch.lauzhack.twin.model.TrainInfo
import ch.lauzhack.twin.pos
import ch.lauzhack.twin.query.IncidentQueries
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.report.ReportService
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.testDb
import ch.lauzhack.twin.twinapi.SbbTwinClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssistantTest {
    /** One local HTTP server standing in for the Claude API (`/v1/messages`) and for sbb-twin (`/api/...`). */
    private class FakeBackends : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val claudeRequests = CopyOnWriteArrayList<JsonObject>()
        val claudeReplies = ConcurrentLinkedQueue<String>()
        val twinRequests = CopyOnWriteArrayList<String>()
        var twin: (path: String, query: String) -> String? = { _, _ -> null }
        val url get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { ex ->
                val path = ex.requestURI.path
                val (status, body) = if (path == "/v1/messages") {
                    claudeRequests += Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                    claudeReplies.poll()?.let { 200 to it } ?: (500 to """{"type":"error","error":{"type":"api_error","message":"no reply scripted"}}""")
                } else {
                    twinRequests += "$path?${ex.requestURI.rawQuery ?: ""}"
                    twin(path, ex.requestURI.query ?: "")?.let { 200 to it } ?: (404 to """{"error":"not found"}""")
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun seeded(): Pair<LiveState, Database> {
        val st = LiveState()
        st.upsertInfo(info("t-at-b", aheadFrom = 1))
        st.upsertInfo(info("t-to-c", aheadFrom = 2, delayMs = 120_000))
        st.replacePositions(mapOf("t-at-b" to pos("t-at-b", "B"), "t-to-c" to pos("t-to-c", null, delaySec = 120)), T0 + 1_200_000)
        val db = testDb()
        db.incidents.upsert(
            Incident(id = "i-1", title = "Fault C-D", severity = "critical", createdAt = 1, updatedAt = 1,
                scope = IncidentScope(kind = "section", from = "C", to = "D")),
            "{}",
        )
        db.awaitIncidents()
        return st to db
    }

    private fun claudeReply(id: String, vararg toolUses: Pair<String, String>) = """
        {"id":"$id","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"thinking","thinking":"","signature":"sig-$id"},
           ${toolUses.mapIndexed { i, (name, input) -> """{"type":"tool_use","id":"toolu_${id}_$i","name":"$name","input":$input,"caller":{"type":"direct"}}""" }.joinToString(",")}],
         "stop_reason":"tool_use","stop_sequence":null,
         "usage":{"input_tokens":100,"output_tokens":40,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"speed":"fast"}}
    """.trimIndent()

    @Test
    fun answersWithPlannerTimesAndRealGeometry() {
        val (st, db) = seeded()
        val dep = System.currentTimeMillis() + 10 * 60_000L
        val arr = dep + 30 * 60_000L
        FakeBackends().use { fake ->
            fake.twin = { path, query ->
                when (path) {
                    "/api/plan" -> """{"from":{"id":"A","name":"Station A","lon":8.0,"lat":47.0},"to":{"id":"D","name":"Station D","lon":8.03,"lat":47.03},
                        "generatedAt":$dep,"recommendedJourneyId":"j-test","journeys":[{"id":"j-test","departure":$dep,"arrival":$arr,
                        "plannedDeparture":$dep,"plannedArrival":${arr - 120_000},"durationMin":30,"transfers":0,"arrivalDelayMin":2,"warnings":["Fault C-D"],
                        "legs":[{"trainId":"t-to-c","line":"S1","category":"S","headsign":"D",
                          "from":{"id":"A","name":"Station A","lon":8.0,"lat":47.0,"plannedDep":$dep,"expectedDep":$dep},
                          "to":{"id":"D","name":"Station D","lon":8.03,"lat":47.03,"plannedArr":${arr - 120_000},"expectedArr":$arr},
                          "stopsBetween":2,"delaySec":120,"status":"late","incidents":[{"id":"i-1","title":"Fault C-D","severity":"critical","source":"staff"}]}]}]}"""
                    "/api/geometry/journey/j-test" -> """{"id":"j-test","legs":[{"trainId":"t-to-c","line":"S1",
                        "geometry":{"type":"LineString","coordinates":[[8.0,47.0],[8.01,47.01],[8.02,47.02],[8.03,47.03]]}}]}"""
                    "/api/situation" -> if ("trainId" in query) """{"train":{"trainId":"t-to-c","mode":"normal","stopped":false},"incidents":[]}"""
                        else """{"from":{"station":{"id":"A"},"incidents":[],"alerts":[],"departures":[]},"to":{"station":{"id":"D"},"incidents":[],"alerts":[],"departures":[]},"onTheWay":["Fault C-D"]}"""
                    "/api/stations/B/board" -> "[]"
                    else -> null
                }
            }
            // Turn 1: two independent data tools (run in parallel). Turn 2: a countdown and the final answer.
            fake.claudeReplies += claudeReply("m1", "train_status" to """{"trainId":"t-to-c"}""", "station_board" to """{"stopId":"B"}""")
            fake.claudeReplies += claudeReply(
                "m2",
                "start_timer" to """{"label":"S1 leaves in","journeyId":"j-test"}""",
                "respond" to """{"answer":"Take the S1 at 18:42; it is expected in Station D at 19:12.","summary":"S1 18:42 to D",
                    "options":[{"journeyId":"j-test","label":"S1 at 18:42, direct","recommended":true,"why":"arrives first"}],"situationIds":["i-1"]}""",
            )
            val cfg = AssistantConfig(twinUrl = fake.url)
            val llm = AnthropicLlm(AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(fake.url).maxRetries(0).build())

            testApplication {
                application { configureHttp(st, db, assistant = { q: TwinQueries -> buildAssistant(cfg, llm, q, st, IncidentQueries(db.incidents), ReportService(db.reports, st)) }) { true } }
                val res = client.post("/api/v1/assistant/ask") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"question":"How do I get to D?","context":{"fromStopId":"A","toStopId":"D","extra":1},"sessionId":"s1"}""")
                }
                assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
                val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject

                // --- what was sent to Claude
                assertEquals(2, fake.claudeRequests.size)
                val first = fake.claudeRequests[0]
                assertEquals("claude-sonnet-5-5", first["model"]!!.jsonPrimitive.content)
                assertEquals(null, first["speed"])
                assertEquals("low", first["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertEquals("1h", first["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
                assertEquals(
                    setOf("find_station", "station_board", "train_status", "plan_journey", "situation", "bulletins", "arrivals", "show_route", "show_section",
                        "highlight_trains", "track_train", "highlight_line", "mark_stations", "show_path", "label", "remove", "start_timer", "fit", "respond"),
                    first["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet(),
                )
                val firstText = first["messages"]!!.jsonArray[0].jsonObject["content"].toString()
                assertTrue("j-test" in firstText && "plannerRecommended" in firstText, "trip prefetched into the first message: $firstText")

                val second = fake.claudeRequests[1]["messages"]!!.jsonArray
                assertEquals(listOf("user", "assistant", "user"), second.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                val assistantTurn = second[1].jsonObject["content"]!!.jsonArray
                assertEquals("thinking", assistantTurn[0].jsonObject["type"]!!.jsonPrimitive.content) // passed back unchanged
                val results = second[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("toolu_m1_0", "toolu_m1_1"), results.map { it["tool_use_id"]!!.jsonPrimitive.content }) // one message
                assertTrue("t-to-c" in results[0]["content"].toString() && "Fault C-D" in results[0]["content"].toString())
                assertTrue("Station B" in results[1]["content"].toString())

                // --- the answer: times from the planner, real geometry, the backend's markers and focus
                assertEquals("Take the S1 at 18:42; it is expected in Station D at 19:12.", body["answer"]!!.jsonPrimitive.content)
                val option = body["options"]!!.jsonArray.single().jsonObject
                assertEquals("A", option["id"]!!.jsonPrimitive.content)
                assertEquals(dep, option["departure"]!!.jsonPrimitive.long)
                assertEquals(arr, option["arrival"]!!.jsonPrimitive.long)
                assertEquals(0, option["transfers"]!!.jsonPrimitive.int)
                assertTrue(option["recommended"]!!.jsonPrimitive.boolean)
                val situation = body["situation"]!!.jsonArray.single().jsonObject
                assertEquals(listOf("i-1", "critical", "staff"), listOf("id", "severity", "source").map { situation[it]!!.jsonPrimitive.content })

                val visuals = body["visuals"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("clear", "route", "timer", "station", "station", "focus"), visuals.map { it["type"]!!.jsonPrimitive.content })
                val route = visuals[1]
                assertEquals("j-test", route["id"]!!.jsonPrimitive.content)
                assertEquals("recommended", route["style"]!!.jsonPrimitive.content)
                val geometry = route["legs"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject
                assertEquals("LineString", geometry["type"]!!.jsonPrimitive.content)
                assertEquals(dep, visuals[2]["until"]!!.jsonPrimitive.long)
                assertEquals(listOf("origin", "destination"), visuals.filter { it["type"]!!.jsonPrimitive.content == "station" }.map { it["role"]!!.jsonPrimitive.content })
                assertEquals(4, visuals.last()["bbox"]!!.jsonArray.size)
                assertNotNull(body["elapsedMs"]!!.jsonPrimitive.long)
                assertTrue(fake.twinRequests.none { it.startsWith("/api/control") }, "read-only use of the twin")
            }
        }
    }

    @Test
    fun stationNamesAndQuestionHints() {
        val st = LiveState()
        val names = listOf("8503000" to "Zürich HB", "8503006" to "Zürich Oerlikon", "8507000" to "Bern", "8501120" to "Lausanne",
            "8501008" to "Genève", "8501026" to "Genève-Aéroport")
        fun run(id: String, stops: List<Pair<String, String>>) = st.upsertInfo(
            TrainInfo(trainId = id, line = "IC8", origin = StopRef(stops.first().first, stops.first().second),
                destination = StopRef(stops.last().first, stops.last().second),
                calls = stops.map { (s, n) -> Call(stopId = s, name = n, lat = 47.0, lon = 8.0, expectedDep = T0) }),
        )
        run("t1", names.take(3)); run("t2", listOf(names[0], names[2])); run("t3", names.drop(3))
        val resolver = StationResolver(st)
        assertEquals("8503000", resolver.search("zurich")[0].stopId)            // busiest "Zürich …" first
        assertEquals("8501008", resolver.resolve("Genf")!!.stopId)              // other-language name
        assertEquals("8507000", resolver.resolve("Bern Hauptbahnhof")!!.stopId) // filler words
        val db = testDb()
        val tools = AssistantTools(TwinQueries(st) { emptyList() }, IncidentQueries(db.incidents), ReportService(db.reports, st), resolver, SbbTwinClient("http://127.0.0.1:9"))
        assertEquals("8503000" to "8507000", tools.guessTrip("my train from Zürich HB to Bern, what's the best way now?")!!.let { it.first.stopId to it.second.stopId })
        assertEquals("8501120" to "8501008", tools.guessTrip("is there any problem between Lausanne and Genève?")!!.let { it.first.stopId to it.second.stopId })
        assertEquals("8501008", tools.guessDestination("I want to go to Geneva please")!!.stopId)
        assertEquals("8507000" to "8501120", tools.guessTrip("Ich bin in Bern und will nach Lausanne. Gibt es Probleme?")!!.let { it.first.stopId to it.second.stopId })
        assertEquals("8501120" to "8501008", tools.guessTrip("Je dois aller vers Genève depuis Lausanne")!!.let { it.first.stopId to it.second.stopId })
        assertEquals(null, tools.guessTrip("The IC 8 I'm on stopped, what's happening?"))
        assertEquals("IC8", tools.guessLine("the IC 8 I'm on stopped, what's happening?"))
        assertEquals(null, tools.guessLine("it's 3 minutes late"))

        // What is open on the passenger's screen goes into the first message, with names resolved.
        val train = tools.describeScreen(ScreenView(screen = "train", trainId = "t1"))!!
        assertTrue("IC8" in train && "Bern" in train && "t1" in train, train)
        assertEquals("the status page of line IC8 (its incidents and trains in service)", tools.describeScreen(ScreenView(screen = "line", line = "ic 8")))
        assertEquals("the live trains from Lausanne (8501120) to Genève (8501008)",
            tools.describeScreen(ScreenView(screen = "connections", fromStopId = "8501120", toStopId = "8501008")))
        assertEquals(null, tools.describeScreen(ScreenView(screen = "alerts")))
        val ctx = tools.describeContext(AskContext(onScreen = ScreenView(screen = "station", stopId = "8507000")))
        assertTrue(ctx.single().startsWith("Open on the passenger's screen right now: the departure board of Bern (8507000)"), ctx.toString())
    }
}
