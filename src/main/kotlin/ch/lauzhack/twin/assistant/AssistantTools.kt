package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.api.v1.BoardType
import ch.lauzhack.twin.api.v1.IncidentDto
import ch.lauzhack.twin.api.v1.StopDto
import ch.lauzhack.twin.api.v1.StopState
import ch.lauzhack.twin.api.v1.TrainFilter
import ch.lauzhack.twin.api.v1.TrainSummaryDto
import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.db.StaffReportRow
import ch.lauzhack.twin.state.Station
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** What one tool call gives back: the text the model reads, and the map commands it produced. */
class ToolOutcome(val content: String, val isError: Boolean = false, val visuals: List<VisualCommand> = emptyList())

/** A tool failed in a way the model can act on (bad input, unknown id): returned to it as an error result. */
class ToolError(message: String) : RuntimeException(message)

data class RespondOption(val journeyId: String?, val label: String, val recommended: Boolean, val why: String?)

/** The final answer given through the `respond` tool. */
data class RespondInput(
    val answer: String,
    val summary: String?,
    val options: List<RespondOption>,
    val followUp: String?,
    /** null = not given (the backend picks), empty = nothing worth showing. */
    val situationIds: List<String>?,
)

/** What one ask gathers while the agent works. Tool calls of one turn run in parallel, so this is thread-safe. */
class TurnState(val request: AskRequest, val now: Long) {
    private val seq = AtomicInteger()
    private val situation = ConcurrentHashMap<String, Pair<Int, SituationItem>>()
    private val replanned = ConcurrentHashMap<String, String>()
    /** Departure times the tools showed (journeys, boards): the only times a countdown may point at. */
    val publishedDepartures: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val toolsCalled = ConcurrentLinkedQueue<String>()
    @Volatile var respond: RespondInput? = null

    /** Records an incident/alert seen by a tool; a later sighting fills in what an earlier one lacked (e.g. `where`). */
    fun note(item: SituationItem) {
        if (item.id.isEmpty()) return
        situation.compute(item.id) { _, old ->
            if (old == null) seq.incrementAndGet() to item
            else old.first to old.second.copy(where = old.second.where ?: item.where, title = old.second.title.ifBlank { item.title })
        }
    }
    fun situationItem(id: String): SituationItem? = situation[id]?.second
    fun situationItems(): List<SituationItem> = situation.values.sortedBy { it.first }.map { it.second }

    /** A journey re-planned under a new id (its geometry had expired): options follow it. */
    fun replaced(oldId: String, newId: String) { replanned[oldId] = newId }
    fun currentJourneyId(id: String): String = replanned[id] ?: id
}

/** How a journey was planned, so it can be planned again when the twin has dropped it. */
data class PlanQuery(val from: String, val to: String, val after: Long?, val limit: Int, val onboardTrainId: String?)

/** A planned journey as the twin returned it, with its validated (simplified) geometry per leg once known. */
data class CachedJourney(val journey: JsonObject, val query: PlanQuery, val geometry: List<JsonElement?>?, val at: Long) {
    val id: String get() = journey.str("id") ?: ""
}

/** Journeys from the planner, by id (the twin keeps them ~30 min): options and routes always use the planner's times. */
class JourneyCache(private val ttlMs: Long = 30 * 60_000L) {
    private val map = ConcurrentHashMap<String, CachedJourney>()

    fun put(entry: CachedJourney) {
        if (entry.id.isEmpty()) return
        map[entry.id] = entry
        if (map.size > 300) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now - it.value.at > ttlMs }
        }
    }

    fun entry(id: String): CachedJourney? = map[id]?.takeIf { System.currentTimeMillis() - it.at <= ttlMs }
    fun get(id: String): JsonObject? = entry(id)?.journey
}

/**
 * The assistant's tools. Data tools read ktor_app's own state ([TwinQueries], SQLite) and sbb-twin (planner, situation,
 * geometry); drawing tools become [VisualCommand]s with real geometry; `respond` records the final answer.
 */
