package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.assistant.AssistantTools.Companion.COLOURS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.GEOMETRY_FRESH_MS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.ROLES
import ch.lauzhack.twin.common.BBoxBuilder
import ch.lauzhack.twin.common.arr
import ch.lauzhack.twin.common.bool
import ch.lauzhack.twin.common.delayMin
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.minutes
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.opt
import ch.lauzhack.twin.common.simplifyGeometry
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.query.TrainFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* Drawing tools: they become map VisualCommands with real geometry. Part of [AssistantTools]. */

internal suspend fun AssistantTools.showRoute(input: JsonObject, turn: TurnState): ToolOutcome {
    val id = input.str("journeyId") ?: throw ToolError("journeyId is required")
    val style = input.str("style")?.lowercase()?.takeIf { it == "alternative" } ?: "recommended"
    return ToolOutcome("ok: route drawn", visuals = listOf(drawJourney(id, style, input.str("label"), turn)))
}

/**
 * A planned journey as a map route. Geometry checked at planning time is reused for a few minutes; an older journey
 * is checked again, and if the twin has dropped it (404) it is planned once more and replaced by the same trains.
 */
suspend fun AssistantTools.drawJourney(journeyId: String, style: String, label: String?, turn: TurnState): RouteVisual {
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

internal suspend fun AssistantTools.showSection(input: JsonObject): ToolOutcome {
    val a = station(input.str("fromStopId"), "fromStopId")
    val b = station(input.str("toStopId"), "toStopId")
    val colour = input.str("colour")?.lowercase()?.takeIf { it in COLOURS } ?: "red"
    val geometry = twin.sectionGeometry(a.stopId, b.stopId).obj()?.get("geometry")?.takeIf { it is JsonObject }
        ?: throw ToolError("no track known between ${a.name} and ${b.name}")
    val label = input.str("label") ?: "${a.name} – ${b.name}"
    return ToolOutcome("ok: section drawn", visuals = listOf(SectionVisual("sec-${a.stopId}-${b.stopId}", colour, label, simplifyGeometry(geometry))))
}

internal suspend fun AssistantTools.trackTrain(input: JsonObject): ToolOutcome {
    val id = input.str("trainId") ?: throw ToolError("trainId is required")
    val st = station(input.str("stopId"), "stopId")
    return ToolOutcome("ok: tracking $id to ${st.name}", visuals = listOf(TrackTrainVisual(id, st.stopId, input.str("label"))))
}

internal suspend fun AssistantTools.highlightLine(input: JsonObject): ToolOutcome {
    val line = input.str("line")?.let(::lineKey) ?: throw ToolError("line is required")
    val visuals = mutableListOf<VisualCommand>(LineVisual(line, input.str("label")))
    var note = ""
    if (input.bool("route") == true) {
        // The path of the line's longest run in service.
        val trains = queries.trains(TrainFilter(line = line), 200, 0).items
        val best = trains.maxByOrNull { runCatching { queries.trainStops(it.trainId, null).size }.getOrDefault(0) }
        val geo = best?.let { t -> runCatching { twin.trainGeometry(t.trainId, null, null).obj()?.get("geometry") }.getOrNull() }
        if (best != null && geo is JsonObject) {
            val stops = runCatching { queries.trainStops(best.trainId, null) }.getOrDefault(emptyList())
            val a = stops.firstOrNull(); val b = stops.lastOrNull()
            visuals += SectionVisual("path:${a?.stopId}-${b?.stopId}", "blue", "$line ${a?.name ?: ""} – ${b?.name ?: ""}".trim(), simplifyGeometry(geo))
            note = "; route drawn ${a?.name} – ${b?.name} (" + stops.joinToString(", ") { it.name }.take(400) + ")"
        } else note = "; no train of $line in service, route not drawn"
    }
    return ToolOutcome("ok: line $line highlighted$note", visuals = visuals)
}

internal suspend fun AssistantTools.arrivals(input: JsonObject): ToolOutcome {
    val st = station(input.str("stopId"), "stopId")
    val list = twin.arrivals(st.stopId).arr()?.mapNotNull { it.obj() }.orEmpty().take(12)
    return ok(buildJsonObject {
        put("station", buildJsonObject { put("stopId", st.stopId); put("name", st.name) })
        put("arrivals", buildJsonArray {
            list.forEach { a ->
                add(buildJsonObject {
                    opt("trainId", a.str("trainId"))
                    opt("line", a.str("line"))
                    opt("headsign", a.str("headsign"))
                    opt("expectedArr", hhmm(a.long("expectedArr") ?: a.long("plannedArr")))
                    opt("etaMin", a.long("etaSec")?.let { minutes(it) })
                    opt("delayMin", a.long("delaySec")?.let { minutes(it) }?.takeIf { it != 0 })
                    opt("stopsAway", a.int("stopsAway"))
                    opt("status", a.str("status"))
                })
            }
        })
        if (list.isEmpty()) put("note", "No trains arriving soon.")
    })
}

internal suspend fun AssistantTools.highlightTrains(input: JsonObject): ToolOutcome = coroutineScope {
    val items = input.objects("trains").ifEmpty { throw ToolError("trains is required") }.take(20)
    val checked = items.map { o ->
        async {
            val id = o.str("trainId") ?: return@async null
            if (queries.trainState(id) == null && runCatching { twin.train(id) }.isFailure) null else TrainVisual(id, o.str("label"))
        }
    }.awaitAll()
    val ok = checked.filterNotNull()
    val missing = items.mapNotNull { it.str("trainId") }.filter { id -> ok.none { it.trainId == id } }
    if (ok.isEmpty()) throw ToolError("none of these trains is in service")
    ToolOutcome("ok: ${ok.size} train(s) highlighted" + if (missing.isEmpty()) "" else "; not in service: ${missing.joinToString()}", visuals = ok)
}

internal suspend fun AssistantTools.markStations(input: JsonObject): ToolOutcome {
    val out = ArrayList<VisualCommand>()
    val errors = ArrayList<String>()
    input.objects("stations").ifEmpty { throw ToolError("stations is required") }.take(30).forEach { o ->
        runCatching { markStation(o).visuals }.onSuccess { out += it }.onFailure { errors += (it.message ?: "error") }
    }
    if (out.isEmpty()) throw ToolError(errors.joinToString("; "))
    return ToolOutcome("ok: ${out.size} station(s) marked" + if (errors.isEmpty()) "" else "; ${errors.joinToString("; ")}", visuals = out)
}

internal suspend fun AssistantTools.showPath(input: JsonObject): ToolOutcome = coroutineScope {
    val stops = input.strings("stopIds").map { station(it, "stopIds") }.distinctBy { it.stopId }
    if (stops.size < 2) throw ToolError("give at least 2 stations")
    val parts = stops.zipWithNext().map { (a, b) ->
        async { runCatching { twin.sectionGeometry(a.stopId, b.stopId).obj()?.get("geometry") }.getOrNull() }
    }.awaitAll()
    val geometry = mergeLines(parts) ?: throw ToolError("no track known along these stations")
    val colour = input.str("colour")?.lowercase()?.takeIf { it in COLOURS } ?: "blue"
    val label = input.str("label") ?: "${stops.first().name} – ${stops.last().name}"
    ToolOutcome("ok: path drawn (id path:${stops.first().stopId}-${stops.last().stopId})",
        visuals = listOf(SectionVisual("path:${stops.first().stopId}-${stops.last().stopId}", colour, label, simplifyGeometry(geometry))))
}

/** LineStrings / MultiLineStrings -> one MultiLineString. */
internal fun AssistantTools.mergeLines(parts: List<JsonElement?>): JsonObject? {
    val lines = ArrayList<JsonElement>()
    parts.forEach { g ->
        val o = g as? JsonObject ?: return@forEach
        val coords = o["coordinates"] as? JsonArray ?: return@forEach
        when (o.str("type")) {
            "LineString" -> lines.add(coords)
            "MultiLineString" -> lines.addAll(coords)
        }
    }
    if (lines.isEmpty()) return null
    return buildJsonObject { put("type", "MultiLineString"); put("coordinates", JsonArray(lines)) }
}

internal suspend fun AssistantTools.label(input: JsonObject): ToolOutcome {
    val text = input.str("text") ?: throw ToolError("text is required")
    val (lon, lat) = input.str("stopId")?.let { s ->
        val st = station(s, "stopId").let { x -> if (x.lat != null) x else twinSearch(x.stopId).firstOrNull { it.stopId == x.stopId } ?: x }
        (st.lon ?: throw ToolError("no coordinates for ${st.name}")) to st.lat!!
    } ?: ((input.double("lon") ?: throw ToolError("give stopId or lon/lat")) to (input.double("lat") ?: throw ToolError("lat is required")))
    val id = "label:" + (input.str("stopId") ?: "%.4f,%.4f".format(lon, lat))
    return ToolOutcome("ok: label placed (id $id)", visuals = listOf(LabelVisual(id, lon, lat, text.take(80))))
}

internal suspend fun AssistantTools.fit(input: JsonObject): ToolOutcome {
    val stops = input.strings("stopIds").mapNotNull { runCatching { station(it, "stopIds").stopId }.getOrNull() }
    val v = FitVisual(input.strings("trainIds"), stops, input.strings("sectionIds"))
    if (v.trainIds.isEmpty() && v.stopIds.isEmpty() && v.sectionIds.isEmpty()) throw ToolError("give trainIds, stopIds or sectionIds")
    return ToolOutcome("ok: map will fit", visuals = listOf(v))
}

internal suspend fun AssistantTools.markStation(input: JsonObject): ToolOutcome {
    val st = station(input.str("stopId"), "stopId").let { s ->
        if (s.lat != null && s.lon != null) s else twinSearch(s.stopId).firstOrNull { it.stopId == s.stopId } ?: s
    }
    if (st.lat == null || st.lon == null) throw ToolError("no coordinates for station ${st.name}")
    val role = input.str("role")?.lowercase()?.takeIf { it in ROLES } ?: throw ToolError("role must be one of ${ROLES.joinToString()}")
    return ToolOutcome("ok: station marked", visuals = listOf(StationVisual(st.stopId, st.name, st.lon, st.lat, role, input.str("label"))))
}

internal fun AssistantTools.startTimer(input: JsonObject, turn: TurnState): ToolOutcome {
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

internal fun AssistantTools.respond(input: JsonObject, turn: TurnState): ToolOutcome {
    val answer = input.str("answer") ?: throw ToolError("answer is required")
    val options = input.objects("options").map { o ->
        RespondOption(o.str("journeyId"), o.str("label") ?: "", o.bool("recommended") ?: false, o.str("why"))
    }
    val ids = if (input.containsKey("situationIds")) input.strings("situationIds") else null
    val unavailable = input.strings("unavailable").map { it.trim().lowercase() }.filter { it in Scope.TOPICS }
    turn.respond = RespondInput(answer.trim(), input.str("summary"), options, input.str("followUp"), ids, unavailable, input.bool("append") == true)
    return ToolOutcome("ok")
}

/** Origin, transfer and destination markers of a drawn journey, from the planner's legs. */
fun AssistantTools.routeMarkers(route: RouteVisual): List<StationVisual> {
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
fun AssistantTools.autoFocus(visuals: List<VisualCommand>): FocusVisual? {
    val box = BBoxBuilder()
    visuals.forEach { v ->
        when (v) {
            is RouteVisual -> v.legs.forEach { box.addGeometry(it.geometry) }
            is SectionVisual -> box.addGeometry(v.geometry)
            is StationVisual -> box.add(v.lon, v.lat)
            is LabelVisual -> box.add(v.lon, v.lat)
            is TrainVisual -> queries.trainState(v.trainId)?.position?.let { box.add(it.lon, it.lat) }
            else -> Unit
        }
    }
    return box.build()?.let { FocusVisual(it) }
}
