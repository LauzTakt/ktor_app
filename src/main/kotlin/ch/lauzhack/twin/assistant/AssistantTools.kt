package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.ZURICH
import ch.lauzhack.twin.common.arr
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.query.IncidentQueries
import ch.lauzhack.twin.report.ReportService
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.StopDto
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TrainSummaryDto
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.model.Station
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.TwinException
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.buildJsonObject
import ch.lauzhack.twin.common.opt
import ch.lauzhack.twin.common.toPlain
import ch.lauzhack.twin.common.delayMin
import ch.lauzhack.twin.common.minutes
import ch.lauzhack.twin.common.cleanDashes
import ch.lauzhack.twin.query.Severity

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
    val unavailable: List<String> = emptyList(),
    val append: Boolean = false,
)

/** What one ask gathers while the agent works. Tool calls of one turn run in parallel, so this is thread-safe. */
class TurnState(val request: AskRequest, val now: Long) {
    internal val seq = AtomicInteger()
    internal val situation = ConcurrentHashMap<String, Pair<Int, SituationItem>>()
    internal val replanned = ConcurrentHashMap<String, String>()
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

/**
 * The assistant's tools. Data tools read ktor_app's own state ([TwinQueries], SQLite) and sbb-twin (planner, situation,
 * geometry); drawing tools become [VisualCommand]s with real geometry; `respond` records the final answer.
 */
class AssistantTools(
    internal val queries: TwinQueries,
    internal val incidents: IncidentQueries,
    internal val reports: ReportService,
    val stations: StationResolver,
    internal val twin: SbbTwinClient,
    val journeys: JourneyCache = JourneyCache(),
) {
    internal val log = LoggerFactory.getLogger(AssistantTools::class.java)

    // ================================================================ definitions (sent to the model; keep them stable for the prompt cache)

    val definitions: List<Tool> get() = TOOL_DEFINITIONS

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
                "bulletins" -> bulletins(input, turn)
                "show_route" -> showRoute(input, turn)
                "show_section" -> showSection(input)
                "highlight_trains" -> highlightTrains(input)
                "show_path" -> showPath(input)
                "label" -> label(input)
                "remove" -> ToolOutcome("ok: removed", visuals = listOf(RemoveVisual(input.str("id") ?: throw ToolError("id is required"))))
                "fit" -> fit(input)
                "track_train" -> trackTrain(input)
                "highlight_line" -> highlightLine(input)
                "arrivals" -> arrivals(input)
                "mark_stations" -> markStations(input)
                "start_timer" -> startTimer(input, turn)
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

    internal suspend fun station(raw: String?, param: String): Station {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolError("$param is required")
        stations.resolve(s)?.let { return it }
        twinSearch(s).firstOrNull()?.let { return it }
        throw ToolError("unknown station \"$s\" (try find_station)")
    }

    internal suspend fun twinSearch(q: String): List<Station> =
        runCatching { twin.stations(q).arr() }.getOrNull()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Station(id, o.str("name") ?: id, o.double("lat"), o.double("lon"))
        }.orEmpty()

