package ch.lauzhack.twin.web

import ch.lauzhack.twin.state.LiveState
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Compact fleet snapshot for the staff map, encoded once per `fleet` tick and shared by every browser.
 * Row layout (same as the sbb-twin viewer): `[trainId, lat, lon, bearing, speedKmh, motion, delaySec, line, category,
 * atStopId, continuity]`.
 */
class FleetFeed(private val state: LiveState) {
    class Snapshot(val ts: Long, val text: String)

    @Volatile private var cached: Snapshot? = null

    /** Latest snapshot, or null before the first `fleet` message. */
    fun current(): Snapshot? {
        val ts = state.lastFleetTs
        if (ts == 0L) return null
        cached?.takeIf { it.ts == ts }?.let { return it }
        synchronized(this) {
            cached?.takeIf { it.ts == ts }?.let { return it }
            return Snapshot(ts, encode(ts)).also { cached = it }
        }
    }

    private fun encode(ts: Long): String = buildJsonObject {
        put("type", "tick")
        put("ts", ts)
        putJsonArray("trains") {
            for (p in state.positions().values) addJsonArray {
                add(p.trainId); add(p.lat); add(p.lon); add(p.bearing ?: 0.0); add(p.speedKmh ?: 0.0)
                add(p.motion ?: "moving"); add(p.delaySec ?: 0); add(p.line ?: ""); add(p.category ?: "")
                add(p.atStopId); add(p.continuity)
            }
        }
    }.toString()
}

/** `GET /ws/fleet` (WebSocket): one `tick` text frame per new `fleet` message (~1 Hz). Server push only. */
fun Route.fleetSocket(feed: FleetFeed) = webSocket("/ws/fleet") {
    var last = -1L
    while (isActive) {
        val snap = feed.current()
        if (snap != null && snap.ts != last) {
            send(Frame.Text(snap.text))
            last = snap.ts
        }
        delay(200)
    }
}
