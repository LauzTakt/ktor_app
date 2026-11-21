package ch.lauzhack.twin.db

import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.state.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SQLite persistence. Only low-volume data lives here: the incident history and the station catalog.
 * Train state is deliberately NOT persisted (see LiveState).
 *
 * One connection, one writer thread: every write is queued on [writer] so MQTT callbacks never block on disk.
 * Reads use the same connection under a lock (SQLite in WAL mode; volumes are tiny).
 */
class Database(path: String) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Database::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val conn: Connection
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sqlite-writer").apply { isDaemon = true } }

    /**
     * Called on the writer thread after every incident change that was actually recorded (same rule as history:
     * `created | updated | removed | missing`; re-delivered retained copies are not reported). Must not block.
     */
    private val incidentListeners = java.util.concurrent.CopyOnWriteArrayList<(kind: String, incident: IncidentRow) -> Unit>()

    fun addIncidentListener(l: (kind: String, incident: IncidentRow) -> Unit) { incidentListeners += l }

    private fun notifyIncident(kind: String, id: String) {
        if (incidentListeners.isEmpty()) return
        val row = conn.prepareStatement("SELECT * FROM incident WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.toIncidentRow() else null }
        } ?: return
        incidentListeners.forEach { l -> runCatching { l(kind, row) }.onFailure { log.warn("incident listener failed", it) } }
    }

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
        log.info("SQLite ready at {}", File(path).absolutePath)
    }

    private fun migrate() = synchronized(lock) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS station (
                  stop_id       TEXT PRIMARY KEY,           -- UIC/DIDOK id, e.g. 8503000 (Zürich HB)
                  name          TEXT NOT NULL,
                  lat           REAL,
                  lon           REAL,
                  first_seen_at INTEGER NOT NULL,           -- epoch ms
                  updated_at    INTEGER NOT NULL
                )""",
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS incident (       -- current/last known version of each incident
                  id            TEXT PRIMARY KEY,           -- e.g. i-4d5811f3
                  title         TEXT NOT NULL,
                  description   TEXT NOT NULL DEFAULT '',
                  severity      TEXT,                       -- info | minor | major | critical
                  type          TEXT,                       -- overhead_line_fault, station_fire, ...
                  reason_code   TEXT,                       -- SBB reason, e.g. EG_008
                  scope_kind    TEXT,                       -- section | station | train | line | network
                  scope_from    TEXT,                       -- stop_id (section) or the station (station scope)
                  scope_to      TEXT,                       -- stop_id (section)
                  scope_train   TEXT,                       -- trainId (train scope)
                  scope_json    TEXT,                       -- raw scope, in case of unknown kinds
                  where_text    TEXT,
                  disruption_id TEXT,
                  geometry_json TEXT,                       -- GeoJSON geometry
                  status        TEXT NOT NULL,
                  created_at    INTEGER NOT NULL,           -- from payload (epoch ms)
                  updated_at    INTEGER NOT NULL,           -- from payload (epoch ms)
                  first_seen_at INTEGER NOT NULL,           -- when we first received it
                  removed_at    INTEGER                     -- tombstone received (or found missing); NULL = active
                )""",
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS incident_event ( -- append-only history, one row per change
                  seq           INTEGER PRIMARY KEY AUTOINCREMENT,
                  incident_id   TEXT NOT NULL REFERENCES incident(id),
                  kind          TEXT NOT NULL,              -- created | updated | removed | missing
                  received_at   INTEGER NOT NULL,           -- epoch ms
                  updated_at    INTEGER,                    -- payload updatedAt at that moment
                  status        TEXT,
                  severity      TEXT,
                  payload_json  TEXT                        -- full payload (NULL for removed/missing)
                )""",
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_incident_event_incident ON incident_event(incident_id, seq)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_incident_scope_from ON incident(scope_from)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_incident_scope_to ON incident(scope_to)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_incident_active ON incident(removed_at)")
            st.executeUpdate(
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
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_staff_report_status ON staff_report(status, created_at)")
        }
    }

    // ---------------------------------------------------------------- writes (async, single thread)

    fun saveStations(stations: List<Station>) {
        if (stations.isEmpty()) return
        submit {
            val now = System.currentTimeMillis()
            conn.prepareStatement(
                """INSERT INTO station(stop_id, name, lat, lon, first_seen_at, updated_at) VALUES (?,?,?,?,?,?)
                   ON CONFLICT(stop_id) DO UPDATE SET name=excluded.name, lat=excluded.lat, lon=excluded.lon,
                   updated_at=excluded.updated_at
                   WHERE name IS NOT excluded.name OR lat IS NOT excluded.lat OR lon IS NOT excluded.lon""",
            ).use { ps ->
                for (s in stations) {
                    ps.setString(1, s.stopId); ps.setString(2, s.name)
                    ps.setObject(3, s.lat); ps.setObject(4, s.lon)
                    ps.setLong(5, now); ps.setLong(6, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Insert or update an incident; records a history row only when something actually changed. */
    fun upsertIncident(inc: Incident, raw: String) = submit {
        val now = System.currentTimeMillis()
        val prev = conn.prepareStatement("SELECT updated_at, status, removed_at FROM incident WHERE id=?").use { ps ->
            ps.setString(1, inc.id)
            ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getLong(1), rs.getString(2), rs.longOrNull("removed_at")) else null }
        }
        val kind = when {
            prev == null -> "created"
            prev.first != inc.updatedAt || prev.second != inc.status || prev.third != null -> "updated"
            else -> return@submit // retained copy re-delivered on reconnect: nothing new
        }
        val scope = inc.scope
        val scopeFrom = scope?.from ?: scope?.stopId
        conn.prepareStatement(
            """INSERT INTO incident(id, title, description, severity, type, reason_code, scope_kind, scope_from, scope_to,
                 scope_train, scope_json, where_text, disruption_id, geometry_json, status, created_at, updated_at,
                 first_seen_at, removed_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)
               ON CONFLICT(id) DO UPDATE SET title=excluded.title, description=excluded.description,
                 severity=excluded.severity, type=excluded.type, reason_code=excluded.reason_code,
                 scope_kind=excluded.scope_kind, scope_from=excluded.scope_from, scope_to=excluded.scope_to,
                 scope_train=excluded.scope_train, scope_json=excluded.scope_json, where_text=excluded.where_text,
                 disruption_id=excluded.disruption_id, geometry_json=excluded.geometry_json, status=excluded.status,
                 created_at=excluded.created_at, updated_at=excluded.updated_at, removed_at=NULL""",
        ).use { ps ->
            ps.setString(1, inc.id); ps.setString(2, inc.title); ps.setString(3, inc.description)
            ps.setString(4, inc.severity); ps.setString(5, inc.type); ps.setString(6, inc.reasonCode)
            ps.setString(7, scope?.kind); ps.setString(8, scopeFrom); ps.setString(9, scope?.to)
            ps.setString(10, scope?.trainId); ps.setString(11, scope?.let { json.encodeToString(it) })
            ps.setString(12, inc.where); ps.setString(13, inc.disruptionId)
            ps.setString(14, inc.geometry?.toString()); ps.setString(15, inc.status)
            ps.setLong(16, inc.createdAt); ps.setLong(17, inc.updatedAt); ps.setLong(18, now)
            ps.executeUpdate()
        }
        insertEvent(inc.id, kind, now, inc.updatedAt, inc.status, inc.severity, raw)
        log.info("incident {} {}: {}", inc.id, kind, inc.title)
        notifyIncident(kind, inc.id)
    }

    /** Tombstone received (`kind = removed`) or incident absent from the retained set after reconnect (`missing`). */
    fun removeIncident(id: String, kind: String = "removed") = submit { markRemoved(id, kind) }

    /** After a reconnect: every incident active in the DB that the broker no longer retains was unmarked while we were away. */
    fun reconcileActiveIncidents(stillRetained: Set<String>) = submit {
        val active = conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM incident WHERE removed_at IS NULL").use { rs -> rs.strings() }
        }
        (active - stillRetained).forEach { markRemoved(it, "missing") }
    }

    private fun markRemoved(id: String, kind: String) {
        val now = System.currentTimeMillis()
        val n = conn.prepareStatement("UPDATE incident SET removed_at=? WHERE id=? AND removed_at IS NULL").use { ps ->
            ps.setLong(1, now); ps.setString(2, id); ps.executeUpdate()
        }
        if (n > 0) {
            insertEvent(id, kind, now, null, null, null, null)
            log.info("incident {} {}", id, kind)
            notifyIncident(kind, id)
        }
    }

    private fun insertEvent(id: String, kind: String, at: Long, updatedAt: Long?, status: String?, severity: String?, raw: String?) {
        conn.prepareStatement(
            "INSERT INTO incident_event(incident_id, kind, received_at, updated_at, status, severity, payload_json) VALUES (?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, kind); ps.setLong(3, at)
            ps.setObject(4, updatedAt); ps.setString(5, status); ps.setString(6, severity); ps.setString(7, raw)
            ps.executeUpdate()
        }
    }

    private fun submit(block: () -> Unit) {
        writer.execute {
            try {
                synchronized(lock) { block() }
            } catch (e: Exception) {
                log.error("SQLite write failed", e)
            }
        }
    }

    // ---------------------------------------------------------------- reads

    fun incidents(activeOnly: Boolean, withGeometry: Boolean = false): List<IncidentRow> = query(
        "SELECT * FROM incident" + (if (activeOnly) " WHERE removed_at IS NULL" else "") + " ORDER BY updated_at DESC",
    ) { it.toIncidentRow(withGeometry) }

    /** Incidents whose scope touches a station (section endpoint or the station itself). */
    fun incidentsAtStation(stopId: String, activeOnly: Boolean): List<IncidentRow> = query(
        "SELECT * FROM incident WHERE (scope_from=? OR scope_to=?)" +
            (if (activeOnly) " AND removed_at IS NULL" else "") + " ORDER BY updated_at DESC",
        stopId, stopId,
    ) { it.toIncidentRow() }

    fun incidentHistory(id: String): List<IncidentEventRow> = query(
        "SELECT seq, incident_id, kind, received_at, updated_at, status, severity FROM incident_event WHERE incident_id=? ORDER BY seq",
        id,
    ) {
        IncidentEventRow(
            it.getLong("seq"), it.getString("incident_id"), it.getString("kind"), it.getLong("received_at"),
            it.longOrNull("updated_at"), it.getString("status"), it.getString("severity"),
        )
    }

    private fun <T> query(sql: String, vararg args: String, map: (ResultSet) -> T): List<T> = synchronized(lock) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }

    private fun ResultSet.strings(): Set<String> = buildSet { while (next()) add(getString(1)) }

    fun incident(id: String): IncidentRow? = query("SELECT * FROM incident WHERE id=?", id) { it.toIncidentRow(withGeometry = true) }.firstOrNull()

    private fun ResultSet.toIncidentRow(withGeometry: Boolean = false) = IncidentRow(
        id = getString("id"), title = getString("title"), description = getString("description"),
        severity = getString("severity"), type = getString("type"), reasonCode = getString("reason_code"),
        scope = getString("scope_json")?.let { runCatching { json.decodeFromString<IncidentScope>(it) }.getOrNull() }
            ?: getString("scope_kind")?.let { IncidentScope(it, getString("scope_from"), getString("scope_to"), trainId = getString("scope_train")) },
        where = getString("where_text"), disruptionId = getString("disruption_id"), status = getString("status"),
        createdAt = getLong("created_at"), updatedAt = getLong("updated_at"), firstSeenAt = getLong("first_seen_at"),
        removedAt = longOrNull("removed_at"),
        geometry = if (withGeometry) getString("geometry_json")?.let { json.parseToJsonElement(it) } else null,
    )

    // ---------------------------------------------------------------- staff reports (synchronous: the caller needs the result)

    fun insertReport(r: StaffReportRow) = synchronized(lock) {
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
        log.info("staff report {} created: {}", r.id, r.title)
    }

    /** Marks an open report resolved; returns false if it does not exist or is already resolved. */
    fun resolveReport(id: String, at: Long, resolution: String?): Boolean = synchronized(lock) {
        conn.prepareStatement("UPDATE staff_report SET status='resolved', resolved_at=?, updated_at=?, resolution=? WHERE id=? AND status='open'").use { ps ->
            ps.setLong(1, at); ps.setLong(2, at); ps.setString(3, resolution); ps.setString(4, id)
            ps.executeUpdate() > 0
        }
    }

    /** [status] null = all. Newest first. */
    fun reports(status: String?, limit: Int = 500): List<StaffReportRow> =
        if (status == null) query("SELECT * FROM staff_report ORDER BY created_at DESC LIMIT $limit") { it.toReportRow() }
        else query("SELECT * FROM staff_report WHERE status=? ORDER BY created_at DESC LIMIT $limit", status) { it.toReportRow() }

    fun report(id: String): StaffReportRow? = query("SELECT * FROM staff_report WHERE id=?", id) { it.toReportRow() }.firstOrNull()

    private fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).takeUnless { wasNull() }

    private fun ResultSet.toReportRow() = StaffReportRow(
        id = getString("id"), title = getString("title"), description = getString("description"),
        category = getString("category"), severity = getString("severity"), scopeKind = getString("scope_kind"),
        stopId = getString("stop_id"), toStopId = getString("to_stop_id"), trainId = getString("train_id"),
        line = getString("line"), where = getString("where_text"), lat = doubleOrNull("lat"), lon = doubleOrNull("lon"),
        reporter = getString("reporter"), status = getString("status"), createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"), resolvedAt = longOrNull("resolved_at"), resolution = getString("resolution"),
    )

    override fun close() {
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.SECONDS)
        synchronized(lock) { conn.close() }
    }
}

@Serializable
data class IncidentRow(
    val id: String, val title: String, val description: String, val severity: String?, val type: String?,
    val reasonCode: String?, val scope: IncidentScope?, val where: String?, val disruptionId: String?,
    val status: String, val createdAt: Long, val updatedAt: Long, val firstSeenAt: Long, val removedAt: Long?,
    /** GeoJSON; only filled by [Database.incident] (single-incident reads) to keep lists small. */
    val geometry: JsonElement? = null,
) {
    val active: Boolean get() = removedAt == null
}

@Serializable
data class IncidentEventRow(
    val seq: Long, val incidentId: String, val kind: String, val receivedAt: Long, val updatedAt: Long?,
    val status: String?, val severity: String?,
)

/** One row of `staff_report`: an incident reported by staff through our web UI (not published to the twin). */
data class StaffReportRow(
    val id: String, val title: String, val description: String, val category: String, val severity: String,
    val scopeKind: String, val stopId: String?, val toStopId: String?, val trainId: String?, val line: String?,
    val where: String, val lat: Double?, val lon: Double?, val reporter: String?, val status: String,
    val createdAt: Long, val updatedAt: Long, val resolvedAt: Long?, val resolution: String?,
)
