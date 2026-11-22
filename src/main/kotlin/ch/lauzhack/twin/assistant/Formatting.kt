package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.ZURICH
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * A time given by the model: epoch ms, `HH:MM` (also `H.MM`, `HHhMM`) Swiss time, or an ISO date-time.
 * `HH:MM` is the next such time from 3 h ago on, so "00:15" said at 23:30 means tomorrow.
 */
fun parseTime(raw: String?, now: Long): Long? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    s.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it else null }
    Regex("^(\\d{1,2})[:.h](\\d{2})$").find(s)?.let { m ->
        val (h, mi) = m.destructured
        if (h.toInt() > 23 || mi.toInt() > 59) return null
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZURICH)
        var t = ZonedDateTime.of(today, LocalTime.of(h.toInt(), mi.toInt()), ZURICH).toInstant().toEpochMilli()
        if (t < now - 3 * 3_600_000L) t += 24 * 3_600_000L
        return t
    }
    runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    runCatching { return LocalDateTime.parse(s).atZone(ZURICH).toInstant().toEpochMilli() }
    return null
}
