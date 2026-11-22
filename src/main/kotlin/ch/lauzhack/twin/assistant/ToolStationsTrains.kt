package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.assistant.AssistantTools.Companion.toItem
import ch.lauzhack.twin.assistant.AssistantTools.Companion.twinItem
import ch.lauzhack.twin.common.arr
import ch.lauzhack.twin.common.bool
import ch.lauzhack.twin.common.delayMin
import ch.lauzhack.twin.common.distanceKm
import ch.lauzhack.twin.common.double
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.minutes
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.opt
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.query.BoardType
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TrainFilter
import ch.lauzhack.twin.query.TrainSummaryDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/* Data tools: stations, boards, trains. Part of [AssistantTools]. */

internal suspend fun AssistantTools.findStation(input: JsonObject): ToolOutcome {
    val q = input.str("query") ?: throw ToolError("query is required")
    var hits = stations.search(q, 5)
    if (hits.isEmpty()) hits = twinSearch(q).take(5)
    return ok(buildJsonObject {
        put("matches", buildJsonArray { hits.forEach { add(buildJsonObject { put("stopId", it.stopId); put("name", it.name) }) } })
        if (hits.isEmpty()) put("note", "no station matches \"$q\"")
    })
}

internal suspend fun AssistantTools.stationBoard(input: JsonObject, turn: TurnState): ToolOutcome = coroutineScope {
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
        rows += t to buildJsonObject {
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
        rows += t to buildJsonObject {
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
    ok(buildJsonObject {
        put("station", buildJsonObject { put("stopId", st.stopId); put("name", st.name) })
        put("now", hhmm(turn.now))
        put("departures", buildJsonArray { rows.sortedBy { it.first }.take(16).forEach { add(it.second) } })
    })
}

internal suspend fun AssistantTools.trainStatus(input: JsonObject, turn: TurnState): ToolOutcome {
    input.str("trainId")?.let { return ok(trainDetail(it, turn)) }
    val line = input.str("line")?.let(::lineKey) ?: throw ToolError("give trainId, or line (e.g. IC8)")
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
        return distanceKm(ctx.lat, ctx.lon, p.lat, p.lon)
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
    return ok(buildJsonObject {
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
internal fun AssistantTools.candidateJson(t: TrainSummaryDto, distanceKm: Double?, turn: TurnState): JsonObject = buildJsonObject {
    put("trainId", t.trainId)
    opt("line", t.line)
    opt("number", t.number)
    opt("from", t.origin?.name)
    opt("to", t.destination?.name ?: t.headsign)
    put("now", describe(t, null))
    opt("delayMin", t.delaySec?.let { minutes(it) }?.takeIf { it != 0 })
    t.nextStop?.let { n -> put("next", buildJsonObject { put("name", n.name); opt("arr", hhmm(n.expectedArr ?: n.plannedArr)); opt("dep", hhmm(n.expectedDep ?: n.plannedDep)) }) }
    val stops = runCatching { queries.trainStops(t.trainId, null) }.getOrNull().orEmpty()
    stops.lastOrNull()?.takeIf { it.stopId != t.nextStop?.stopId }?.let { last -> opt("arrivesAtTerminus", hhmm(last.expectedArr ?: last.plannedArr)) }
    val incidents = runCatching { queries.trainIncidents(t.trainId) }.getOrNull().orEmpty()
    incidents.forEach { turn.note(it.toItem()) }
    if (incidents.isNotEmpty()) put("incidents", buildJsonArray { incidents.take(3).forEach { i -> add(buildJsonObject { put("id", i.id); put("title", i.title) }) } })
    opt("distanceKm", distanceKm?.let { (it * 10).roundToInt() / 10.0 })
}

internal suspend fun AssistantTools.trainDetail(trainId: String, turn: TurnState): JsonObject = coroutineScope {
    val sitAsync = async { runCatching { twin.situationOfTrain(trainId).obj() }.getOrNull() }
    val local = runCatching { queries.train(trainId) }.getOrNull()
    val remote = if (local == null) runCatching { twin.train(trainId).obj() }.getOrNull() else null
    val sit = sitAsync.await()
    if (local == null && remote == null) throw ToolError("train $trainId is not in service")
    val incidents = LinkedHashMap<String, JsonObject>()
    val out = buildJsonObject {
        if (local != null) {
            val t = local.train
            put("trainId", t.trainId)
            opt("line", t.line)
            opt("number", t.number)
            opt("from", t.origin?.name)
            opt("to", t.destination?.name ?: t.headsign)
            if (t.status == "cancelled") put("status", "cancelled")
            opt("delayMin", t.delaySec?.let { minutes(it) })
            put("now", describe(t, local.stops))
            opt("speedKmh", t.position?.speedKmh?.roundToInt())
            local.stops.lastOrNull { it.state == StopState.passed }?.let { p ->
                put("lastStop", buildJsonObject { put("name", p.name); opt("left", hhmm(p.actualDep ?: p.expectedDep ?: p.plannedDep)) })
            }
            val ahead = local.stops.filter { it.state != StopState.passed }
            val shown = if (ahead.size > 12) ahead.take(11) + ahead.last() else ahead
            put("nextStops", buildJsonArray { shown.forEach { add(stopJson(it)) } })
            val raw = rawPayloads(local.incidents.map { it.id })
            local.incidents.forEach { inc ->
                val item = inc.toItem()
                turn.note(item)
                incidents[inc.id] = buildJsonObject {
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
            incidents.putIfAbsent(id, buildJsonObject {
                put("id", id); put("title", item.title); opt("where", item.where); put("severity", item.severity)
                put("source", item.source); measure(inc, turn.now)
            })
        }
        put("incidents", JsonArray(incidents.values.toList()))
    }
    out
}

/** Train detail from the twin's `/trains/{id}` (`{position, info}`; its `truth` part is staff-only and ignored). */
internal fun AssistantTools.remoteTrain(o: JsonObject, b: JsonObjectBuilder) = with(b) {
    val info = o.obj("info")
    val pos = o.obj("position")
    put("trainId", info?.str("trainId") ?: pos?.str("trainId") ?: "")
    opt("line", info?.str("line") ?: pos?.str("line"))
    opt("number", info?.str("number"))
    opt("from", info?.obj("origin")?.str("name"))
    opt("to", info?.obj("destination")?.str("name") ?: info?.str("headsign"))
    if (info?.str("status") == "cancelled") put("status", "cancelled")
    opt("delayMin", pos?.int("delaySec")?.let { minutes(it) })
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
            add(buildJsonObject {
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
