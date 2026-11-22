package ch.lauzhack.twin.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Swiss local time: the feed is in epoch ms UTC, everything shown to people is in this zone. */
val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")

private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZURICH)

/** `HH:mm`, Swiss time. */
fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms))

/** `HH:mm`, Swiss time, or null for an unknown time. */
fun hhmm(ms: Long?): String? = ms?.let { HHMM.format(Instant.ofEpochMilli(it)) }
