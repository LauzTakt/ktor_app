package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.cleanDashes
import ch.lauzhack.twin.common.dayMonth
import ch.lauzhack.twin.common.hhmm
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.obj
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import ch.lauzhack.twin.common.strings
import ch.lauzhack.twin.common.truthy
import ch.lauzhack.twin.query.mentionsLine
import ch.lauzhack.twin.query.sameLine
import kotlinx.serialization.json.JsonObject

/* Which disruptions concern a place, and how they read (incidents and SBB alerts -> BriefDisruption). Part of [PlaceBrief]. */

internal fun PlaceBrief.alertActive(a: JsonObject): Boolean {
    val t = now()
    val periods = a.objects("activePeriods")
    return periods.isEmpty() || periods.any { (it.long("start") ?: 0) <= t + 30 * 60_000 && (it.long("end") ?: Long.MAX_VALUE) > t }
}
internal fun PlaceBrief.isOpen(i: JsonObject) = i.str("status") != "ended" && i.str("lifecycle") != "ENDED"
/** Ended control disruptions stay worth a sentence ("service resumed") for 30 minutes. */
internal fun PlaceBrief.recentlyEnded(i: JsonObject) = !isOpen(i) && i.str("source") == "control" && now() - (i.long("updatedAt") ?: 0) < 30 * 60_000

internal fun PlaceBrief.untilWords(until: Long?, durationType: String?): String? {
    if (until == null || until == 0L) return if (durationType == "UNKNOWN" || durationType.isNullOrEmpty()) "the end is not known yet" else null
    val t = if (until - now() > 20 * 3_600_000L) "${dayMonth(until)}, ${hhmm(until)}" else hhmm(until)
    return when (durationType) {
        "UNTIL_AT_LEAST", "AT_LEAST" -> "at least until $t"
        "UNKNOWN" -> "the end is not known yet"
        else -> "expected until $t"
    }
}

private val linesAffected = Regex("^Lines? (.+) (?:is|are) affected\\.?$")
/** The bulletin's own plain words, with the endless "Lines IC1, IC3, … are affected." line shortened. */
internal fun PlaceBrief.plainText(text: String?): String = (text ?: "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { l ->
    val m = linesAffected.find(l) ?: return@joinToString l
    val lines = m.groupValues[1].split(Regex(",\\s*|\\s+and\\s+"))
    if (lines.size > 6) "Many lines are affected (${lines.take(5).joinToString(", ")} and ${lines.size - 5} more)." else l
}

internal fun PlaceBrief.fromIncident(i: JsonObject): BriefDisruption {
    val e = i.str("effect")
    val effect = if (e == "interrupted" || e == "limited" || e == "resumed") e else if (i.str("type")?.startsWith("auto") == true) "train" else null
    val source = i.str("source")
    return BriefDisruption(
        id = i.str("id") ?: "",
        kind = when (source) { "control" -> "operator"; "auto" -> "detected"; else -> "sbb" },
        title = cleanDashes(i.str("title") ?: ""),
        where = i.str("where"),
        effect = effect,
        cause = i.obj("cause").str("label"),
        until = if (effect == "resumed") null else if (source == "control") untilWords(i.long("until"), i.str("durationType")) else null,
        text = plainText(i.str("description")),
        measures = i.objects("messages").takeLast(3).map { "${it.str("title")}: ${it.str("text")}" },
        replacementBus = i.truthy("replacementBus"),
        severity = i.str("severity") ?: "info",
        active = isOpen(i),
    )
}

internal fun PlaceBrief.fromAlert(a: JsonObject): BriefDisruption {
    val t = now()
    val end = a.objects("activePeriods").map { it.long("end") ?: 0 }.filter { it > t }.minOrNull()
    val title = a.str("title") ?: ""
    val effect = if (Regex("^interrupted", RegexOption.IGNORE_CASE).containsMatchIn(title)) "interrupted"
        else if (Regex("^(limited|restricted|irregular)", RegexOption.IGNORE_CASE).containsMatchIn(title)) "limited" else null
    val stations = a.objects("stations")
    return BriefDisruption(
        id = a.str("id") ?: "",
        kind = "sbb",
        title = cleanDashes(title),
        where = if (stations.isNotEmpty()) stations.mapNotNull { it.str("name") }.take(2).joinToString(" – ") else null,
        effect = effect,
        cause = a.str("cause")?.replace("_", " ")?.takeIf { it.isNotEmpty() },
        until = end?.let { "expected until ${hhmm(it)}" },
        text = plainText(a.str("description")).take(600),
        measures = emptyList(),
        replacementBus = Regex("replacement bus|\\(EV\\)|Bahnersatz", RegexOption.IGNORE_CASE).containsMatchIn(a.str("description") ?: ""),
        severity = a.str("severity") ?: "info",
        active = true,
    )
}

internal fun PlaceBrief.touchesStation(id: String, inc: JsonObject, d: JsonObject?): Boolean {
    val s = inc.obj("scope")
    if (s != null && id in listOf(s.str("station"), s.str("stopId"), s.str("from"), s.str("to"))) return true
    val sec = d.obj("section") ?: return false
    return id in listOf(sec.obj("a").str("id"), sec.obj("b").str("id")) + sec.objects("inside").map { it.str("id") }
}

internal fun PlaceBrief.relevant(pic: PlaceBrief.Picture, stationIds: List<String>, line: String?, ids: Set<String>? = null, titles: Set<String>? = null): List<BriefDisruption> {
    val byDisruption = pic.disruptions.associateBy { it.str("id") }
    val out = mutableListOf<BriefDisruption>()
    for (inc in pic.incidents) {
        if (!isOpen(inc) && !recentlyEnded(inc)) continue
        val d = byDisruption[inc.str("disruptionId") ?: inc.str("id")]
        val id = inc.str("id")
        val title = inc.str("title")
        val hit = ids?.contains(id) == true || titles?.contains(title) == true ||
            (inc.str("source") != "auto" && stationIds.any { touchesStation(it, inc, d) }) ||
            (line != null && (inc.strings("lines").any { sameLine(it, line) } || (inc.str("source") == "auto" && mentionsLine(line, title ?: ""))))
        if (hit) out += fromIncident(inc).copy(onRoute = ids?.contains(id) == true || titles?.contains(title) == true)
    }
    for (a in pic.alerts) {
        if (!alertActive(a)) continue
        val id = a.str("id")
        val title = a.str("title")
        val hit = ids?.contains(id) == true || titles?.contains(title) == true ||
            stationIds.any { sid -> a.objects("stations").any { it.str("id") == sid } } ||
            (line != null && mentionsLine(line, "${title ?: ""} ${a.str("description") ?: ""}"))
        if (hit) out += fromAlert(a).copy(onRoute = ids?.contains(id) == true || titles?.contains(title) == true)
    }
    fun rank(d: BriefDisruption) = (if (d.active) 0 else 10) + (when (d.kind) { "operator" -> 0; "sbb" -> 1; else -> 3 }) +
        (when (d.effect) { "interrupted" -> 0; "limited" -> 1; else -> 2 })
    return out.sortedBy { rank(it) }.take(5)
}
