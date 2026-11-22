package ch.lauzhack.twin.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Swiss local time: every time the model reads or writes is in this zone. */
val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

fun hhmm(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZURICH).format(HHMM) }
fun dayTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZURICH).format(DAY_TIME)

/** Minutes of delay between an expected/actual and a planned time (rounded, + = late). */
fun delayMin(expected: Long?, planned: Long?): Int? =
    if (expected != null && planned != null) Math.round((expected - planned) / 60_000.0).toInt() else null

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

// ---------------------------------------------------------------- JSON helpers

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

/** `put` that leaves out nulls, empty strings and empty lists, to keep tool results short. */
fun JsonObjectBuilder.opt(key: String, value: Any?) {
    when (value) {
        null -> Unit
        is String -> if (value.isNotEmpty()) put(key, value)
        is Number -> put(key, value)
        is Boolean -> put(key, value)
        is JsonElement -> if (value !is JsonNull && !(value is JsonArray && value.isEmpty())) put(key, value)
        is List<*> -> if (value.isNotEmpty()) put(key, buildJsonArray { value.forEach { add(toJson(it)) } })
        else -> put(key, value.toString())
    }
}

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

inline fun jsonOf(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

/** kotlinx JSON -> plain Kotlin values (Map / List / String / Number / Boolean / null), e.g. for the SDK's `JsonValue.from`. */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { it.value.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

/** Plain Kotlin/Java values (as Jackson converts them) -> kotlinx JSON. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

// ---------------------------------------------------------------- geometry

/**
 * Simplifies a GeoJSON LineString / MultiLineString (Douglas-Peucker, ~[toleranceM] metres) and rounds coordinates to
 * 5 decimals (~1 m): a 100 km route shrinks from ~3,000 points to a few hundred, same picture.
 */
fun simplifyGeometry(geometry: JsonElement?, toleranceM: Double = 8.0): JsonElement? {
    val g = geometry.obj() ?: return geometry
    val type = g.str("type") ?: return g
    val eps = toleranceM / 111_000.0
    fun line(coords: JsonElement?): JsonArray? {
        val pts = coords.arr()?.mapNotNull { p ->
            val a = p.arr() ?: return@mapNotNull null
            val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            doubleArrayOf(lon, lat)
        } ?: return null
        return buildJsonArray { douglasPeucker(pts, eps).forEach { add(buildJsonArray { add(round5(it[0])); add(round5(it[1])) }) } }
    }
    return when (type) {
        "LineString" -> line(g["coordinates"])?.let { jsonOf { put("type", "LineString"); put("coordinates", it) } } ?: g
        "MultiLineString" -> jsonOf {
            put("type", "MultiLineString")
            put("coordinates", buildJsonArray { g["coordinates"].arr()?.forEach { l -> line(l)?.let { add(it) } } })
        }
        else -> g
    }
}

private fun round5(v: Double) = JsonPrimitive((v * 100_000).roundToLong() / 100_000.0)

private fun douglasPeucker(pts: List<DoubleArray>, eps: Double): List<DoubleArray> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.lastIndex] = true }
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.lastIndex) }
    val kx = 0.68 // cos(47°): degrees of longitude are shorter than degrees of latitude in Switzerland
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        val ax = pts[a][0] * kx; val ay = pts[a][1]
        val bx = pts[b][0] * kx; val by = pts[b][1]
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        var maxD = 0.0; var idx = -1
        for (i in a + 1 until b) {
            val px = pts[i][0] * kx; val py = pts[i][1]
            val d = if (len2 == 0.0) Math.hypot(px - ax, py - ay) else abs(dy * px - dx * py + bx * ay - by * ax) / Math.sqrt(len2)
            if (d > maxD) { maxD = d; idx = i }
        }
        if (idx > 0 && maxD > eps) {
            keep[idx] = true
            stack.add(a to idx); stack.add(idx to b)
        }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}

/** Grows a `[w, s, e, n]` box with every coordinate of a GeoJSON geometry. */
class BBoxBuilder {
    private var w = Double.MAX_VALUE; private var s = Double.MAX_VALUE
    private var e = -Double.MAX_VALUE; private var n = -Double.MAX_VALUE
    val isEmpty get() = w > e

    fun add(lon: Double?, lat: Double?) {
        if (lon == null || lat == null) return
        w = min(w, lon); e = max(e, lon); s = min(s, lat); n = max(n, lat)
    }

    fun addGeometry(geometry: JsonElement?) {
        fun walk(el: JsonElement?) {
            val a = el.arr() ?: return
            val first = a.firstOrNull()
            if (first is JsonPrimitive) add(first.doubleOrNull, (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull)
            else a.forEach { walk(it) }
        }
        walk(geometry.obj()?.get("coordinates"))
    }

    /** Padded by 12 % (at least ~1 km) so markers are not on the edge of the screen. */
    fun build(): List<Double>? {
        if (isEmpty) return null
        val padX = max((e - w) * 0.12, 0.012)
        val padY = max((n - s) * 0.12, 0.008)
        fun r(v: Double) = (v * 10_000).roundToLong() / 10_000.0
        return listOf(r(w - padX), r(s - padY), r(e + padX), r(n + padY))
    }
}