class AssistantTools(
    private val queries: TwinQueries,
    private val db: Database,
    val stations: StationResolver,
    private val twin: SbbTwinClient,
    val journeys: JourneyCache = JourneyCache(),
) {
    private val log = LoggerFactory.getLogger(AssistantTools::class.java)

    // ================================================================ definitions (sent to the model; keep them stable for the prompt cache)

    val definitions: List<Tool> = listOf(
        tool(
            "find_station",
            "Find stations by name (any language, accents optional). Returns up to 5 matches, best first, with their stopId. " +
                "Every other tool already accepts a station name, so use this only when a name is ambiguous.",
            required = listOf("query"),
        ) { prop("query", "string", "Station name, e.g. \"Zürich HB\", \"Genf\", \"Lausanne\".") },
        tool(
            "station_board",
            "Next departures from a station, with live expected times, delays and cancellations.",
            required = listOf("stopId"),
        ) {
            prop("stopId", "string", STOP)
            prop("windowMin", "integer", "Minutes ahead (default 60, max 240).")
        },
        tool(
            "train_status",
            "Live status of one train: where it is now (standing at a station, running, or stopped on the open line), its delay, " +
                "its next stops with expected times, and the incidents affecting it. Give trainId; or give line (e.g. \"IC8\", \"S3\") " +
                "with destination and/or stopId to find the matching trains in service, nearest to the passenger first.",
            required = emptyList(),
        ) {
            prop("trainId", "string", "Train id from another tool.")
            prop("line", "string", "Line name, e.g. IC8, IR15, S3.")
            prop("destination", "string", "Station the train is heading to (name or stopId).")
            prop("stopId", "string", "A station the train will still call at (name or stopId).")
        },
        tool(
            "plan_journey",
            "Journey planner on the live network: today's timetable with live delays, cancellations, held trains and closed " +
                "sections; transfers of at least 4 minutes, at most 2. Returns up to 4 journeys sorted by arrival, each with a " +
                "journeyId, live departure and arrival, transfers, legs and warnings; the planner's own pick is marked " +
                "plannerRecommended. Use the journeyId in show_route, start_timer and the options of respond. An empty list " +
                "means no connection in the hours the planner covers.",
            required = listOf("fromStopId", "toStopId"),
        ) {
            prop("fromStopId", "string", STOP)
            prop("toStopId", "string", STOP)
            prop("after", "string", "Leave at or after this time, HH:MM Swiss time (default: now).")
            prop("limit", "integer", "Number of journeys, 1-6 (default 4).")
            prop("onboardTrainId", "string", "The train the passenger is on, if any (default: the app's trainId): staying aboard is then an option.")
        },
        tool(
            "situation",
            "What the passenger should know about a station, a train or a trip: active incidents (marked by railway staff or " +
                "detected automatically from train movements) and real SBB service alerts. Give stopId, or trainId, or fromStopId " +
                "and toStopId.",
            required = emptyList(),
        ) {
            prop("stopId", "string", STOP)
            prop("trainId", "string", "Train id.")
            prop("fromStopId", "string", "Trip origin: $STOP")
            prop("toStopId", "string", "Trip destination: $STOP")
        },
        tool(
            "show_route",
            "Draw a journey from plan_journey on the passenger's map (the real track geometry is added for you). The journeys " +
                "in respond's options are drawn automatically: use this only for another journey or a custom label.",
            required = listOf("journeyId", "style"),
        ) {
            prop("journeyId", "string", "A journeyId returned by plan_journey.")
            prop("style", "string", "recommended (the one you advise) or alternative.", enum = listOf("recommended", "alternative"))
            prop("label", "string", "Short map label in the passenger's language (default like \"IC8 → Bern, 18:42\").")
        },
        tool(
            "show_section",
            "Highlight the track between two stations on the map, e.g. a closed or disrupted section.",
            required = listOf("fromStopId", "toStopId", "colour", "label"),
        ) {
            prop("fromStopId", "string", STOP)
            prop("toStopId", "string", STOP)
            prop("colour", "string", "red = closed or interrupted, orange = disrupted or slow, green = fine, blue = information.",
                enum = listOf("red", "orange", "green", "blue"))
            prop("label", "string", "Short map label in the passenger's language, e.g. \"Closed: Olten – Aarau\".")
        },
        tool(
            "highlight_train",
            "Outline a live train on the map: the passenger's train, or the one to take.",
            required = listOf("trainId", "label"),
        ) {
            prop("trainId", "string", "Train id (only trains in service can be shown).")
            prop("label", "string", "Short label in the passenger's language, e.g. \"Your train\".")
        },
        tool(
            "mark_station",
            "Mark a station on the map.",
            required = listOf("stopId", "role"),
        ) {
            prop("stopId", "string", STOP)
            prop("role", "string", "Why it matters.", enum = listOf("origin", "destination", "transfer", "incident"))
            prop("label", "string", "Short label in the passenger's language (optional).")
        },
        tool(
            "start_timer",
            "Show a countdown chip on the passenger's screen, e.g. until the recommended train leaves.",
            required = listOf("label"),
        ) {
            prop("label", "string", "Short label in the passenger's language, e.g. \"IC 8 leaves in\".")
            prop("journeyId", "string", "Count down to this journey's live departure (preferred).")
            prop("until", "string", "Otherwise the time to count down to, HH:MM Swiss time, taken from a tool result.")
        },
        tool(
            "focus_on",
            "Zoom the map to fit these stations. Optional: by default the map fits everything you drew.",
            required = listOf("stopIds"),
        ) {
            put("stopIds", jsonOf {
                put("type", "array")
                put("description", "Stations to fit (stopIds or names).")
                putJsonObject("items") { put("type", "string") }
            })
        },
        tool(
            "respond",
            "Your final answer to the passenger. Call it exactly once, at the end; put the drawing tools in the same turn.",
            required = listOf("answer", "summary"),
        ) {
            prop("answer", "string",
                "Spoken by the voice agent: 1-3 short, calm sentences in the passenger's language, the key facts and the " +
                    "recommendation first. Plain text: no markdown, no lists, no ids.")
            prop("summary", "string", "One short line for the screen (at most about 60 characters), same language.")
            put("options", jsonOf {
                put("type", "array")
                put("description", "The journeys you compared, best first. Departure, arrival and transfers are filled in from the journeyId.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("journeyId") { put("type", "string"); put("description", "journeyId from plan_journey.") }
                        putJsonObject("label") { put("type", "string"); put("description", "e.g. \"IC 8 at 18:42, direct\", in the passenger's language.") }
                        putJsonObject("recommended") { put("type", "boolean") }
                        putJsonObject("why") { put("type", "string"); put("description", "A few words, e.g. \"arrives first\".") }
                    }
                    putJsonArray("required") { add("label"); add("recommended") }
                }
            })
            prop("followUp", "string", "One short question for the passenger, only if it really helps (otherwise leave it out).")
            put("situationIds", jsonOf {
                put("type", "array")
                put("description", "ids of the incidents and alerts (from the tools) that matter for this passenger, most important first.")
                putJsonObject("items") { put("type", "string") }
            })
        },
    )

    // ================================================================ execution

    suspend fun execute(name: String, input: JsonObject, turn: TurnState, logAs: String = name): ToolOutcome {
        turn.toolsCalled += logAs
        return try {
            when (name) {
                "find_station" -> findStation(input)
                "station_board" -> stationBoard(input, turn)
                "train_status" -> trainStatus(input, turn)
                "plan_journey" -> planJourney(input, turn)
                "situation" -> situation(input, turn)
                "show_route" -> showRoute(input, turn)
                "show_section" -> showSection(input)
                "highlight_train" -> highlightTrain(input)
                "mark_station" -> markStation(input)
                "start_timer" -> startTimer(input, turn)
                "focus_on" -> focusOn(input)
                "respond" -> respond(input, turn)
                else -> ToolOutcome("unknown tool '$name'", isError = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolError) {
            ToolOutcome(e.message ?: "error", isError = true)
        } catch (e: TwinException) {
            ToolOutcome(if (e.status == 404) "not found: ${e.message}" else "live data unavailable right now (${e.message})", isError = true)
        } catch (e: Exception) {
            log.warn("tool {} failed: {}", name, e.toString())
            ToolOutcome("tool failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    /**
     * Calls the tools the context makes predictable (journey from -> to, situation, the passenger's train) before the
     * first model call, so the model usually needs one turn less. Same output as the tools; failures are skipped.
     */
    suspend fun prefetch(turn: TurnState): List<Triple<String, JsonObject, ToolOutcome>> = coroutineScope {
        val c = turn.request.context ?: AskContext()
        var to = c.toStopId?.trim()?.takeIf { it.isNotEmpty() }
        val train = c.trainId?.trim()?.takeIf { it.isNotEmpty() }
        var from = c.fromStopId?.trim()?.takeIf { it.isNotEmpty() }
        if (from == null && train == null && c.lat != null && c.lon != null) from = stations.nearest(c.lat, c.lon, 1.5)?.first?.stopId
        // On board without an origin: plan from where the train is (or will be next), with the train as onboardTrainId.
        if (from == null && train != null && to != null) {
            from = runCatching { queries.trainSummary(train) }.getOrNull()?.let { it.currentStop?.stopId ?: it.nextStop?.stopId }
        }
        // No trip in the context: the stations named in the question ("from X to Y", "zwischen X und Y", ...).
        if (from == null && to == null && train == null) {
            guessTrip(turn.request.question)?.let { (a, b) -> from = a.stopId; to = b.stopId }
        } else if (from != null && to == null && train == null) {
            guessDestination(turn.request.question)?.takeIf { it.stopId != from }?.let { to = it.stopId }
        }
        val line = if (train == null) guessLine(turn.request.question) else null
        val calls = buildList {
            if (line != null) add("train_status" to jsonOf { put("line", line); to?.let { put("destination", it) } })
            if (from != null && to != null) {
                add("plan_journey" to jsonOf { put("fromStopId", from); put("toStopId", to) })
                add("situation" to jsonOf { put("fromStopId", from); put("toStopId", to) })
            }
            if (train != null) add("train_status" to jsonOf { put("trainId", train) })
            if (from != null && to == null && train == null) {
                add("situation" to jsonOf { put("stopId", from) })
                add("station_board" to jsonOf { put("stopId", from); put("windowMin", 30) })
            }
        }
        calls.map { (n, args) -> async { Triple(n, args, execute(n, args, turn, logAs = "pre:$n")) } }.awaitAll().filter { !it.third.isError }
    }

    /** Origin and destination named in the question, when both resolve with confidence to different stations. */
    fun guessTrip(question: String): Pair<Station, Station>? {
        for (re in TRIP_PATTERNS) {
            val m = re.find(question) ?: continue
            val a = stations.confident(m.groupValues[1]) ?: continue
            val b = stations.confident(m.groupValues[2]) ?: continue
            if (a.stopId != b.stopId) return a to b
        }
        // Otherwise exactly two stations named ("Ich bin in Bern und will nach Lausanne"): the word before each tells
        // which is the destination; by default the first one named is the origin.
        val named = stations.mentions(question)
        if (named.size != 2) return null
        val (first, second) = named
        val swap = first.second in TO_WORDS && second.second !in TO_WORDS || second.second in FROM_WORDS && first.second !in FROM_WORDS
        return if (swap) second.first to first.first else first.first to second.first
    }

    /** A destination named in the question ("to Bern", "nach Bern", "pour Genève", ...). */
    fun guessDestination(question: String): Station? =
        DESTINATION_PATTERNS.firstNotNullOfOrNull { re -> re.findAll(question).firstNotNullOfOrNull { stations.confident(it.groupValues[1]) } }

    /** A train line named in the question, e.g. "IC8", "IC 8", "S3" -> "IC8", "S3". */
    fun guessLine(question: String): String? =
        LINE_PATTERN.find(question)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[3] } + m.groupValues[2].ifEmpty { m.groupValues[4] } }

    /** Lines describing the app's context for the first message (names resolved). */
    fun describeContext(c: AskContext?): List<String> {
        if (c == null) return emptyList()
        fun st(id: String?) = id?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> stations.resolve(s)?.let { "${it.name} ($s)" } ?: s }
        return buildList {
            st(c.fromStopId)?.let { add("Origin: $it") }
            st(c.toStopId)?.let { add("Destination: $it") }
            c.trainId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                val t = runCatching { queries.trainSummary(id) }.getOrNull()
                add("The passenger's train: " + (t?.let { "${it.line ?: ""} ${it.number ?: ""} to ${it.destination?.name ?: it.headsign ?: "?"} ".replace(Regex("\\s+"), " ").trim() + " " } ?: "") + "(trainId $id)")
            }
            if (c.lat != null && c.lon != null) {
                val near = stations.nearest(c.lat, c.lon, 3.0)
                add("Passenger location: %.5f, %.5f".format(java.util.Locale.ROOT, c.lat, c.lon) +
                    (near?.let { (s, km) -> ", about ${(km * 1000).roundToInt()} m from ${s.name} (${s.stopId})" } ?: ""))
            }
        }
    }

    // ---------------------------------------------------------------- data tools

    private suspend fun findStation(input: JsonObject): ToolOutcome {
        val q = input.str("query") ?: throw ToolError("query is required")
        var hits = stations.search(q, 5)
        if (hits.isEmpty()) hits = twinSearch(q).take(5)
        return ok(jsonOf {
            put("matches", buildJsonArray { hits.forEach { add(jsonOf { put("stopId", it.stopId); put("name", it.name) }) } })
            if (hits.isEmpty()) put("note", "no station matches \"$q\"")
        })
    }

    private suspend fun stationBoard(input: JsonObject, turn: TurnState): ToolOutcome = coroutineScope {
        val st = station(input.str("stopId"), "stopId")
        val window = (input.int("windowMin") ?: 60).coerceIn(5, 240)
        val until = turn.now + window * 60_000L
        val remoteAsync = async { runCatching { twin.board(st.stopId).arr() }.getOrNull() }
        val local = runCatching { queries.board(st.stopId, BoardType.departures, window, 40, includeSkipped = true) }.getOrNull()
        val rows = ArrayList<Pair<Long, JsonObject>>()
        val seen = HashSet<String>()
        local?.entries?.forEach { e ->
            val t = e.time ?: return@forEach
            seen += e.trainId
            if (e.status != "cancelled") turn.publishedDepartures += t
            rows += t to jsonOf {
                put("time", hhmm(t))
                if (hhmm(e.plannedTime) != hhmm(t)) opt("planned", hhmm(e.plannedTime))
                opt("delayMin", delayMin(t, e.plannedTime)?.takeIf { it != 0 })
                opt("line", e.line)
                opt("to", e.destination?.name ?: e.headsign)
                put("trainId", e.trainId)
                if (e.status == "cancelled") put("cancelled", true)
                if (e.stop.skipped) put("passesWithoutStopping", true)
                if (e.atStation) put("standingThereNow", true)
            }
        }
        // The twin's board also lists trains that have not started their run yet (they have no trainId).
        val remote = remoteAsync.await()
        remote?.forEach { el ->
            val o = el.obj() ?: return@forEach
            val id = o.str("trainId")
            if (id != null && id in seen) return@forEach
            val t = o.long("expected") ?: o.long("planned") ?: return@forEach
            if (t > until) return@forEach
            if (o.bool("cancelled") != true) turn.publishedDepartures += t
            rows += t to jsonOf {
                put("time", hhmm(t))
                val p = o.long("planned")
                if (hhmm(p) != hhmm(t)) opt("planned", hhmm(p))
                opt("delayMin", delayMin(t, p)?.takeIf { it != 0 })
                opt("line", o.str("line"))
                opt("to", o.str("headsign"))
                opt("trainId", id)
                if (o.bool("cancelled") == true) put("cancelled", true)
            }
        }
        if (local == null && remote == null) throw ToolError("no departures known for ${st.name}")
        ok(jsonOf {
            put("station", jsonOf { put("stopId", st.stopId); put("name", st.name) })
            put("now", hhmm(turn.now))
            put("departures", buildJsonArray { rows.sortedBy { it.first }.take(16).forEach { add(it.second) } })
        })
    }

    private suspend fun trainStatus(input: JsonObject, turn: TurnState): ToolOutcome {
        input.str("trainId")?.let { return ok(trainDetail(it, turn)) }
        val line = input.str("line")?.replace(" ", "")?.uppercase() ?: throw ToolError("give trainId, or line (e.g. IC8)")
        val dest = input.str("destination")?.let { station(it, "destination") }
        val via = input.str("stopId")?.let { station(it, "stopId") }
        var found = queries.trains(TrainFilter(line = line, stationId = dest?.stopId), 2000, 0).items
        if (via != null) {
            val calling = queries.trainsUpcomingAt(via.stopId).mapTo(HashSet()) { it.trainId }
            found = found.filter { it.trainId in calling }
        }
        val ctx = turn.request.context
        fun dist(t: TrainSummaryDto): Double? {
            val p = t.position ?: return null
            if (ctx?.lat == null || ctx.lon == null) return null
            return StationResolver.distanceKm(ctx.lat, ctx.lon, p.lat, p.lon)
        }
        val sorted = found.sortedWith(
            compareBy<TrainSummaryDto>(
                { dist(it) ?: Double.MAX_VALUE },
                { if (it.position?.motion == "stopped" && it.currentStop == null) 0 else 1 },
                { -(it.delaySec ?: 0) },
            ),
        )
        if (sorted.isEmpty()) throw ToolError("no $line train in service" + (dest?.let { " towards ${it.name}" } ?: "") + (via?.let { " calling at ${it.name}" } ?: ""))
        val first = sorted.first()
        val firstDist = dist(first)
        if (sorted.size == 1 || (firstDist != null && firstDist < 1.0 && sorted.getOrNull(1)?.let { dist(it) }?.let { it > 3.0 } != false)) {
            return ok(trainDetail(first.trainId, turn))
        }
        // The only one stopped on the open line is most likely the passenger's "my train stopped": full detail for it.
        val stoppedOnLine = sorted.filter { it.position?.motion == "stopped" && it.currentStop == null }
        val detailed = stoppedOnLine.singleOrNull()?.let { runCatching { trainDetail(it.trainId, turn) }.getOrNull() }
        return ok(jsonOf {
            if (detailed != null) put("stoppedOnTheLine", detailed)
            put("candidates", buildJsonArray {
                sorted.filter { it.trainId != stoppedOnLine.singleOrNull()?.trainId || detailed == null }.take(6).forEach { t ->
                    add(candidateJson(t, dist(t), turn))
                }
            })
            put("hint", "Several $line trains are in service: pick the passenger's by direction, position or state; call train_status with its trainId for its stops, or ask the passenger.")
        })
    }

    /** A train in a candidates list: enough to answer without a second call (state, delay, next stop, arrival, incidents). */
    private fun candidateJson(t: TrainSummaryDto, distanceKm: Double?, turn: TurnState): JsonObject = jsonOf {
        put("trainId", t.trainId)
        opt("line", t.line)
        opt("number", t.number)
        opt("from", t.origin?.name)
        opt("to", t.destination?.name ?: t.headsign)
        put("now", describe(t, null))
        opt("delayMin", t.delaySec?.let { (it / 60.0).roundToInt() }?.takeIf { it != 0 })
        t.nextStop?.let { n -> put("next", jsonOf { put("name", n.name); opt("arr", hhmm(n.expectedArr ?: n.plannedArr)); opt("dep", hhmm(n.expectedDep ?: n.plannedDep)) }) }
        val stops = runCatching { queries.trainStops(t.trainId, null) }.getOrNull().orEmpty()
        stops.lastOrNull()?.takeIf { it.stopId != t.nextStop?.stopId }?.let { last -> opt("arrivesAtTerminus", hhmm(last.expectedArr ?: last.plannedArr)) }
        val incidents = runCatching { queries.trainIncidents(t.trainId) }.getOrNull().orEmpty()
        incidents.forEach { turn.note(it.toItem()) }
        if (incidents.isNotEmpty()) put("incidents", buildJsonArray { incidents.take(3).forEach { i -> add(jsonOf { put("id", i.id); put("title", i.title) }) } })
        opt("distanceKm", distanceKm?.let { (it * 10).roundToInt() / 10.0 })
    }

    private suspend fun trainDetail(trainId: String, turn: TurnState): JsonObject = coroutineScope {
        val sitAsync = async { runCatching { twin.situationOfTrain(trainId).obj() }.getOrNull() }
        val local = runCatching { queries.train(trainId) }.getOrNull()
        val remote = if (local == null) runCatching { twin.train(trainId).obj() }.getOrNull() else null
        val sit = sitAsync.await()
        if (local == null && remote == null) throw ToolError("train $trainId is not in service")
        val incidents = LinkedHashMap<String, JsonObject>()
        val out = jsonOf {
            if (local != null) {
                val t = local.train
                put("trainId", t.trainId)
                opt("line", t.line)
                opt("number", t.number)
                opt("from", t.origin?.name)
                opt("to", t.destination?.name ?: t.headsign)
                if (t.status == "cancelled") put("status", "cancelled")
                opt("delayMin", t.delaySec?.let { (it / 60.0).roundToInt() })
                put("now", describe(t, local.stops))
                opt("speedKmh", t.position?.speedKmh?.roundToInt())
                local.stops.lastOrNull { it.state == StopState.passed }?.let { p ->
                    put("lastStop", jsonOf { put("name", p.name); opt("left", hhmm(p.actualDep ?: p.expectedDep ?: p.plannedDep)) })
                }
                val ahead = local.stops.filter { it.state != StopState.passed }
                val shown = if (ahead.size > 12) ahead.take(11) + ahead.last() else ahead
                put("nextStops", buildJsonArray { shown.forEach { add(stopJson(it)) } })
                val raw = rawPayloads(local.incidents.map { it.id })
                local.incidents.forEach { inc ->
                    val item = inc.toItem()
                    turn.note(item)
                    incidents[inc.id] = jsonOf {
                        put("id", inc.id); put("title", inc.title); opt("where", inc.where); opt("severity", inc.severity)
                        put("source", item.source); opt("match", inc.match); measure(raw[inc.id], turn.now)
                    }
                }
            } else if (remote != null) {
                remoteTrain(remote, this)
            }
            sit?.obj("train")?.let { tr ->
                if (tr.str("mode") == "affected") put("affectedByDisruption", true)
                if (tr.bool("stopped") == true && tr.str("atStation") == null) put("stoppedOnTheLine", true)
            }
            sit?.objects("incidents")?.forEach { inc ->
                val id = inc.str("id") ?: return@forEach
                val item = twinItem(inc)
                turn.note(item)
                incidents.putIfAbsent(id, jsonOf {
                    put("id", id); put("title", item.title); opt("where", item.where); put("severity", item.severity)
                    put("source", item.source); measure(inc, turn.now)
                })
            }
            put("incidents", JsonArray(incidents.values.toList()))
        }
        out
    }

    /** Train detail from the twin's `/trains/{id}` (`{position, info}`; its `truth` part is staff-only and ignored). */
    private fun remoteTrain(o: JsonObject, b: JsonObjectBuilder) = with(b) {
        val info = o.obj("info")
        val pos = o.obj("position")
        put("trainId", info?.str("trainId") ?: pos?.str("trainId") ?: "")
        opt("line", info?.str("line") ?: pos?.str("line"))
        opt("number", info?.str("number"))
        opt("from", info?.obj("origin")?.str("name"))
        opt("to", info?.obj("destination")?.str("name") ?: info?.str("headsign"))
        if (info?.str("status") == "cancelled") put("status", "cancelled")
        opt("delayMin", pos?.int("delaySec")?.let { (it / 60.0).roundToInt() })
        val at = pos?.obj("atStop")?.str("name")
        val next = pos?.obj("nextStop")?.str("name")
        val prev = pos?.obj("prevStop")?.str("name")
        put("now", when {
            at != null -> "standing at $at"
            pos?.str("motion") == "stopped" -> "stopped on the open line" + (if (prev != null && next != null) " between $prev and $next" else "")
            next != null -> "running" + (if (prev != null) " between $prev and $next" else " towards $next")
            else -> "position unknown"
        })
        opt("speedKmh", pos?.double("speedKmh")?.roundToInt())
        val calls = info?.objects("calls").orEmpty()
        val firstAhead = calls.indexOfFirst { it.long("expectedArr") != null || it.long("expectedDep") != null }.takeIf { it >= 0 } ?: calls.size
        put("nextStops", buildJsonArray {
            calls.drop(firstAhead).take(12).forEach { c ->
                add(jsonOf {
                    opt("stopId", c.str("stopId"))
                    opt("name", c.str("name"))
                    opt("arr", hhmm(c.long("expectedArr") ?: c.long("plannedArr")))
                    opt("dep", hhmm(c.long("expectedDep") ?: c.long("plannedDep")))
                    opt("delayMin", (delayMin(c.long("expectedArr"), c.long("plannedArr")) ?: delayMin(c.long("expectedDep"), c.long("plannedDep")))?.takeIf { it != 0 })
                    if (c.bool("skipped") == true) put("passesWithoutStopping", true)
                })
            }
        })
    }

    private suspend fun planJourney(input: JsonObject, turn: TurnState): ToolOutcome {
        val a = station(input.str("fromStopId"), "fromStopId")
        val b = station(input.str("toStopId"), "toStopId")
        if (a.stopId == b.stopId) throw ToolError("origin and destination are the same station (${a.name})")
        val q = PlanQuery(
            from = a.stopId, to = b.stopId, after = parseTime(input.str("after"), turn.now),
            limit = (input.int("limit") ?: 4).coerceIn(1, 6),
            onboardTrainId = input.str("onboardTrainId") ?: turn.request.context?.trainId?.takeIf { it.isNotBlank() },
        )
        val (r, list) = planValidated(q)
        list.forEach { cj ->
            cj.journey.objects("legs").forEach { leg -> leg.objects("incidents").forEach { inc -> turn.note(twinItem(inc)) } }
            cj.journey.long("departure")?.let { turn.publishedDepartures += it }
        }
        val recommended = r.str("recommendedJourneyId")?.takeIf { id -> list.any { it.id == id } }
        return ok(jsonOf {
            put("from", jsonOf { put("stopId", r.obj("from")?.str("id") ?: a.stopId); put("name", r.obj("from")?.str("name") ?: a.name) })
            put("to", jsonOf { put("stopId", r.obj("to")?.str("id") ?: b.stopId); put("name", r.obj("to")?.str("name") ?: b.name) })
            opt("plannerRecommends", recommended)
            put("journeys", buildJsonArray { list.forEach { add(journeyJson(it.journey, recommended)) } })
            if (list.isEmpty()) put("note", "No connection found in the timetable the planner covers (the next hours).")
        })
    }

    /**
     * Plans and checks that every journey still has its geometry (the twin answers 404 for an expired or invalidated
     * journey); if one is missing, plans once more and keeps only journeys whose geometry exists.
     */
    private suspend fun planValidated(q: PlanQuery): Pair<JsonObject, List<CachedJourney>> {
        var attempt = 0
        while (true) {
            val r = twin.plan(q.from, q.to, q.after, q.limit, q.onboardTrainId).obj() ?: throw ToolError("the planner returned nothing")
            val list = r.objects("journeys")
            val geometries = coroutineScope { list.map { j -> async { journeyGeometry(j) } }.awaitAll() }
            if (geometries.any { it is Geo.Missing } && attempt++ == 0) {
                log.info("journey geometry missing right after planning {} -> {}: planning again", q.from, q.to)
                continue
            }
            val now = System.currentTimeMillis()
            val kept = list.zip(geometries).filter { it.second !is Geo.Missing }.map { (j, g) ->
                CachedJourney(j, q, (g as? Geo.Found)?.legs, now).also { journeys.put(it) }
            }
            return r to kept
        }
    }

    private sealed interface Geo {
        class Found(val legs: List<JsonElement?>) : Geo
        data object Missing : Geo
        data object Unknown : Geo
    }

    /** The track of each leg of a journey (simplified), from `/geometry/journey/{id}`; per-train fallback for a leg without one. */
    private suspend fun journeyGeometry(j: JsonObject): Geo = coroutineScope {
        val id = j.str("id") ?: return@coroutineScope Geo.Unknown
        val geoLegs = try {
            twin.journeyGeometry(id).obj()?.objects("legs").orEmpty()
        } catch (e: TwinException) {
            return@coroutineScope if (e.status == 404) Geo.Missing else Geo.Unknown
        }
        val legs = j.objects("legs").mapIndexed { i, leg ->
            async {
                val g = geoLegs.getOrNull(i)?.get("geometry")?.takeIf { it is JsonObject }
                    ?: leg.str("trainId")?.let { tid ->
                        runCatching { twin.trainGeometry(tid, leg.obj("from")?.str("id"), leg.obj("to")?.str("id")).obj()?.get("geometry") }.getOrNull()
                    }
                simplifyGeometry(g)
            }
        }.awaitAll()
        Geo.Found(legs)
    }

    /** Same journey after a re-plan: same trains on every leg. */
    private fun sameTrains(a: JsonObject, b: JsonObject) =
        a.objects("legs").map { it.str("trainId") ?: it.str("line") } == b.objects("legs").map { it.str("trainId") ?: it.str("line") }

    private suspend fun situation(input: JsonObject, turn: TurnState): ToolOutcome {
        val trainId = input.str("trainId")
        val from = input.str("fromStopId")
        val to = input.str("toStopId")
        val stop = input.str("stopId") ?: from?.takeIf { to == null } ?: to?.takeIf { from == null }
        val incidents = LinkedHashMap<String, JsonObject>()
        val alerts = LinkedHashMap<String, JsonObject>()
        // A twin failure must not hide what ktor_app knows itself (a 404 only means "nothing there").
        var twinFailed = false
        suspend fun twinOrNull(call: suspend () -> JsonElement): JsonObject? = try {
            call().obj()
        } catch (e: TwinException) {
            if (e.status != 404) twinFailed = true
            null
        }
        val out = jsonOf {
            fun addTwinStation(part: JsonObject?) {
                part?.objects("incidents")?.forEach { addIncident(twinItem(it), it.long("since"), it.strings("trainIds").size, it.str("description"), it, incidents, turn) }
                part?.objects("alerts")?.forEach { addAlert(it, alerts, turn) }
            }
            when {
                from != null && to != null -> {
                    val a = station(from, "fromStopId")
                    val b = station(to, "toStopId")
                    put("for", "trip ${a.name} → ${b.name}")
                    val r = twinOrNull { twin.situationOfTrip(a.stopId, b.stopId) }
                    addTwinStation(r?.obj("from"))
                    addTwinStation(r?.obj("to"))
                    localStation(a.stopId, incidents, turn)
                    localStation(b.stopId, incidents, turn)
                    reports { it.stopId in setOf(a.stopId, b.stopId) || it.toStopId in setOf(a.stopId, b.stopId) }.forEach { addReport(it, incidents, turn) }
                    opt("onTheWay", r?.strings("onTheWay"))
                }
                trainId != null -> {
                    put("for", "train $trainId")
                    val r = twinOrNull { twin.situationOfTrain(trainId) }
                    r?.obj("train")?.let { tr ->
                        put("train", jsonOf {
                            opt("line", tr.str("line"))
                            opt("to", tr.str("headsign"))
                            if (tr.str("mode") == "affected") put("affectedByDisruption", true)
                            if (tr.bool("stopped") == true) put("stopped", true)
                            opt("atStation", tr.str("atStation"))
                            tr.strings("between").takeIf { it.size == 2 }?.let { put("between", "${it[0]} – ${it[1]}") }
                        })
                    }
                    r?.objects("incidents")?.forEach { addIncident(twinItem(it), it.long("since"), null, it.str("description"), it, incidents, turn) }
                    runCatching { queries.trainIncidents(trainId) }.getOrNull()?.let { addLocal(it, incidents, turn) }
                    reports { it.trainId == trainId }.forEach { addReport(it, incidents, turn) }
                }
                stop != null -> {
                    val s = station(stop, "stopId")
                    put("for", "station ${s.name}")
                    addTwinStation(twinOrNull { twin.situationAtStation(s.stopId) })
                    localStation(s.stopId, incidents, turn)
                    reports { r ->
                        r.stopId == s.stopId || r.toStopId == s.stopId ||
                            (r.scopeKind == "point" && r.lat != null && r.lon != null && s.lat != null && s.lon != null &&
                                StationResolver.distanceKm(r.lat, r.lon, s.lat, s.lon) < 2.0)
                    }.forEach { addReport(it, incidents, turn) }
                }
                else -> throw ToolError("give stopId, or trainId, or fromStopId and toStopId")
            }
            put("incidents", JsonArray(incidents.values.toList()))
            put("alerts", JsonArray(alerts.values.toList()))
            if (twinFailed) put("note", "The live twin did not answer: SBB alerts and twin incidents may be missing.")
            else if (incidents.isEmpty() && alerts.isEmpty()) put("note", "No incidents or alerts known here.")
        }
        return ok(out)
    }


    private fun localStation(stopId: String, into: MutableMap<String, JsonObject>, turn: TurnState) {
        runCatching { queries.stationIncidents(stopId) }.getOrNull()?.let { addLocal(it, into, turn) }
    }

    private fun reports(filter: (StaffReportRow) -> Boolean): List<StaffReportRow> =
        runCatching { db.reports("open", limit = 200) }.getOrDefault(emptyList()).filter(filter)

    private fun addIncident(
        item: SituationItem, since: Long?, trains: Int?, description: String?, raw: JsonObject?,
        into: MutableMap<String, JsonObject>, turn: TurnState,
    ) {
        if (into.containsKey(item.id)) return
        turn.note(item)
        into[item.id] = jsonOf {
            put("id", item.id)
            put("title", item.title)
            opt("where", item.where)
            put("severity", item.severity)
            put("source", item.source)
            opt("since", hhmm(since))
            opt("trainsAffected", trains?.takeIf { it > 0 })
            measure(raw, turn.now)
            if (raw?.str("summary") == null) opt("description", description?.take(300))
        }
    }

    /** Raw MQTT payloads of ktor_app's incidents (they carry the cause / effect / until fields the table does not). */
    private fun rawPayloads(ids: Collection<String>): Map<String, JsonObject> =
        runCatching { db.latestIncidentPayloads(ids) }.getOrDefault(emptyMap())
            .mapNotNull { (id, json) -> runCatching { Json.parseToJsonElement(json).obj() }.getOrNull()?.let { id to it } }.toMap()

    private fun addLocal(list: List<IncidentDto>, into: MutableMap<String, JsonObject>, turn: TurnState) {
        val raw = rawPayloads(list.map { it.id }.filter { it !in into })
        list.forEach { addIncident(it.toItem(), it.updatedAt, null, it.description, raw[it.id], into, turn) }
    }

    private fun addReport(r: StaffReportRow, into: MutableMap<String, JsonObject>, turn: TurnState) =
        addIncident(SituationItem(r.id, r.title, r.where, r.severity, "staff"), r.createdAt, null, r.description, null, into, turn)

    private fun addAlert(a: JsonObject, into: MutableMap<String, JsonObject>, turn: TurnState) {
        val id = a.str("id") ?: return
        if (into.containsKey(id)) return
        val item = alertItem(a)
        turn.note(item)
        into[id] = jsonOf {
            put("id", id)
            put("title", item.title)
            put("severity", item.severity)
            opt("cause", a.str("cause"))
            opt("text", a.str("description")?.take(300))
        }
    }

    // ---------------------------------------------------------------- drawing tools

    private suspend fun showRoute(input: JsonObject, turn: TurnState): ToolOutcome {
        val id = input.str("journeyId") ?: throw ToolError("journeyId is required")
        val style = input.str("style")?.lowercase()?.takeIf { it == "alternative" } ?: "recommended"
        return ToolOutcome("ok: route drawn", visuals = listOf(drawJourney(id, style, input.str("label"), turn)))
    }

    /**
     * A planned journey as a map route. Geometry checked at planning time is reused for a few minutes; an older journey
     * is checked again, and if the twin has dropped it (404) it is planned once more and replaced by the same trains.
     */
    suspend fun drawJourney(journeyId: String, style: String, label: String?, turn: TurnState): RouteVisual {
        var cj = journeys.entry(turn.currentJourneyId(journeyId)) ?: throw ToolError("unknown journeyId $journeyId: use one returned by plan_journey")
        if (cj.geometry == null || System.currentTimeMillis() - cj.at > GEOMETRY_FRESH_MS) {
            when (val g = journeyGeometry(cj.journey)) {
                is Geo.Found -> cj = cj.copy(geometry = g.legs, at = System.currentTimeMillis()).also { journeys.put(it) }
                Geo.Unknown -> Unit
                Geo.Missing -> {
                    val old = cj
                    val (_, fresh) = planValidated(old.query)
                    cj = fresh.firstOrNull { sameTrains(it.journey, old.journey) }
                        ?: throw ToolError("journey $journeyId is no longer valid (the timetable changed): call plan_journey again")
                    turn.replaced(journeyId, cj.id)
                    cj.journey.long("departure")?.let { turn.publishedDepartures += it }
                }
            }
        }
        val legs = cj.journey.objects("legs").mapIndexed { i, leg ->
            RouteLeg(leg.str("line"), leg.str("trainId"), leg.obj("from")?.str("name"), leg.obj("to")?.str("name"), cj.geometry?.getOrNull(i))
        }
        return RouteVisual(cj.id, style, label ?: defaultRouteLabel(cj.journey), legs)
    }

    private suspend fun showSection(input: JsonObject): ToolOutcome {
        val a = station(input.str("fromStopId"), "fromStopId")
        val b = station(input.str("toStopId"), "toStopId")
        val colour = input.str("colour")?.lowercase()?.takeIf { it in COLOURS } ?: "red"
        val geometry = twin.sectionGeometry(a.stopId, b.stopId).obj()?.get("geometry")?.takeIf { it is JsonObject }
            ?: throw ToolError("no track known between ${a.name} and ${b.name}")
        val label = input.str("label") ?: "${a.name} – ${b.name}"
        return ToolOutcome("ok: section drawn", visuals = listOf(SectionVisual("sec-${a.stopId}-${b.stopId}", colour, label, simplifyGeometry(geometry))))
    }

    private suspend fun highlightTrain(input: JsonObject): ToolOutcome {
        val id = input.str("trainId") ?: throw ToolError("trainId is required")
        if (queries.trainState(id) == null && runCatching { twin.train(id) }.isFailure) throw ToolError("train $id is not in service")
        return ToolOutcome("ok: train highlighted", visuals = listOf(TrainVisual(id, input.str("label"))))
    }

    private suspend fun markStation(input: JsonObject): ToolOutcome {
        val st = station(input.str("stopId"), "stopId").let { s ->
            if (s.lat != null && s.lon != null) s else twinSearch(s.stopId).firstOrNull { it.stopId == s.stopId } ?: s
        }
        if (st.lat == null || st.lon == null) throw ToolError("no coordinates for station ${st.name}")
        val role = input.str("role")?.lowercase()?.takeIf { it in ROLES } ?: throw ToolError("role must be one of ${ROLES.joinToString()}")
        return ToolOutcome("ok: station marked", visuals = listOf(StationVisual(st.stopId, st.name, st.lon, st.lat, role, input.str("label"))))
    }

    private fun startTimer(input: JsonObject, turn: TurnState): ToolOutcome {
        val label = input.str("label") ?: throw ToolError("label is required")
        val until = input.str("journeyId")?.let { id ->
            journeys.get(turn.currentJourneyId(id))?.long("departure") ?: throw ToolError("unknown journeyId $id")
        } ?: parseTime(input.str("until"), turn.now)?.let { t ->
            // Only a published departure (a journey or board time shown by the tools) gets a countdown.
            turn.publishedDepartures.filter { kotlin.math.abs(it - t) < 60_000 }.minByOrNull { kotlin.math.abs(it - t) }
                ?: throw ToolError("${hhmm(t)} is not a departure from plan_journey or station_board: no countdown")
        } ?: throw ToolError("give journeyId, or until as HH:MM (Swiss time)")
        if (until < turn.now - 60_000 || until > turn.now + 24 * 3_600_000L) throw ToolError("the departure ${hhmm(until)} is not in the next 24 hours")
        return ToolOutcome("ok: countdown to ${hhmm(until)}", visuals = listOf(TimerVisual(label, until)))
    }

    private suspend fun focusOn(input: JsonObject): ToolOutcome {
        val ids = input.strings("stopIds").ifEmpty { throw ToolError("stopIds is required") }
        val box = BBoxBuilder()
        ids.forEach { s -> runCatching { station(s, "stopIds") }.getOrNull()?.let { box.add(it.lon, it.lat) } }
        val bbox = box.build() ?: throw ToolError("no coordinates for these stations")
        return ToolOutcome("ok: map focused", visuals = listOf(FocusVisual(bbox)))
    }

    private fun respond(input: JsonObject, turn: TurnState): ToolOutcome {
        val answer = input.str("answer") ?: throw ToolError("answer is required")
        val options = input.objects("options").map { o ->
            RespondOption(o.str("journeyId"), o.str("label") ?: "", o.bool("recommended") ?: false, o.str("why"))
        }
        val ids = if (input.containsKey("situationIds")) input.strings("situationIds") else null
        turn.respond = RespondInput(answer.trim(), input.str("summary"), options, input.str("followUp"), ids)
        return ToolOutcome("ok")
    }

    /** Origin, transfer and destination markers of a drawn journey, from the planner's legs. */
    fun routeMarkers(route: RouteVisual): List<StationVisual> {
        val legs = journeys.get(route.id)?.objects("legs").orEmpty()
        if (legs.isEmpty()) return emptyList()
        val out = ArrayList<StationVisual>()
        fun mark(p: JsonObject?, role: String) {
            val id = p?.str("id") ?: return
            if (out.none { it.stopId == id }) out += StationVisual(id, p.str("name") ?: id, p.double("lon"), p.double("lat"), role, null)
        }
        mark(legs.first().obj("from"), "origin")
        legs.dropLast(1).forEach { mark(it.obj("to"), "transfer") }
        mark(legs.last().obj("to"), "destination")
        return out
    }

    /** A map box fitting everything drawn (route and section geometry, stations, highlighted trains); null if nothing. */
    fun autoFocus(visuals: List<VisualCommand>): FocusVisual? {
        val box = BBoxBuilder()
        visuals.forEach { v ->
            when (v) {
                is RouteVisual -> v.legs.forEach { box.addGeometry(it.geometry) }
                is SectionVisual -> box.addGeometry(v.geometry)
                is StationVisual -> box.add(v.lon, v.lat)
                is TrainVisual -> queries.trainState(v.trainId)?.position?.let { box.add(it.lon, it.lat) }
                else -> Unit
            }
        }
        return box.build()?.let { FocusVisual(it) }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun station(raw: String?, param: String): Station {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolError("$param is required")
        stations.resolve(s)?.let { return it }
        twinSearch(s).firstOrNull()?.let { return it }
        throw ToolError("unknown station \"$s\" (try find_station)")
    }

    private suspend fun twinSearch(q: String): List<Station> =
        runCatching { twin.stations(q).arr() }.getOrNull()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Station(id, o.str("name") ?: id, o.double("lat"), o.double("lon"))
        }.orEmpty()

    private fun journeyJson(j: JsonObject, recommended: String?): JsonObject = jsonOf {
        put("journeyId", j.str("id") ?: "")
        if (recommended != null && j.str("id") == recommended) put("plannerRecommended", true)
        put("dep", hhmm(j.long("departure")))
        if (hhmm(j.long("plannedDeparture")) != hhmm(j.long("departure"))) opt("plannedDep", hhmm(j.long("plannedDeparture")))
        put("arr", hhmm(j.long("arrival")))
        if (hhmm(j.long("plannedArrival")) != hhmm(j.long("arrival"))) opt("plannedArr", hhmm(j.long("plannedArrival")))
        opt("durationMin", j.int("durationMin"))
        put("transfers", j.int("transfers") ?: 0)
        opt("arrivalDelayMin", j.int("arrivalDelayMin")?.takeIf { it != 0 })
        opt("warnings", j.strings("warnings"))
        put("legs", buildJsonArray {
            j.objects("legs").forEach { l ->
                val f = l.obj("from")
                val t = l.obj("to")
                add(jsonOf {
                    opt("line", l.str("line"))
                    opt("trainId", l.str("trainId"))
                    opt("headsign", l.str("headsign"))
                    opt("from", f?.str("name"))
                    opt("fromStopId", f?.str("id"))
                    opt("dep", hhmm(f?.long("expectedDep") ?: f?.long("plannedDep")))
                    opt("to", t?.str("name"))
                    opt("toStopId", t?.str("id"))
                    opt("arr", hhmm(t?.long("expectedArr") ?: t?.long("plannedArr")))
                    opt("delayMin", l.int("delaySec")?.let { (it / 60.0).roundToInt() }?.takeIf { it != 0 })
                    l.str("status")?.takeIf { it != "on time" }?.let { put("status", it) }
                    opt("stopsBetween", l.int("stopsBetween"))
                    val inc = l.objects("incidents")
                    if (inc.isNotEmpty()) put("incidents", buildJsonArray { inc.forEach { i -> add(jsonOf { opt("id", i.str("id")); opt("title", i.str("title")); opt("severity", i.str("severity")) }) } })
                })
            }
        })
    }

    /**
     * The SBB/VIA description of a measure when the incident carries it: cause, effect (interrupted / limited service),
     * expected end (`until`; `durationType` says whether it is an estimate or "at least until"), replacement buses, lines.
     */
    private fun JsonObjectBuilder.measure(o: JsonObject?, now: Long) {
        if (o == null) return
        opt("summary", o.str("summary")?.take(300))
        opt("cause", o.str("cause"))
        opt("effect", o.str("effect"))
        val until = o.long("until") ?: o.str("until")?.let { parseTime(it, now) }
        opt("until", untilText(until, now))
        opt("durationType", o.str("durationType"))
        when (val bus = o["replacementBus"]) {
            null, is kotlinx.serialization.json.JsonNull -> Unit
            is JsonPrimitive -> if (bus.content == "true") put("replacementBus", true) else opt("replacementBus", bus.content.takeIf { it != "false" })
            else -> if (bus.toString().length <= 600) put("replacementBus", bus)
        }
        opt("lines", o.strings("lines"))
    }

    private fun untilText(t: Long?, now: Long): String? {
        if (t == null) return null
        val day = java.time.Instant.ofEpochMilli(t).atZone(ZURICH).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZURICH).toLocalDate()
        return if (day == today) hhmm(t) else java.time.Instant.ofEpochMilli(t).atZone(ZURICH)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ENGLISH))
    }

    private fun stopJson(s: StopDto): JsonObject = jsonOf {
        put("stopId", s.stopId)
        put("name", s.name)
        opt("arr", hhmm(s.expectedArr ?: s.plannedArr))
        opt("dep", hhmm(s.expectedDep ?: s.plannedDep))
        val d = s.arrDelaySec ?: s.depDelaySec
        opt("delayMin", d?.let { (it / 60.0).roundToInt() }?.takeIf { it != 0 })
        if (s.skipped) put("passesWithoutStopping", true)
        if (s.state == StopState.current) put("standingHereNow", true)
    }

    private fun describe(t: TrainSummaryDto, stops: List<StopDto>?): String {
        val p = t.position
        val next = t.nextStop?.name
        val prev = stops?.lastOrNull { it.state == StopState.passed }?.name
        return when {
            t.currentStop != null -> "standing at ${t.currentStop.name}"
            p == null -> "position unknown (no live position yet)"
            p.motion == "stopped" -> "stopped on the open line" + when {
                prev != null && next != null -> " between $prev and $next"
                next != null -> " before $next"
                else -> ""
            }
            prev != null && next != null -> "running between $prev and $next"
            next != null -> "running towards $next"
            else -> "running"
        }
    }

    private fun defaultRouteLabel(j: JsonObject): String {
        val legs = j.objects("legs")
        val line = legs.firstOrNull()?.str("line")
        val to = legs.lastOrNull()?.obj("to")?.str("name")
        val transfers = j.int("transfers") ?: (legs.size - 1).coerceAtLeast(0)
        return listOfNotNull(line, to?.let { "→ $it" }).joinToString(" ") +
            (hhmm(j.long("departure"))?.let { ", $it" } ?: "") +
            (if (transfers > 0) " ($transfers change${if (transfers > 1) "s" else ""})" else "")
    }

    private fun ok(o: JsonObject) = ToolOutcome(o.toString())

    companion object {
        private const val STOP = "7-digit stopId or station name."
        private const val GEOMETRY_FRESH_MS = 3 * 60_000L

        // "from X to Y" in the four languages of the voice agent; names are checked against the station catalog.
        private const val NAME = "([\\p{L}][\\p{L}0-9 .'/-]{1,60}?)"
        private const val END = "(?=\\s*(?:[,.;:?!]|$|\\s(?:what|how|which|when|is|are|now|please|tonight|today|right|was|wie|welche|wann|ist|jetzt|bitte|quel|quelle|comment|est|maintenant|ce|qual|come|quando|adesso|ora)\\b))"
        private val TRIP_PATTERNS = listOf(
            "\\bfrom\\s+$NAME\\s+to\\s+$NAME$END",
            "\\bbetween\\s+$NAME\\s+and\\s+$NAME$END",
            "\\bvon\\s+$NAME\\s+nach\\s+$NAME$END",
            "\\bzwischen\\s+$NAME\\s+und\\s+$NAME$END",
            "\\bentre\\s+$NAME\\s+et\\s+$NAME$END",
            "\\bde\\s+$NAME\\s+(?:à|a|pour|vers|jusqu'à)\\s+$NAME$END",
            "\\b(?:tra|fra)\\s+$NAME\\s+e\\s+$NAME$END",
            "\\bda\\s+$NAME\\s+(?:a|per|verso)\\s+$NAME$END",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }
        private val TO_WORDS = setOf("to", "towards", "nach", "bis", "richtung", "pour", "vers", "per", "verso", "jusqu'à")
        private val FROM_WORDS = setOf("from", "von", "ab", "de", "depuis", "da", "dal", "dalla")

        // a lookahead, so that "to go to Bern" is also tried at "to Bern"
        private val DESTINATION_PATTERNS = listOf(
            "(?=\\b(?:to|towards|nach|pour|vers|à|per|verso)\\s+$NAME$END)",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }
        // case-sensitive: "S3" yes, "it's 3" no
        private val LINE_PATTERN = Regex("\\b(?:(IC|ICN|IR|RE|EC|ICE|TGV|RJX|PE)\\s?(\\d{1,3})|(S|R)(\\d{1,2}))\\b")
        val COLOURS = setOf("red", "orange", "green", "blue")
        val ROLES = listOf("origin", "destination", "transfer", "incident")
        private val SEVERITIES = listOf("info", "minor", "major", "critical")

        fun severityOf(s: String?) = s?.lowercase()?.takeIf { it in SEVERITIES } ?: "info"
        fun severityRank(s: String) = SEVERITIES.indexOf(s)

        /** Incidents ingested from MQTT: the twin's automatic detector types them `auto.<rule>`. */
        fun IncidentDto.toItem() = SituationItem(id, title, where, severityOf(severity), if (type?.startsWith("auto") == true) "auto" else "staff")

        /** An incident or alert as the twin's planner/situation lists it (`{id, title, where?, severity, source}`). */
        fun twinItem(o: JsonObject): SituationItem {
            val source = when (o.str("source")) {
                "auto" -> "auto"
                "staff" -> "staff"
                null -> if (o.str("id")?.startsWith("sa-") == true) "sbb" else "staff"
                else -> "sbb"
            }
            val title = o.str("title") ?: ""
            return SituationItem(o.str("id") ?: "", title, o.str("where") ?: whereFromTitle(title, emptyList()).takeIf { source == "sbb" }, severityOf(o.str("severity")), source)
        }

        /** A real SBB alert; `where` from its title ("Limited service: Lausanne - Genève") or its stations. */
        fun alertItem(a: JsonObject): SituationItem {
            val title = a.str("title") ?: "SBB information"
            val names = a.objects("stations").mapNotNull { it.str("name") }
            return SituationItem(a.str("id") ?: "", title, whereFromTitle(title, names), severityOf(a.str("severity")), "sbb")
        }

        /** "Limited service: Lausanne - Genève" -> "Lausanne – Genève"; else the alert's stations, if any. */
        fun whereFromTitle(title: String, stationNames: List<String>): String? = when {
            ':' in title && title.substringAfter(':').isNotBlank() -> title.substringAfter(':').trim().replace(" - ", " – ")
            " - " in title -> {
                val (left, right) = title.split(" - ", limit = 2)
                val start = stationNames.filter { left.endsWith(it) }.maxByOrNull { it.length } ?: left.substringAfterLast(' ')
                "$start – $right"
            }
            stationNames.size == 1 -> stationNames[0]
            stationNames.size > 1 -> "${stationNames[0]} – ${stationNames[1]}"
            else -> null
        }

        private fun JsonElement.toJsonValue(): JsonValue = JsonValue.from(toPlain())

        private fun JsonObjectBuilder.prop(name: String, type: String, description: String, enum: List<String>? = null) {
            put(name, jsonOf {
                put("type", type)
                put("description", description)
                if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
            })
        }

        private fun tool(name: String, description: String, required: List<String>, props: JsonObjectBuilder.() -> Unit): Tool {
            val properties = Tool.InputSchema.Properties.builder()
            jsonOf(props).forEach { (k, v) -> properties.putAdditionalProperty(k, v.toJsonValue()) }
            return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build()
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
