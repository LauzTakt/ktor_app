package ch.lauzhack.twin.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Swiss local time: the feed is in epoch ms UTC, everything shown to people is in this zone. */
val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")

private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZURICH)

/** `HH:mm`, Swiss time. */
fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms))

/** `HH:mm`, Swiss time, or null for an unknown time. */
fun hhmm(ms: Long?): String? = ms?.let { HHMM.format(Instant.ofEpochMilli(it)) }

private val DAY_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZURICH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZURICH)

/** `Saturday 21 November 2026, 14:05`, Swiss time. */
fun dayTime(ms: Long): String = DAY_TIME.format(Instant.ofEpochMilli(ms))

/** `21 November`, Swiss time. */
fun dayMonth(ms: Long): String = DAY_MONTH.format(Instant.ofEpochMilli(ms))

/** Minutes of delay between an expected/actual and a planned time (rounded, + = late), or null if either is unknown. */
fun delayMin(expected: Long?, planned: Long?): Int? =
    if (expected != null && planned != null) ((expected - planned) / 60_000.0).roundToInt() else null

/** Seconds -> whole minutes, rounded. */
fun minutes(sec: Number): Int = (sec.toDouble() / 60).roundToInt()
