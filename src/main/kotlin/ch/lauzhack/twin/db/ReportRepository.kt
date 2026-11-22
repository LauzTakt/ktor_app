package ch.lauzhack.twin.db

import org.slf4j.LoggerFactory
import java.sql.ResultSet

/** `staff_report`: incidents reported by staff through our web UI (synchronous: the caller needs the result). */
class ReportRepository(private val db: Sqlite) {
    private val log = LoggerFactory.getLogger(ReportRepository::class.java)

    init {
        db.migrate(
            """
            CREATE TABLE IF NOT EXISTS staff_report (   -- incidents reported by staff through our web UI
              id            TEXT PRIMARY KEY,           -- r-xxxxxxxx
              title         TEXT NOT NULL,
              description   TEXT NOT NULL DEFAULT '',
              category      TEXT NOT NULL,              -- see ReportCategory
              severity      TEXT NOT NULL,              -- info | minor | major | critical
              scope_kind    TEXT NOT NULL,              -- station | section | train | point
              stop_id       TEXT,                       -- station, or section start
              to_stop_id    TEXT,                       -- section end
              train_id      TEXT,
              line          TEXT,                       -- line of the train when reported
              where_text    TEXT NOT NULL,              -- human-readable location
              lat           REAL,
              lon           REAL,
              reporter      TEXT,
              status        TEXT NOT NULL,              -- open | resolved
              created_at    INTEGER NOT NULL,           -- epoch ms
              updated_at    INTEGER NOT NULL,
              resolved_at   INTEGER,
              resolution    TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS ix_staff_report_status ON staff_report(status, created_at)",
        )
    }

    fun insert(r: StaffReportRow) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """INSERT INTO staff_report(id, title, description, category, severity, scope_kind, stop_id, to_stop_id, train_id,
                     line, where_text, lat, lon, reporter, status, created_at, updated_at, resolved_at, resolution)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, r.id); ps.setString(2, r.title); ps.setString(3, r.description); ps.setString(4, r.category)
                ps.setString(5, r.severity); ps.setString(6, r.scopeKind); ps.setString(7, r.stopId); ps.setString(8, r.toStopId)
                ps.setString(9, r.trainId); ps.setString(10, r.line); ps.setString(11, r.where); ps.setObject(12, r.lat)
                ps.setObject(13, r.lon); ps.setString(14, r.reporter); ps.setString(15, r.status); ps.setLong(16, r.createdAt)
                ps.setLong(17, r.updatedAt); ps.setObject(18, r.resolvedAt); ps.setString(19, r.resolution)
                ps.executeUpdate()
            }
        }
        log.info("staff report {} created: {}", r.id, r.title)
    }

    /** Marks an open report resolved; returns false if it does not exist or is already resolved. */
    fun resolve(id: String, at: Long, resolution: String?): Boolean = db.withConnection { conn ->
        conn.prepareStatement("UPDATE staff_report SET status='resolved', resolved_at=?, updated_at=?, resolution=? WHERE id=? AND status='open'").use { ps ->
            ps.setLong(1, at); ps.setLong(2, at); ps.setString(3, resolution); ps.setString(4, id)
            ps.executeUpdate() > 0
        }
    }

    /** [status] null = all. Newest first. */
    fun list(status: String?, limit: Int = 500): List<StaffReportRow> {
        val n = limit.coerceIn(1, 5000)
        return if (status == null) db.query("SELECT * FROM staff_report ORDER BY created_at DESC LIMIT $n") { it.toReportRow() }
        else db.query("SELECT * FROM staff_report WHERE status=? ORDER BY created_at DESC LIMIT $n", status) { it.toReportRow() }
    }

    fun get(id: String): StaffReportRow? = db.query("SELECT * FROM staff_report WHERE id=?", id) { it.toReportRow() }.firstOrNull()

    private fun ResultSet.toReportRow() = StaffReportRow(
        id = getString("id"), title = getString("title"), description = getString("description"),
        category = getString("category"), severity = getString("severity"), scopeKind = getString("scope_kind"),
        stopId = getString("stop_id"), toStopId = getString("to_stop_id"), trainId = getString("train_id"),
        line = getString("line"), where = getString("where_text"), lat = doubleOrNull("lat"), lon = doubleOrNull("lon"),
        reporter = getString("reporter"), status = getString("status"), createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"), resolvedAt = longOrNull("resolved_at"), resolution = getString("resolution"),
    )
}

/** One row of `staff_report`: an incident reported by staff through our web UI (not published to the twin). */
data class StaffReportRow(
    val id: String, val title: String, val description: String, val category: String, val severity: String,
    val scopeKind: String, val stopId: String?, val toStopId: String?, val trainId: String?, val line: String?,
    val where: String, val lat: Double?, val lon: Double?, val reporter: String?, val status: String,
    val createdAt: Long, val updatedAt: Long, val resolvedAt: Long?, val resolution: String?,
)
