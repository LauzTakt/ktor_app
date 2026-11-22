package ch.lauzhack.twin.passenger

import ch.lauzhack.twin.common.capitalised
import ch.lauzhack.twin.common.objects
import ch.lauzhack.twin.common.str
import kotlinx.serialization.json.JsonArray

/* What the brief says and what it shows, in words for the voice agent. Part of [PlaceBrief]. */

private val sentenceEnd = Regex("(?<=\\.)\\s+")

/** A plain summary the agent may say in its own words (and the text box shows when the voice is off). */
internal fun PlaceBrief.sayFor(place: String, ds: List<BriefDisruption>, js: List<BriefJourney>, dest: String?): String {
    val main = ds.find { serious(it) }
    val parts = mutableListOf<String>()
    if (main != null) {
        parts += if (main.kind == "sbb" || main.text.isEmpty()) "${main.title}${main.cause?.let { ", due to $it" } ?: ""}."
            else main.text.split(sentenceEnd).take(2).joinToString(" ")
        main.until?.let { u ->
            parts += if (u.startsWith("at least")) "It lasts $u." else if (u.startsWith("expected")) "It is $u." else "${u.capitalised()}."
        }
        val bus = main.measures.find { Regex("^Replacement bus", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (bus != null) parts += bus.replaceFirst(Regex("^Replacement bus:\\s*", RegexOption.IGNORE_CASE), "").replaceFirst(Regex(", calling at [^.]+"), "").split(sentenceEnd)[0]
        else if (main.replacementBus) parts += "Replacement buses (EV) are running."
    } else {
        val ended = ds.find { it.effect == "resumed" }
        parts += ended?.text?.split(sentenceEnd)?.get(0) ?: "No disruption is reported for $place right now."
    }
    val best = js.firstOrNull()
    if (best != null && dest != null) {
        parts += if (best.uncertain) "The next connection leaves at ${best.departs} (${best.legs.firstOrNull()?.split(" ")?.take(3)?.joinToString(" ") ?: ""}), but its arrival time in $dest is uncertain right now."
            else "The best connection now leaves at ${best.departs} and arrives in $dest at ${best.arrives}" +
                (if (best.transfers > 0) " with ${best.transfers} change${if (best.transfers > 1) "s" else ""}" else "") +
                ": ${best.legs.joinToString(", then ")}."
    }
    return parts.joinToString(" ")
}

internal fun PlaceBrief.shownFor(vs: JsonArray): String {
    val bits = mutableListOf<String>()
    val objs = vs.objects()
    for (v in objs) {
        when (v.str("type")) {
            "route" -> if (v.str("style") == "recommended") bits += "the recommended route (${v.str("label")})"
            "section" -> bits += "the affected section \"${v.str("label")}\" in ${when (v.str("colour")) { "red" -> "red"; "orange" -> "orange"; else -> "green" }}"
            "timer" -> bits += "a countdown \"${v.str("label")}\""
        }
    }
    val alt = objs.count { it.str("type") == "route" && it.str("style") == "alternative" }
    if (alt > 0) bits += "$alt alternative route${if (alt > 1) "s" else ""}"
    return if (bits.isNotEmpty()) "The map now shows ${bits.joinToString(", ")}." else "The map shows the place."
}
