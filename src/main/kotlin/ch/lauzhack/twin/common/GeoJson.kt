package ch.lauzhack.twin.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/* GeoJSON helpers (sbb-twin geometries, map visuals): one coordinate walk, bounding boxes, simplification. */

/** Calls [f] with every `(lon, lat)` of a GeoJSON geometry, at any nesting depth. */
fun forEachCoord(geometry: JsonElement?, f: (lon: Double, lat: Double) -> Unit) {
    fun walk(el: JsonElement?) {
        val a = el.arr() ?: return
        val first = a.firstOrNull()
        if (first is JsonPrimitive) {
            val lon = first.doubleOrNull
            val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
            if (lon != null && lat != null) f(lon, lat)
        } else a.forEach { walk(it) }
    }
    walk(geometry.obj()?.get("coordinates"))
}

/** Every `(lon, lat)` pair of a GeoJSON geometry. */
fun coordsOf(geometry: JsonElement?): List<Pair<Double, Double>> =
    buildList { forEachCoord(geometry) { lon, lat -> add(lon to lat) } }

/** Grows a `[w, s, e, n]` box with points and GeoJSON geometries. */
class BBoxBuilder {
    private var w = Double.MAX_VALUE; private var s = Double.MAX_VALUE
    private var e = -Double.MAX_VALUE; private var n = -Double.MAX_VALUE
    val isEmpty get() = w > e

    fun add(lon: Double?, lat: Double?) {
        if (lon == null || lat == null) return
        w = min(w, lon); e = max(e, lon); s = min(s, lat); n = max(n, lat)
    }

    fun addGeometry(geometry: JsonElement?) = forEachCoord(geometry, ::add)

    /** `[w, s, e, n]` as is, or null when empty. */
    fun raw(): List<Double>? = if (isEmpty) null else listOf(w, s, e, n)

    /** Padded by 12 % (at least ~1 km) so markers are not on the edge of the screen. */
    fun build(): List<Double>? {
        if (isEmpty) return null
        val padX = max((e - w) * 0.12, 0.012)
        val padY = max((n - s) * 0.12, 0.008)
        fun r(v: Double) = (v * 10_000).roundToLong() / 10_000.0
        return listOf(r(w - padX), r(s - padY), r(e + padX), r(n + padY))
    }
}

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
        "LineString" -> line(g["coordinates"])?.let { buildJsonObject { put("type", "LineString"); put("coordinates", it) } } ?: g
        "MultiLineString" -> buildJsonObject {
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
