package ch.lauzhack.twin.report

import ch.lauzhack.twin.common.ConflictException
import ch.lauzhack.twin.common.invalid
import ch.lauzhack.twin.common.notFound
import ch.lauzhack.twin.db.ReportRepository
import ch.lauzhack.twin.db.StaffReportRow
import ch.lauzhack.twin.query.Severity
import ch.lauzhack.twin.query.line
import ch.lauzhack.twin.state.LiveState
import java.util.Locale
import java.util.UUID

/*
 * Staff incident reports: railway staff report what they see (web UI `/admin/report.html`). Stored in our SQLite
 * only; nothing is published to the twin (the twin's own incidents stay read-only for us).
 */

enum class ReportCategory { rolling_stock, infrastructure, signalling, station, person, obstacle, weather, other }
enum class ReportScope { station, section, train, point }

/** What staff send to create a report (validated by [ReportService.create]). */
data class NewReport(
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

/** Staff reports: validation, location from the live state, the open -> resolved flow. No HTTP in here. */
class ReportService(
    private val repo: ReportRepository,
    private val state: LiveState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "r-" + UUID.randomUUID().toString().take(8) },
) {
    /** [status] `open`, `resolved` or null = all. Newest first. */
    fun list(status: String?, limit: Int = 500): List<StaffReportRow> = repo.list(status, limit)

    fun open(limit: Int = 200): List<StaffReportRow> = repo.list("open", limit)

    fun get(id: String): StaffReportRow = repo.get(id) ?: throw notFound("report", id)

    fun create(req: NewReport): StaffReportRow = build(req, state, clock(), newId).also { repo.insert(it) }

    /** Resolves an open report; `409 already_resolved` if it was resolved before (checked atomically). */
    fun resolve(id: String, resolution: String?): StaffReportRow {
        val text = resolution?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
        if (!repo.resolve(id, clock(), text)) {
            get(id) // 404 when it does not exist
            throw ConflictException("already_resolved", "report '$id' is already resolved")
        }
        return get(id)
    }

    companion object {
        private fun bad(code: String, msg: String): Nothing = invalid(code, msg)

        /** Validates a report and fills in its location from the live state. Pure apart from [now]/[newId]. */
        fun build(req: NewReport, state: LiveState, now: Long, newId: () -> String): StaffReportRow {
            val title = req.title.trim().takeIf { it.isNotEmpty() } ?: bad("bad_title", "title is required")
            if (title.length > 200) bad("bad_title", "title must be at most 200 characters")
            if (req.description.length > 4000) bad("bad_description", "description must be at most 4000 characters")
            val category = ReportCategory.entries.firstOrNull { it.name == req.category }?.name
                ?: bad("bad_category", "category must be one of ${ReportCategory.entries.joinToString { it.name }}")
            val severity = Severity.of(req.severity)?.id
                ?: bad("bad_severity", "severity must be one of ${Severity.IDS.reversed().joinToString()}")
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
                    line = t.line
                    val number = t.info?.number?.let { " $it" } ?: ""
                    val to = t.info?.destination?.name?.let { " → $it" } ?: ""
                    where = "${line ?: "Train"}$number$to"
                    lat = req.lat ?: t.position?.lat
                    lon = req.lon ?: t.position?.lon
                }
                ReportScope.point -> {
                    lat = req.lat ?: bad("bad_lat", "lat is required for scope point")
                    lon = req.lon ?: bad("bad_lon", "lon is required for scope point")
                    where = "%.5f, %.5f".format(Locale.ROOT, lat, lon)
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
    }
}