    internal fun journeyJson(j: JsonObject, recommended: String?): JsonObject = buildJsonObject {
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
                add(buildJsonObject {
                    opt("line", l.str("line"))
                    opt("trainId", l.str("trainId"))
                    opt("headsign", l.str("headsign"))
                    opt("from", f?.str("name"))
                    opt("fromStopId", f?.str("id"))
                    opt("dep", hhmm(f?.long("expectedDep") ?: f?.long("plannedDep")))
                    opt("to", t?.str("name"))
                    opt("toStopId", t?.str("id"))
                    opt("arr", hhmm(t?.long("expectedArr") ?: t?.long("plannedArr")))
                    opt("delayMin", l.int("delaySec")?.let { minutes(it) }?.takeIf { it != 0 })
                    l.str("status")?.takeIf { it != "on time" }?.let { put("status", it) }
                    opt("stopsBetween", l.int("stopsBetween"))
                    val inc = l.objects("incidents")
                    if (inc.isNotEmpty()) put("incidents", buildJsonArray { inc.forEach { i -> add(buildJsonObject { opt("id", i.str("id")); opt("title", i.str("title")); opt("severity", i.str("severity")) }) } })
                })
            }
        })
    }

    /**
     * The SBB/VIA description of a measure when the incident carries it: cause, effect (interrupted / limited service),
     * expected end (`until`; `durationType` says whether it is an estimate or "at least until"), replacement buses, lines.
     */
    internal fun JsonObjectBuilder.measure(o: JsonObject?, now: Long) {
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

    internal fun untilText(t: Long?, now: Long): String? {
        if (t == null) return null
        val day = java.time.Instant.ofEpochMilli(t).atZone(ZURICH).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZURICH).toLocalDate()
        return if (day == today) hhmm(t) else java.time.Instant.ofEpochMilli(t).atZone(ZURICH)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ENGLISH))
    }

    internal fun stopJson(s: StopDto): JsonObject = buildJsonObject {
        put("stopId", s.stopId)
        put("name", s.name)
        opt("arr", hhmm(s.expectedArr ?: s.plannedArr))
        opt("dep", hhmm(s.expectedDep ?: s.plannedDep))
        val d = s.arrDelaySec ?: s.depDelaySec
        opt("delayMin", d?.let { minutes(it) }?.takeIf { it != 0 })
        if (s.skipped) put("passesWithoutStopping", true)
        if (s.state == StopState.current) put("standingHereNow", true)
    }

    internal fun describe(t: TrainSummaryDto, stops: List<StopDto>?): String {
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

    internal fun defaultRouteLabel(j: JsonObject): String {
        val legs = j.objects("legs")
        val line = legs.firstOrNull()?.str("line")
        val to = legs.lastOrNull()?.obj("to")?.str("name")
        val transfers = j.int("transfers") ?: (legs.size - 1).coerceAtLeast(0)
        return listOfNotNull(line, to?.let { "→ $it" }).joinToString(" ") +
            (hhmm(j.long("departure"))?.let { ", $it" } ?: "") +
            (if (transfers > 0) " ($transfers change${if (transfers > 1) "s" else ""})" else "")
    }

    internal fun ok(o: JsonObject) = ToolOutcome(o.toString())

    companion object {
        internal const val STOP = "7-digit stopId or station name."
        internal const val GEOMETRY_FRESH_MS = 3 * 60_000L

        // "from X to Y" in the four languages of the voice agent; names are checked against the station catalog.
        internal const val NAME = "([\\p{L}][\\p{L}0-9 .'/-]{1,60}?)"
        internal const val END = "(?=\\s*(?:[,.;:?!]|$|\\s(?:what|how|which|when|is|are|now|please|tonight|today|right|was|wie|welche|wann|ist|jetzt|bitte|quel|quelle|comment|est|maintenant|ce|qual|come|quando|adesso|ora)\\b))"
        internal val TRIP_PATTERNS = listOf(
            "\\bfrom\\s+$NAME\\s+to\\s+$NAME$END",
            "\\bbetween\\s+$NAME\\s+and\\s+$NAME$END",
            "\\bvon\\s+$NAME\\s+nach\\s+$NAME$END",
            "\\bzwischen\\s+$NAME\\s+und\\s+$NAME$END",
            "\\bentre\\s+$NAME\\s+et\\s+$NAME$END",
            "\\bde\\s+$NAME\\s+(?:à|a|pour|vers|jusqu'à)\\s+$NAME$END",
            "\\b(?:tra|fra)\\s+$NAME\\s+e\\s+$NAME$END",
            "\\bda\\s+$NAME\\s+(?:a|per|verso)\\s+$NAME$END",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

        // a lookahead, so that "to go to Bern" is also tried at "to Bern"
        internal val DESTINATION_PATTERNS = listOf(
            "(?=\\b(?:to|towards|nach|pour|vers|à|per|verso)\\s+$NAME$END)",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }
        // case-sensitive: "S3" yes, "it's 3" no
        val COLOURS = setOf("red", "orange", "green", "blue", "grey")
        val ROLES = listOf("origin", "destination", "transfer", "incident", "info")
        /** Major hubs used to explore alternative corridors. */
        internal val HUBS = listOf("8507000", "8500218", "8503000", "8501120", "8504300", "8505000", "8500010", "8504100")

        /** Incidents ingested from MQTT: the twin's automatic detector types them `auto.<rule>`. */
        fun IncidentDto.toItem() = SituationItem(id, title, where, Severity.parse(severity).id, if (type?.startsWith("auto") == true) "auto" else "staff")

        /** An incident or alert as the twin's planner/situation lists it (`{id, title, where?, severity, source}`). */
        fun twinItem(o: JsonObject): SituationItem {
            val source = when (o.str("source")) {
                "auto" -> "auto"
                "staff" -> "staff"
                null -> if (o.str("id")?.startsWith("sa-") == true) "sbb" else "staff"
                else -> "sbb"
            }
            val title = o.str("title") ?: ""
            return SituationItem(o.str("id") ?: "", title, o.str("where") ?: whereFromTitle(title, emptyList()).takeIf { source == "sbb" }, Severity.parse(o.str("severity")).id, source)
        }

        /** A real SBB alert; `where` from its title ("Limited service: Lausanne - Genève") or its stations. */
        fun alertItem(a: JsonObject): SituationItem {
            val title = a.str("title") ?: "SBB information"
            val names = a.objects("stations").mapNotNull { it.str("name") }
            return SituationItem(a.str("id") ?: "", title, whereFromTitle(title, names), Severity.parse(a.str("severity")).id, "sbb")
        }

        /** "Limited service: Lausanne - Genève" -> "Lausanne – Genève"; else the alert's stations, if any. */
        fun whereFromTitle(title: String, stationNames: List<String>): String? = when {
            ':' in title && title.substringAfter(':').isNotBlank() -> cleanDashes(title.substringAfter(':').trim())
            " - " in title -> {
                val (left, right) = title.split(" - ", limit = 2)
                val start = stationNames.filter { left.endsWith(it) }.maxByOrNull { it.length } ?: left.substringAfterLast(' ')
                "$start – $right"
            }
            stationNames.size == 1 -> stationNames[0]
            stationNames.size > 1 -> "${stationNames[0]} – ${stationNames[1]}"
            else -> null
        }

        internal fun JsonElement.toJsonValue(): JsonValue = JsonValue.from(toPlain())

        internal fun JsonObjectBuilder.prop(name: String, type: String, description: String, enum: List<String>? = null) {
            put(name, buildJsonObject {
                put("type", type)
                put("description", description)
                if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
            })
        }

        internal fun tool(name: String, description: String, required: List<String>, props: JsonObjectBuilder.() -> Unit): Tool {
            val properties = Tool.InputSchema.Properties.builder()
            buildJsonObject(props).forEach { (k, v) -> properties.putAdditionalProperty(k, v.toJsonValue()) }
            return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build()
        }
    }
}

