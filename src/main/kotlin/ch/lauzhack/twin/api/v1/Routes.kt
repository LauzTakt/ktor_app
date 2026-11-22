package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.common.invalid
import ch.lauzhack.twin.query.BBox
import ch.lauzhack.twin.query.BoardType
import ch.lauzhack.twin.query.IncidentQueries
import ch.lauzhack.twin.query.JourneyQueries
import ch.lauzhack.twin.query.StopState
import ch.lauzhack.twin.query.TrainFilter
import ch.lauzhack.twin.query.TwinQueries
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.Parameters
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** Public read API, mounted at `/api/v1`. Contract: docs/API.md. */
fun Route.apiV1(q: TwinQueries, journey: JourneyQueries, incidents: IncidentQueries, mqttConnected: () -> Boolean) = route("/api/v1") {
    get("/status") { call.respond(q.status(mqttConnected())) }

    route("/trains") {
        get {
            val p = call.request.queryParameters
            val filter = TrainFilter(
                line = p["line"], category = p["category"], status = p["status"], stationId = p["stationId"],
                atStation = p.bool("atStation"), minDelaySec = p.int("minDelaySec"), bbox = BBox.parse(p["bbox"]),
            )
            call.respond(q.trains(filter, p.limit(default = 100, max = 2000), p.int("offset", min = 0) ?: 0))
        }
        // Passenger view: "which train am I on?" from the phone's location (constant segment wins over /{trainId}).
        get("/nearby") {
            val p = call.request.queryParameters
            call.respond(
                journey.nearbyTrains(
                    lat = p.double("lat", -90.0, 90.0) ?: bad("bad_lat", "lat is required"),
                    lon = p.double("lon", -180.0, 180.0) ?: bad("bad_lon", "lon is required"),
                    radiusM = p.int("radiusM", min = 10, max = 20_000) ?: 2_000,
                    limit = p.limit(default = 5, max = 20),
                    headingDeg = p.double("headingDeg", 0.0, 360.0),
                    speedKmh = p.double("speedKmh", 0.0, 500.0),
                    fixTs = p["ts"]?.let { it.toLongOrNull() ?: bad("bad_ts", "ts must be epoch milliseconds") },
                ),
            )
        }
        get("/{trainId}") { call.respond(q.train(call.path("trainId"))) }
        get("/{trainId}/position") { call.respond(q.trainPosition(call.path("trainId"))) }
        get("/{trainId}/stops") {
            val only = call.request.queryParameters.enum<StopState>("state")
            call.respond(q.trainStops(call.path("trainId"), only))
        }
        get("/{trainId}/incidents") { call.respond(q.trainIncidents(call.path("trainId"))) }
    }

    // Lines of the trains in service (for "follow a line": push notifications on its incidents).
    get("/lines") { call.respond(q.lines(call.request.queryParameters["q"])) }
    get("/lines/{line}") { call.respond(q.line(call.path("line"))) }

    route("/stations") {
        get {
            val p = call.request.queryParameters
            call.respond(q.stations(p["q"], BBox.parse(p["bbox"]), p.limit(default = 50, max = 5000)))
        }
        get("/nearby") {
            val p = call.request.queryParameters
            call.respond(
                journey.nearbyStations(
                    lat = p.double("lat", -90.0, 90.0) ?: bad("bad_lat", "lat is required"),
                    lon = p.double("lon", -180.0, 180.0) ?: bad("bad_lon", "lon is required"),
                    radiusM = p.int("radiusM", min = 10, max = 50_000) ?: 5_000,
                    limit = p.limit(default = 8, max = 50),
                ),
            )
        }
        get("/{stopId}") { call.respond(q.station(call.path("stopId"))) }
        get("/{stopId}/destinations") {
            val p = call.request.queryParameters
            call.respond(journey.destinations(call.path("stopId"), p["q"], p.limit(default = 30, max = 500)))
        }
        get("/{stopId}/connections") {
            val p = call.request.queryParameters
            call.respond(
                journey.connections(
                    fromStopId = call.path("stopId"),
                    toStopId = p["to"] ?: bad("bad_to", "to (destination stopId) is required"),
                    windowMin = p.int("windowMin", min = 1, max = 24 * 60) ?: 180,
                    limit = p.limit(default = 10, max = 100),
                ),
            )
        }
        get("/{stopId}/board") {
            val p = call.request.queryParameters
            call.respond(
                q.board(
                    stopId = call.path("stopId"),
                    type = p.enum<BoardType>("type") ?: BoardType.departures,
                    windowMin = p.int("windowMin", min = 1, max = 24 * 60) ?: 120,
                    limit = p.limit(default = 50, max = 500),
                    includeSkipped = p.bool("includeSkipped") ?: true,
                ),
            )
        }
        get("/{stopId}/incidents") { call.respond(q.stationIncidents(call.path("stopId"))) }
        get("/{stopId}/screen") {
            val p = call.request.queryParameters
            call.respond(
                q.screen(
                    stopId = call.path("stopId"),
                    limit = p.limit(default = 6, max = LiveUpdates.MAX_SCREEN_LIMIT),
                    windowMin = p.int("windowMin", min = 1, max = 24 * 60) ?: 120,
                ),
            )
        }
        // Same view as plain text for microcontrollers (ESP32 station screen); ETag + 304 when nothing changed.
        get("/{stopId}/screen.txt") {
            val p = call.request.queryParameters
            val text = ScreenText.render(
                q.screen(
                    stopId = call.path("stopId"),
                    limit = p.limit(default = 6, max = LiveUpdates.MAX_SCREEN_LIMIT),
                    windowMin = p.int("windowMin", min = 1, max = 24 * 60) ?: 120,
                    maxIncidents = p.int("incidents", min = 0, max = 5) ?: 3,
                ),
            )
            call.response.header(HttpHeaders.ETag, text.etag)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            if (call.request.header(HttpHeaders.IfNoneMatch) == text.etag) call.respond(HttpStatusCode.NotModified)
            else call.respondText(text.body, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }
    }

    route("/incidents") {
        get {
            val p = call.request.queryParameters
            call.respond(
                incidents.list(
                    all = p.bool("all") ?: false, severity = p["severity"], scopeKind = p["scopeKind"],
                    withGeometry = p.bool("geometry") ?: false,
                ),
            )
        }
        get("/{id}") { call.respond(incidents.incident(call.path("id"))) }
        get("/{id}/history") { call.respond(incidents.history(call.path("id"))) }
    }
}

// ---------------------------------------------------------------- parameter parsing

private fun RoutingCall.path(name: String): String = parameters[name]!!

private fun bad(code: String, msg: String): Nothing = invalid(code, msg)

private fun Parameters.int(name: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int? {
    val raw = this[name] ?: return null
    val v = raw.toIntOrNull() ?: bad("bad_$name", "$name must be an integer")
    if (v < min || v > max) bad("bad_$name", "$name must be between $min and $max")
    return v
}

private fun Parameters.double(name: String, min: Double, max: Double): Double? {
    val raw = this[name] ?: return null
    val v = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad("bad_$name", "$name must be a number")
    if (v < min || v > max) bad("bad_$name", "$name must be between $min and $max")
    return v
}

private fun Parameters.bool(name: String): Boolean? =
    this[name]?.let { it.toBooleanStrictOrNull() ?: bad("bad_$name", "$name must be true or false") }

private fun Parameters.limit(default: Int, max: Int) = int("limit", min = 1, max = max) ?: default

private inline fun <reified E : Enum<E>> Parameters.enum(name: String): E? = this[name]?.let { raw ->
    enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: bad("bad_$name", "$name must be one of ${enumValues<E>().joinToString { it.name }}")
}
