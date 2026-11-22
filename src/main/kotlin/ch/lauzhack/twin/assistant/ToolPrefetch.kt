package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.assistant.AssistantTools.Companion.DESTINATION_PATTERNS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.HUBS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.TRIP_PATTERNS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.twinItem
import ch.lauzhack.twin.common.ZURICH
import ch.lauzhack.twin.common.arr
import ch.lauzhack.twin.common.distanceKm
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.model.Station
import ch.lauzhack.twin.query.StationSearch
import ch.lauzhack.twin.query.lineIn
import ch.lauzhack.twin.twinapi.CachedJourney
import ch.lauzhack.twin.twinapi.PlanQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/* Pre-model work: tools the question makes predictable, alternative corridors, guesses from the question and the screen. Part of [AssistantTools]. */

/**
 * Calls the tools the context makes predictable (journey from -> to, situation, the passenger's train) before the
 * first model call, so the model usually needs one turn less. Same output as the tools; failures are skipped.
 */
suspend fun AssistantTools.prefetch(turn: TurnState): List<Triple<String, JsonObject, ToolOutcome>> = coroutineScope {
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
    // What is open on the screen fills what the question and the trip leave open.
    val view = c.onScreen
    val viewTrain = view?.trainId?.trim()?.takeIf { view.screen == "train" && it.isNotEmpty() && it != train }
    // No destination given while the passenger looks at the trains from A to B: that trip (stronger than the GPS origin).
    if (to == null && train == null && view?.screen == "connections") {
        val a = view.fromStopId?.trim()?.takeIf { it.isNotEmpty() }
        val b = view.toStopId?.trim()?.takeIf { it.isNotEmpty() }
        if (a != null && b != null && a != b) { from = a; to = b }
    }
    val viewStop = view?.stopId?.trim()?.takeIf { (view.screen == "station" || view.screen == "destinations") && it.isNotEmpty() && it != from }
    val line = if (train == null) guessLine(turn.request.question)
        ?: view?.line?.trim()?.takeIf { view.screen == "line" && it.isNotEmpty() && viewTrain == null }?.let(::lineKey)
        else null
    val calls = buildList {
        if (viewTrain != null) add("train_status" to buildJsonObject { put("trainId", viewTrain) })
        if (viewStop != null && to == null && train == null) add("station_board" to buildJsonObject { put("stopId", viewStop); put("windowMin", 30) })
        if (line != null) add("train_status" to buildJsonObject { put("line", line); to?.let { put("destination", it) } })
        if (from != null && to != null) {
            add("plan_journey" to buildJsonObject { put("fromStopId", from); put("toStopId", to) })
            add("situation" to buildJsonObject { put("fromStopId", from); put("toStopId", to) })
        }
        if (train != null) add("train_status" to buildJsonObject { put("trainId", train) })
        if (from != null && to == null && train == null) {
            add("situation" to buildJsonObject { put("stopId", from) })
            add("station_board" to buildJsonObject { put("stopId", from); put("windowMin", 30) })
        }
    }
    val explored = if (from != null && to != null) {
        val f = from!!; val t = to!!
        async { runCatching { explore(f, t, turn) }.onFailure { log.warn("explore failed: {}", it.toString()) }.getOrNull() }
    } else null
    val done = calls.map { (n, args) -> async { Triple(n, args, execute(n, args, turn, logAs = "pre:$n")) } }.awaitAll().filter { !it.third.isError }
    val ex = explored?.await()
    if (ex != null) {
        turn.toolsCalled += "pre:explore"
        done + Triple("explored_alternatives", buildJsonObject { put("fromStopId", from); put("toStopId", to) }, ToolOutcome(ex))
    } else done
}

/**
 * Deterministic exploration before the model answers (when origin and destination are known): the direct journeys,
 * waiting for the reopening of a related disruption, routes via 2-3 hubs between origin and destination (and the
 * nearest hub), and at night the first morning services. All in parallel; a compact text with a verdict.
 */
