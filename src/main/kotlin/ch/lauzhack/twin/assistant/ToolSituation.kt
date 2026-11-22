package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.assistant.AssistantTools.Companion.alertItem
import ch.lauzhack.twin.assistant.AssistantTools.Companion.toItem
import ch.lauzhack.twin.assistant.AssistantTools.Companion.twinItem
import ch.lauzhack.twin.common.arr
import ch.lauzhack.twin.common.bool
import ch.lauzhack.twin.common.distanceKm
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.opt
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.db.StaffReportRow
import ch.lauzhack.twin.model.Station
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.Severity
import ch.lauzhack.twin.query.sameLine
import ch.lauzhack.twin.twinapi.TwinException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* Data tools: the situation (our incidents, staff reports, SBB alerts) and bulletins. Part of [AssistantTools]. */

internal suspend fun AssistantTools.situation(input: JsonObject, turn: TurnState): ToolOutcome {
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
    // For a trip: the twin's disruptions whose section is off the corridor (e.g. Zürich HB - Winterthur for
    // Zürich HB -> Bern) are left out, so the model never mentions closures that are not on the way.
    val offRoute: Set<String> = if (from != null && to != null) offCorridor(from, to) else emptySet()
    val out = buildJsonObject {
        fun addTwinStation(part: JsonObject?) {
            part?.objects("incidents")?.filter { it.str("id") !in offRoute }?.forEach { addIncident(twinItem(it), it.long("since"), it.strings("trainIds").size, it.str("description"), it, incidents, turn) }
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
                localStation(a.stopId, incidents, turn, offRoute)
                localStation(b.stopId, incidents, turn, offRoute)
                reports { it.stopId in setOf(a.stopId, b.stopId) || it.toStopId in setOf(a.stopId, b.stopId) }.forEach { addReport(it, incidents, turn) }
                opt("onTheWay", r?.strings("onTheWay"))
            }
            trainId != null -> {
                put("for", "train $trainId")
                val r = twinOrNull { twin.situationOfTrain(trainId) }
                r?.obj("train")?.let { tr ->
                    put("train", buildJsonObject {
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
                            distanceKm(r.lat, r.lon, s.lat, s.lon) < 2.0)
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

internal suspend fun AssistantTools.bulletins(input: JsonObject, turn: TurnState): ToolOutcome = coroutineScope {
    val st = input.str("stopId")?.let { station(it, "stopId") }
    val line = input.str("line")?.let(::lineKey)
    val bulletinsCall = async { twin.bulletins() }
    val disruptions = runCatching { twin.disruptions() }.getOrNull()?.arr()?.mapNotNull { it.obj() }.orEmpty()
        .associateBy { it.str("id") ?: "" }
    val all = bulletinsCall.await().arr()?.mapNotNull { it.obj() }.orEmpty()
    fun stopIds(d: JsonObject?): Set<String> {
        if (d == null) return emptySet()
        val ids = HashSet<String>()
        d.obj("scope")?.let { sc -> listOf("from", "to", "station").forEach { k -> sc.str(k)?.let(ids::add) } }
        d.obj("section")?.let { sec ->
            sec.obj("a")?.str("id")?.let(ids::add); sec.obj("b")?.str("id")?.let(ids::add)
            sec.objects("inside").forEach { o -> o.str("id")?.let(ids::add) }
        }
        d.objects("busRoute").forEach { o -> o.str("id")?.let(ids::add) }
        return ids
    }
    val kept = all.filter { b ->
        val d = disruptions[b.str("id")]
        val ended = b.str("lifecycle") == "ENDED"
        val endedAt = d?.long("endedAt") ?: b.long("ts")
        if (ended && endedAt != null && turn.now - endedAt > 30 * 60_000L) return@filter false
        if (st != null && st.stopId !in stopIds(d)) return@filter false
        if (line != null && b.strings("lines").none { sameLine(it, line) }) return@filter false
        true
    }.sortedWith(compareBy<JsonObject>({ if (it.str("lifecycle") == "ENDED") 1 else 0 }, { -(it.long("ts") ?: 0L) }))

    val text = buildString {
        val scope = listOfNotNull(st?.let { "station ${it.name}" }, line?.let { "line $it" }).joinToString(", ")
        if (kept.isEmpty()) {
            append("No disruption bulletins").append(if (scope.isEmpty()) "." else " for $scope.")
            return@buildString
        }
        append(kept.size).append(" bulletin(s)").append(if (scope.isEmpty()) "" else " for $scope").appendLine(", active first.")
        kept.take(6).forEach { b ->
            val id = b.str("id") ?: ""
            val d = disruptions[id]
            val title = b.str("title") ?: d?.str("summary") ?: "Disruption"
            val active = b.str("lifecycle") != "ENDED"
            turn.note(SituationItem(id, title, d?.str("where"), (if (!active) Severity.INFO else Severity.fromEffect(d?.str("effect"), unknown = Severity.CRITICAL)).id, "sbb"))
            appendLine()
            append("[").append(id).append("] ").append(title)
            append(" (").append(if (active) "active" else "ended, service resumed").append(", version ").append(b.int("version") ?: 1).appendLine(")")
            if (d != null) {
                val facts = listOfNotNull(
                    d.str("effect")?.let { "effect: $it" },
                    d.obj("cause")?.str("label")?.let { "cause: $it" },
                    if (active) d.long("until")?.let { "until: ${untilText(it, turn.now)}" } else d.long("endedAt")?.let { "resumed at ${hhmm(it)}" },
                    if (active) d.str("durationType")?.let { "durationType: $it" } else null,
                )
                if (facts.isNotEmpty()) appendLine("  " + facts.joinToString("; "))
                val measures = d.objects("measures").filter { (it.str("status") ?: it.str("state")) in setOf("active", "planned") }
                    .mapNotNull { it.str("label") ?: it.str("type") }.distinct()
                if (active && measures.isNotEmpty()) appendLine("  measures: " + measures.joinToString(", "))
                val bus = d.objects("busRoute").mapNotNull { it.str("name") }
                if (active && bus.isNotEmpty()) appendLine("  replacement bus stops: " + bus.joinToString(" – "))
            }
            b.str("description")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { l ->
                appendLine("  | " + shortenLines(l))
            }
            b.objects("messages").sortedByDescending { it.long("ts") ?: 0L }.take(3).forEach { m ->
                appendLine("  message ${hhmm(m.long("ts")) ?: ""} ${m.str("title") ?: ""}: ${m.str("text")?.take(400) ?: ""}".replace("  :", ":"))
            }
            val lines = b.strings("lines")
            if (lines.isNotEmpty()) appendLine("  lines: " + (if (lines.size > 12) lines.take(12).joinToString(", ") + " and ${lines.size - 12} more" else lines.joinToString(", ")))
        }
    }
    ToolOutcome(text.trim())
}

/** "Lines IC1, IC3, ... and S29 are affected." with dozens of lines -> the first ones and a count. */
internal fun AssistantTools.shortenLines(line: String): String {
    if (!line.startsWith("Lines ") || line.length < 160) return line.take(400)
    val names = line.removePrefix("Lines ").substringBefore(" are affected").replace(" and ", ", ").split(", ").map { it.trim() }
    return "Lines " + names.take(10).joinToString(", ") + " and ${names.size - 10} more are affected."
}


/** A journey without the leg incidents (and their warnings) of off-route disruptions. */
internal fun AssistantTools.stripOffRoute(j: JsonObject, skip: Set<String>): JsonObject {
    if (skip.isEmpty()) return j
    val dropped = HashSet<String>()
    val legs = JsonArray(j.objects("legs").map { l ->
        val inc = l.objects("incidents")
        val keep = inc.filter { (it.str("id") ?: "") !in skip }
        inc.filter { (it.str("id") ?: "") in skip }.mapNotNullTo(dropped) { it.str("title") }
        if (keep.size == inc.size) l else JsonObject(l + ("incidents" to JsonArray(keep)))
    })
    val warnings = JsonArray(j.strings("warnings").filter { it !in dropped }.map { JsonPrimitive(it) })
    return JsonObject(j + mapOf("legs" to legs, "warnings" to warnings))
}

/** Ids of active disruptions whose section does not lie between origin and destination. */
internal suspend fun AssistantTools.offCorridor(fromRaw: String, toRaw: String): Set<String> {
    val a = runCatching { station(fromRaw, "fromStopId") }.getOrNull() ?: return emptySet()
    val b = runCatching { station(toRaw, "toStopId") }.getOrNull() ?: return emptySet()
    val all = runCatching { twin.disruptions() }.getOrNull()?.arr()?.mapNotNull { it.obj() }.orEmpty()
    return all.filter { d ->
        val sec = d.obj("section")
        val ends = listOfNotNull(sec?.obj("a")?.str("id"), sec?.obj("b")?.str("id")).mapNotNull { stations.byId(it) }
        ends.size == 2 && !ends.all { corridorRatio(a, b, it) <= 1.25 }
    }.mapNotNull { it.str("id") }.toSet()
}

/** (|a s| + |s b|) / |a b|: 1 = on the straight line, larger = a detour. */
internal fun AssistantTools.corridorRatio(a: Station, b: Station, s: Station): Double {
    if (a.lat == null || a.lon == null || b.lat == null || b.lon == null || s.lat == null || s.lon == null) return 99.0
    val ab = distanceKm(a.lat, a.lon, b.lat, b.lon).coerceAtLeast(1.0)
    return (distanceKm(a.lat, a.lon, s.lat, s.lon) + distanceKm(s.lat, s.lon, b.lat, b.lon)) / ab
}

internal fun AssistantTools.localStation(stopId: String, into: MutableMap<String, JsonObject>, turn: TurnState, skip: Set<String> = emptySet()) {
    runCatching { queries.stationIncidents(stopId) }.getOrNull()?.filter { it.id !in skip && it.disruptionId !in skip }?.let { addLocal(it, into, turn) }
}

internal fun AssistantTools.reports(filter: (StaffReportRow) -> Boolean): List<StaffReportRow> =
    runCatching { reports.open(limit = 200) }.getOrDefault(emptyList()).filter(filter)

internal fun AssistantTools.addIncident(
    item: SituationItem, since: Long?, trains: Int?, description: String?, raw: JsonObject?,
    into: MutableMap<String, JsonObject>, turn: TurnState,
) {
    if (into.containsKey(item.id)) return
    turn.note(item)
    into[item.id] = buildJsonObject {
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
internal fun AssistantTools.rawPayloads(ids: Collection<String>): Map<String, JsonObject> =
    runCatching { incidents.latestPayloads(ids) }.getOrDefault(emptyMap())
        .mapNotNull { (id, json) -> runCatching { Json.parseToJsonElement(json).obj() }.getOrNull()?.let { id to it } }.toMap()

internal fun AssistantTools.addLocal(list: List<IncidentDto>, into: MutableMap<String, JsonObject>, turn: TurnState) {
    val raw = rawPayloads(list.map { it.id }.filter { it !in into })
    list.forEach { addIncident(it.toItem(), it.updatedAt, null, it.description, raw[it.id], into, turn) }
}

internal fun AssistantTools.addReport(r: StaffReportRow, into: MutableMap<String, JsonObject>, turn: TurnState) =
    addIncident(SituationItem(r.id, r.title, r.where, r.severity, "staff"), r.createdAt, null, r.description, null, into, turn)

internal fun AssistantTools.addAlert(a: JsonObject, into: MutableMap<String, JsonObject>, turn: TurnState) {
    val id = a.str("id") ?: return
    if (into.containsKey(id)) return
    val item = alertItem(a)
    turn.note(item)
    into[id] = buildJsonObject {
        put("id", id)
        put("title", item.title)
        put("severity", item.severity)
        opt("cause", a.str("cause"))
        opt("text", a.str("description")?.take(300))
    }
}
