package ch.lauzhack.twin.common

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/* WGS84 helpers shared by every layer (queries, assistant, passenger app). */

private const val EARTH_M = 6_371_000.0
private fun Double.rad() = this * PI / 180

/** Great-circle distance in metres. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1).rad(); val dLon = (lon2 - lon1).rad()
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1.rad()) * cos(lat2.rad()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Great-circle distance in kilometres. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = haversineM(lat1, lon1, lat2, lon2) / 1000

/** Point [distM] metres from (lat, lon) along [bearingDeg] (small distances: equirectangular is plenty). */
fun move(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
    val b = bearingDeg.rad()
    val dLat = distM * cos(b) / EARTH_M
    val dLon = distM * sin(b) / (EARTH_M * cos(lat.rad()))
    return (lat + dLat * 180 / PI) to (lon + dLon * 180 / PI)
}

/** Smallest angle between two bearings, 0…180. */
fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)
