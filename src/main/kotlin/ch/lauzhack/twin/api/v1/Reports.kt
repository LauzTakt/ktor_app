package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.auth.staff
import ch.lauzhack.twin.auth.staffOnly
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.db.StaffReportRow
import ch.lauzhack.twin.state.LiveState
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Staff incident reports: railway staff report what they see (web UI `/report.html`). Stored in our SQLite only;
 * nothing is published to the twin (the twin's own incidents stay read-only for us).
 */

enum class ReportCategory { rolling_stock, infrastructure, signalling, station, person, obstacle, weather, other }
enum class ReportScope { station, section, train, point }
private val SEVERITIES = listOf("info", "minor", "major", "critical")

@Serializable
data class CreateReportDto(
    val title: String,
    val description: String = "",
    val category: String = "other",
    val severity: String = "minor",
    /** `station` (stopId), `section` (stopId + toStopId), `train` (trainId) or `point` (lat + lon). */
    val scope: String,
    val stopId: String? = null,
    val toStopId: String? = null,
    val trainId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val reporter: String? = null,
)

@Serializable
data class ResolveReportDto(val resolution: String? = null)

@Serializable
data class ReportDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val severity: String,
    val scope: String,
    val stopId: String?,
    val toStopId: String?,
    val trainId: String?,
    /** Line of the train at report time (train scope). */
    val line: String?,
    /** Human-readable location, e.g. `Bern`, `Bern – Thun`, `IC 1 712 → Genève-Aéroport`. */
    val where: String,
    /** Where to draw it: the station, the section midpoint, the train's position when reported, or the point. */
    val lat: Double?,
    val lon: Double?,
    val reporter: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long?,
    val resolution: String?,
)

fun StaffReportRow.toDto() = ReportDto(
    id, title, description, category, severity, scopeKind, stopId, toStopId, trainId, line, where, lat, lon,
    reporter, status, createdAt, updatedAt, resolvedAt, resolution,
)

private fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, msg)

/** Validates a report and fills in its location from the live state. Pure apart from [now]/[newId]. */
fun buildReport(
    req: CreateReportDto,
    state: LiveState,
    now: Long = System.currentTimeMillis(),
    newId: () -> String = { "r-" + UUID.randomUUID().toString().take(8) },
): StaffReportRow {
    val title = req.title.trim().takeIf { it.isNotEmpty() } ?: bad("bad_title", "title is required")
    if (title.length > 200) bad("bad_title", "title must be at most 200 characters")
    if (req.description.length > 4000) bad("bad_description", "description must be at most 4000 characters")
    val category = ReportCategory.entries.firstOrNull { it.name == req.category }?.name
        ?: bad("bad_category", "category must be one of ${ReportCategory.entries.joinToString { it.name }}")
    val severity = req.severity.takeIf { it in SEVERITIES } ?: bad("bad_severity", "severity must be one of ${SEVERITIES.joinToString()}")
    val scope = ReportScope.entries.firstOrNull { it.name == req.scope }
        ?: bad("bad_scope", "scope must be one of ${ReportScope.entries.joinToString { it.name }}")

    fun station(id: String?, param: String) =
        state.station(id ?: bad("bad_$param", "$param is required for scope ${scope.name}")) ?: throw notFound("station", id)

    var stopId: String? = null
    var toStopId: String? = null
    var trainId: String? = null
    var line: String? = null
    val where: String
    var lat: Double?
    var lon: Double?
    when (scope) {
        ReportScope.station -> {
            val s = station(req.stopId, "stopId")
            stopId = s.stopId; where = s.name; lat = s.lat; lon = s.lon
        }
        ReportScope.section -> {
            val a = station(req.stopId, "stopId")
            val b = station(req.toStopId, "toStopId")
            if (a.stopId == b.stopId) bad("bad_toStopId", "toStopId must differ from stopId")
            stopId = a.stopId; toStopId = b.stopId; where = "${a.name} – ${b.name}"
            lat = if (a.lat != null && b.lat != null) (a.lat + b.lat) / 2 else a.lat ?: b.lat
            lon = if (a.lon != null && b.lon != null) (a.lon + b.lon) / 2 else a.lon ?: b.lon
        }
        ReportScope.train -> {
            val id = req.trainId ?: bad("bad_trainId", "trainId is required for scope train")
            val t = state.train(id) ?: throw notFound("train", id)
            trainId = id
            line = t.info?.line ?: t.position?.line
            val number = t.info?.number?.let { " $it" } ?: ""
            val to = t.info?.destination?.name?.let { " → $it" } ?: ""
            where = "${line ?: "Train"}$number$to"
            lat = req.lat ?: t.position?.lat
            lon = req.lon ?: t.position?.lon
        }
        ReportScope.point -> {
            lat = req.lat ?: bad("bad_lat", "lat is required for scope point")
            lon = req.lon ?: bad("bad_lon", "lon is required for scope point")
            where = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)
        }
    }
    if (lat != null && lat !in -90.0..90.0) bad("bad_lat", "lat must be between -90 and 90")
    if (lon != null && lon !in -180.0..180.0) bad("bad_lon", "lon must be between -180 and 180")

    return StaffReportRow(
        id = newId(), title = title, description = req.description.trim(), category = category, severity = severity,
        scopeKind = scope.name, stopId = stopId, toStopId = toStopId, trainId = trainId, line = line, where = where,
        lat = lat, lon = lon, reporter = req.reporter?.trim()?.takeIf { it.isNotEmpty() }?.take(100), status = "open",
        createdAt = now, updatedAt = now, resolvedAt = null, resolution = null,
    )
}

/** `/api/v1/reports`. Contract: docs/API.md. */
fun Route.reportsV1(state: LiveState, db: Database) = route("/api/v1/reports") {
    get {
        val status = when (val s = call.request.queryParameters["status"] ?: "open") {
            "open", "resolved" -> s
            "all" -> null
            else -> bad("bad_status", "status must be one of open, resolved, all")
        }
        call.respond(db.reports(status).map { it.toDto() })
    }
    // Creating a report is staff-only (Bearer token or the staff session cookie); reading and resolving stay public.
    staffOnly {
        post {
            val req = call.receive<CreateReportDto>()
            val row = buildReport(req.copy(reporter = req.reporter?.takeIf { it.isNotBlank() } ?: call.staff().username), state)
            db.insertReport(row)
            call.respond(HttpStatusCode.Created, row.toDto())
        }
    }
    get("/{id}") {
        val id = call.parameters["id"]!!
        call.respond(db.report(id)?.toDto() ?: throw notFound("report", id))
    }
    post("/{id}/resolve") {
        val id = call.parameters["id"]!!
        val body = runCatching { call.receive<ResolveReportDto>() }.getOrDefault(ResolveReportDto())
        val existing = db.report(id) ?: throw notFound("report", id)
        if (existing.status != "open") throw ApiException(HttpStatusCode.Conflict, "already_resolved", "report '$id' is already resolved")
        db.resolveReport(id, System.currentTimeMillis(), body.resolution?.trim()?.takeIf { it.isNotEmpty() }?.take(1000))
        call.respond(db.report(id)!!.toDto())
    }
}