suspend fun AssistantTools.explore(fromId: String, toId: String, turn: TurnState): String? = coroutineScope {
    val a = stations.resolve(fromId) ?: return@coroutineScope null
    val b = stations.resolve(toId) ?: return@coroutineScope null
    if (a.stopId == b.stopId || a.lat == null || a.lon == null || b.lat == null || b.lon == null) return@coroutineScope null
    val dAB = distanceKm(a.lat, a.lon, b.lat, b.lon).coerceAtLeast(1.0)
    fun ratio(s: Station) = if (s.lat == null || s.lon == null) 99.0 else
        (distanceKm(a.lat, a.lon, s.lat, s.lon) + distanceKm(s.lat, s.lon, b.lat, b.lon)) / dAB

    val disruptionsCall = async { runCatching { twin.disruptions() }.getOrNull()?.arr()?.mapNotNull { it.obj() }.orEmpty() }
    val directCall = async { runCatching { planValidated(PlanQuery(a.stopId, b.stopId, null, 3, null)).second }.getOrDefault(emptyList()) }
    val direct = directCall.await().let { l -> val sk = offCorridor(a.stopId, b.stopId); l.map { it.copy(journey = stripOffRoute(it.journey, sk)) } }
    val active = disruptionsCall.await().filter { it.str("status") == "active" && it.str("effect") in setOf("interrupted", "limited") }
    fun sectionIds(d: JsonObject, insideOnly: Boolean): Set<String> {
        val sec = d.obj("section") ?: return emptySet()
        val ids = sec.objects("inside").mapNotNull { it.str("id") }.toMutableSet()
        if (!insideOnly) { sec.obj("a")?.str("id")?.let(ids::add); sec.obj("b")?.str("id")?.let(ids::add) }
        return ids
    }
    val related = active.filter { d ->
        // The section lies between origin and destination (both ends near the corridor). The legs' incidents are not
        // enough: the twin tags every leg touching a section's end station.
        val sec = d.obj("section")
        val ends = listOfNotNull(sec?.obj("a")?.str("id"), sec?.obj("b")?.str("id")).mapNotNull { stations.byId(it) }
        ends.size == 2 && ends.all { ratio(it) <= 1.25 }
    }
    val night = Instant.ofEpochMilli(turn.now).atZone(ZURICH).hour in 0..4
    if (related.isEmpty() && direct.isNotEmpty() && !night) return@coroutineScope null

    // Waiting for the reopening of the first related disruption with an expected end.
    val wait = related.firstOrNull { (it.long("until") ?: 0L) > turn.now }
    val waitCall = wait?.let { d ->
        async { runCatching { planValidated(PlanQuery(a.stopId, b.stopId, d.long("until"), 2, null)).second }.getOrDefault(emptyList()) }
    }
    // Hubs roughly between origin and destination, not inside an interrupted section; plus the nearest hub.
    val closedInside = related.filter { it.str("effect") == "interrupted" }.flatMap { sectionIds(it, true) }.toSet()
    val hubPool = HUBS.mapNotNull { stations.byId(it) }.filter { it.stopId != a.stopId && it.stopId != b.stopId && it.stopId !in closedInside }
    val between = hubPool.map { it to ratio(it) }.filter { it.second <= 1.45 }.sortedBy { it.second }.take(3).map { it.first }
    val nearest = hubPool.filter { it.lat != null && it.lon != null }
        .minByOrNull { distanceKm(a.lat, a.lon, it.lat!!, it.lon!!) }
        ?.takeIf { distanceKm(a.lat, a.lon, it.lat!!, it.lon!!) <= 30.0 }
    val hubs = (between + listOfNotNull(nearest)).distinctBy { it.stopId }.take(4)
    val hubCalls = hubs.map { h ->
        async {
            runCatching {
                val first = planValidated(PlanQuery(a.stopId, h.stopId, null, 1, null)).second.firstOrNull() ?: return@runCatching null
                val arrH = first.journey.long("arrival") ?: return@runCatching null
                val second = planValidated(PlanQuery(h.stopId, b.stopId, arrH + 4 * 60_000L, 1, null)).second.firstOrNull()
                    ?: return@runCatching null
                Triple(h, first, second)
            }.getOrNull()
        }
    }
    val morningCall = if (night) {
        val five = Instant.ofEpochMilli(turn.now).atZone(ZURICH).withHour(5).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
        async { runCatching { planValidated(PlanQuery(a.stopId, b.stopId, five, 2, null)).second }.getOrDefault(emptyList()) }
    } else null
    val skip = offCorridor(a.stopId, b.stopId)
    fun CachedJourney.clean() = copy(journey = stripOffRoute(journey, skip))
    val waited = waitCall?.await().orEmpty().map { it.clean() }
    val viaHubs = hubCalls.awaitAll().filterNotNull().map { (h, j1, j2) -> Triple(h, j1.clean(), j2.clean()) }
    val morning = morningCall?.await().orEmpty().map { it.clean() }

    (direct + waited + morning + viaHubs.flatMap { listOf(it.second, it.third) }).forEach { cj ->
        cj.journey.long("departure")?.let { turn.publishedDepartures += it }
        cj.journey.objects("legs").forEach { leg -> leg.objects("incidents").forEach { inc -> turn.note(twinItem(inc)) } }
    }

    data class Cand(val label: String, val arrival: Long, val text: String)
    val cands = ArrayList<Cand>()
    val text = buildString {
        appendLine("Explored for ${a.name} → ${b.name} (straight-line ${dAB.roundToInt()} km), all live:")
        appendLine("Direct (planner):")
        if (direct.isEmpty()) appendLine("  none found")
        direct.forEach { cj -> appendLine("  " + journeyLine(cj.journey)); cj.journey.long("arrival")?.let { cands += Cand("direct", it, "") } }
        if (wait != null) {
            val dt = wait.str("durationType") ?: "UNKNOWN"
            val uncertain = dt != "UNTIL"
            appendLine("Wait for the reopening of ${wait.str("where") ?: wait.str("summary") ?: "the disruption"} " +
                "(${if (dt == "UNTIL_AT_LEAST") "at least until" else if (dt == "UNTIL") "expected until" else "end unknown, estimate"} " +
                "${untilText(wait.long("until"), turn.now)}${if (uncertain) "; UNCERTAIN, the end may be later" else ""}):")
            if (waited.isEmpty()) appendLine("  none found")
            waited.take(1).forEach { cj -> appendLine("  " + journeyLine(cj.journey)); cj.journey.long("arrival")?.let { cands += Cand("waiting for the reopening" + if (uncertain) " (uncertain)" else "", it, "") } }
        }
        if (viaHubs.isNotEmpty()) {
            appendLine("Via a hub (two journeys, at least 4 min to change):")
            viaHubs.forEach { (h, j1, j2) ->
                appendLine("  via ${h.name}: arrives ${hhmm(j2.journey.long("arrival"))}")
                appendLine("    1) " + journeyLine(j1.journey))
                appendLine("    2) " + journeyLine(j2.journey))
                j2.journey.long("arrival")?.let { cands += Cand("via ${h.name}", it, "") }
            }
        }
        if (night) {
            appendLine("First services of the morning (after 05:00):")
            if (morning.isEmpty()) appendLine("  none found")
            morning.forEach { cj -> appendLine("  " + journeyLine(cj.journey)); cj.journey.long("arrival")?.let { cands += Cand("morning", it, "") } }
        }
        if (related.isNotEmpty()) appendLine("Related disruptions: " + related.joinToString("; ") { "${it.str("summary") ?: it.str("where")} (${it.str("id")})" })
        val best = cands.minByOrNull { it.arrival }
        if (best != null) {
            val ties = cands.filter { hhmm(it.arrival) == hhmm(best.arrival) }.map { it.label }.distinct()
            appendLine("Verdict: earliest arrival found is ${hhmm(best.arrival)} (${ties.joinToString(" = ")}).")
        } else appendLine("Verdict: no journey found in the hours the planner covers.")
    }
    text.trim()
}

