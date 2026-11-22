package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.auth.staff
import ch.lauzhack.twin.common.invalid
import ch.lauzhack.twin.auth.staffOnly
import ch.lauzhack.twin.db.StaffReportRow
import ch.lauzhack.twin.report.NewReport
import ch.lauzhack.twin.report.ReportService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/* Staff incident reports over HTTP; the rules live in report/ReportService.kt. */

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

private fun bad(code: String, msg: String): Nothing = invalid(code, msg)

private fun CreateReportDto.toNewReport(reporter: String?) =
    NewReport(title, description, category, severity, scope, stopId, toStopId, trainId, lat, lon, reporter)

/** `/api/v1/reports`. Contract: docs/API.md. */
fun Route.reportsV1(reports: ReportService) = route("/api/v1/reports") {
    get {
        val status = when (val s = call.request.queryParameters["status"] ?: "open") {
            "open", "resolved" -> s
            "all" -> null
            else -> bad("bad_status", "status must be one of open, resolved, all")
        }
        call.respond(reports.list(status).map { it.toDto() })
    }
    // Creating a report is staff-only (Bearer token or the staff session cookie); reading and resolving stay public.
    staffOnly {
        post {
            val req = call.receive<CreateReportDto>()
            val row = reports.create(req.toNewReport(req.reporter?.takeIf { it.isNotBlank() } ?: call.staff().username))
            call.respond(HttpStatusCode.Created, row.toDto())
        }
    }
    get("/{id}") {
        call.respond(reports.get(call.parameters["id"]!!).toDto())
    }
    post("/{id}/resolve") {
        val body = runCatching { call.receive<ResolveReportDto>() }.getOrDefault(ResolveReportDto())
        call.respond(reports.resolve(call.parameters["id"]!!, body.resolution).toDto())
    }
}
