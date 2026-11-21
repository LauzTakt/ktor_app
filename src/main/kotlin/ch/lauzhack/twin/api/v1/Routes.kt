package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.db.Database
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

/** Uniform `{"error":{"code","message"}}` bodies for every API error. */
fun Application.installApiErrors() {
    val log = LoggerFactory.getLogger("api")
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorDto(ErrorBody(e.code, e.message))) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorBody("bad_request", e.message ?: "bad request")))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(ErrorBody("internal", "internal error")))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorDto(ErrorBody("not_found", "no such endpoint: ${call.request.local.uri}")))
        }
    }
}

/** Public read API, mounted at `/api/v1`. Contract: docs/API.md. */
fun Route.apiV1(q: TwinQueries, db: Database, mqttConnected: () -> Boolean) = route("/api/v1") {
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
        get("/{trainId}") { call.respond(q.train(call.path("trainId"))) }
        get("/{trainId}/position") { call.respond(q.trainPosition(call.path("trainId"))) }
        get("/{trainId}/stops") {
            val only = call.request.queryParameters.enum<StopState>("state")
            call.respond(q.trainStops(call.path("trainId"), only))
        }
        get("/{trainId}/incidents") { call.respond(q.trainIncidents(call.path("trainId"))) }
    }

    route("/stations") {
        get {
            val p = call.request.queryParameters
            call.respond(q.stations(p["q"], BBox.parse(p["bbox"]), p.limit(default = 50, max = 5000)))
        }
        get("/{stopId}") { call.respond(q.station(call.path("stopId"))) }
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
    }

    route("/incidents") {
        get {
            val p = call.request.queryParameters
            val all = p.bool("all") ?: false
            val severity = p["severity"]
            val kind = p["scopeKind"]
            call.respond(
                db.incidents(activeOnly = !all)
                    .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
                    .filter { kind == null || it.scope?.kind.equals(kind, ignoreCase = true) }
                    .map { it.toDto() },
            )
        }
        get("/{id}") {
            val id = call.path("id")
            call.respond(db.incident(id)?.toDto() ?: throw notFound("incident", id))
        }
        get("/{id}/history") {
            val id = call.path("id")
            if (db.incident(id) == null) throw notFound("incident", id)
            call.respond(db.incidentHistory(id).map { it.toDto() })
        }
    }
}

// ---------------------------------------------------------------- parameter parsing

private fun RoutingCall.path(name: String): String = parameters[name]!!

private fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, msg)

private fun Parameters.int(name: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int? {
    val raw = this[name] ?: return null
    val v = raw.toIntOrNull() ?: bad("bad_$name", "$name must be an integer")
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