/** One journey in one line: id, times, changes, legs, warnings. */
internal fun AssistantTools.journeyLine(j: JsonObject): String {
    val legs = j.objects("legs").joinToString("; ") { l ->
        val f = l.obj("from"); val t = l.obj("to")
        "${l.str("line") ?: l.str("category") ?: "?"} ${f?.str("name")} ${hhmm(f?.long("expectedDep") ?: f?.long("plannedDep"))} → " +
            "${t?.str("name")} ${hhmm(t?.long("expectedArr") ?: t?.long("plannedArr"))}"
    }
    val dur = j.int("durationMin")?.let { " ${it / 60}h${"%02d".format(it % 60)}" } ?: ""
    val warn = j.strings("warnings").take(3).takeIf { it.isNotEmpty() }?.joinToString("; ", " [", "]") ?: ""
    return "${j.str("id")}: dep ${hhmm(j.long("departure"))} arr ${hhmm(j.long("arrival"))}$dur, ${j.int("transfers") ?: 0} changes: $legs$warn"
}

/** Origin and destination named in the question, when both resolve with confidence to different stations. */
fun AssistantTools.guessTrip(question: String): Pair<Station, Station>? {
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
    val swap = first.second in StationSearch.TO_WORDS && second.second !in StationSearch.TO_WORDS || second.second in StationSearch.FROM_WORDS && first.second !in StationSearch.FROM_WORDS
    return if (swap) second.first to first.first else first.first to second.first
}

