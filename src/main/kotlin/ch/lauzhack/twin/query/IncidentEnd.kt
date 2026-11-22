package ch.lauzhack.twin.query

import ch.lauzhack.twin.common.ZURICH
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** When an incident is expected to end: [kind] = `at_least` ("at least until"), `approx` ("until approx."), `until`. */
data class IncidentEnd(val at: Long, val kind: String)

/**
 * The expected end of an incident, from its text. The MQTT feed has no end field: SBB messages say it in a sentence
 * ("The restriction lasts at least until 12:25.", "lasts until approx. 03:31", optionally with a date "23.11.2026").
 * A bare time is on the day of [ref] (the incident's last update), or the next day when it would be > 1 h before it.
 */
object IncidentEnds {
    private val SENTENCE = Regex(
        """\blasts\s+(at\s+least\s+)?until\s+(approx\.?\s+|about\s+)?(?:(\d{1,2})\.(\d{1,2})\.(\d{4}),?\s+)?(\d{1,2})[:.](\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, ref: Long): IncidentEnd? {
        val m = SENTENCE.find(text) ?: return null
        val (atLeast, approx, d, mo, y, h, mi) = m.destructured
        val time = runCatching { LocalTime.of(h.toInt(), mi.toInt()) }.getOrNull() ?: return null
        val kind = when { atLeast.isNotBlank() -> "at_least"; approx.isNotBlank() -> "approx"; else -> "until" }
        val at = if (y.isNotEmpty()) {
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(time).atZone(ZURICH).toInstant().toEpochMilli() }.getOrNull() ?: return null
        } else {
            val day = Instant.ofEpochMilli(ref).atZone(ZURICH).toLocalDate()
            val same = day.atTime(time).atZone(ZURICH).toInstant().toEpochMilli()
            if (same < ref - 3_600_000L) day.plusDays(1).atTime(time).atZone(ZURICH).toInstant().toEpochMilli() else same
        }
        return IncidentEnd(at, kind)
    }

}
