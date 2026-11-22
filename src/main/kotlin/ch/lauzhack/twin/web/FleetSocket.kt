package ch.lauzhack.twin.web

import ch.lauzhack.twin.query.FleetFeed
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