/** A destination named in the question ("to Bern", "nach Bern", "pour Genève", ...). */
fun AssistantTools.guessDestination(question: String): Station? =
    DESTINATION_PATTERNS.firstNotNullOfOrNull { re -> re.findAll(question).firstNotNullOfOrNull { stations.confident(it.groupValues[1]) } }

/** A train line named in the question, e.g. "IC8", "IC 8", "S3" -> "IC8", "S3". */
fun AssistantTools.guessLine(question: String): String? =
    lineIn(question)

internal fun AssistantTools.trainText(id: String): String {
    val t = runCatching { queries.trainSummary(id) }.getOrNull()
    return (t?.let { "${it.line ?: ""} ${it.number ?: ""} to ${it.destination?.name ?: it.headsign ?: "?"} ".replace(Regex("\\s+"), " ").trim() + " " } ?: "") + "(trainId $id)"
}

/** What the passenger has open in the app right now, in words (null = nothing specific). */
fun AssistantTools.describeScreen(v: ScreenView?): String? {
    if (v == null) return null
    fun id(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
    fun st(s: String?) = id(s)?.let { x -> stations.resolve(x)?.let { "${it.name} ($x)" } ?: x }
    return when (v.screen?.trim()) {
        "train" -> id(v.trainId)?.let { t ->
            "the live view of train ${trainText(t)}" + (st(v.fromStopId)?.let { f -> st(v.toStopId)?.let { " for a trip $f -> $it" } } ?: "")
        }
        "line" -> id(v.line)?.let { "the status page of line ${lineKey(it)} (its incidents and trains in service)" }
        "station" -> st(v.stopId)?.let { "the departure board of $it" }
        "destinations" -> st(v.stopId)?.let { "the list of destinations from $it (choosing where to go)" }
        "connections" -> st(v.fromStopId)?.let { f -> st(v.toStopId)?.let { "the live trains from $f to $it" } }
        else -> null
    }
}

/** Lines describing the app's context for the first message (names resolved). */
fun AssistantTools.describeContext(c: AskContext?): List<String> {
    if (c == null) return emptyList()
    fun st(id: String?) = id?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> stations.resolve(s)?.let { "${it.name} ($s)" } ?: s }
    return buildList {
        st(c.fromStopId)?.let { add("Origin: $it") }
        st(c.toStopId)?.let { add("Destination: $it") }
        c.trainId?.trim()?.takeIf { it.isNotEmpty() }?.let { id -> add("The passenger's train: ${trainText(id)}") }
        if (c.lat != null && c.lon != null) {
            val near = stations.nearest(c.lat, c.lon, 3.0)
            add("Passenger location: %.5f, %.5f".format(Locale.ROOT, c.lat, c.lon) +
                (near?.let { (s, km) -> ", about ${(km * 1000).roundToInt()} m from ${s.name} (${s.stopId})" } ?: ""))
        }
        describeScreen(c.onScreen)?.let {
            add("Open on the passenger's screen right now: $it. Words like \"this train\", \"this line\", \"here\" or a " +
                "question without a subject refer to it; it is what they are looking at, not necessarily their own trip.")
        }
    }
}
