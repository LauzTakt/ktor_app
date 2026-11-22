package ch.lauzhack.twin.db

import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `incident` (current version of each incident, `removed_at` NULL = active) and `incident_event` (append-only
 * history: `created | updated | removed | missing`). Writes are async on the writer thread; listeners hear about every
 * change that was actually recorded.
 */
class IncidentRepository(private val db: Sqlite) {
    private val log = LoggerFactory.getLogger(IncidentRepository::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Called on the writer thread after every incident change that was actually recorded (same rule as history;
     * re-delivered retained copies are not reported). Must not block.
     */
    private val listeners = CopyOnWriteArrayList<(kind: String, incident: IncidentRow) -> Unit>()

    init {
        db.migrate(
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
            "CREATE INDEX IF NOT EXISTS ix_incident_event_incident ON incident_event(incident_id, seq)",
            "CREATE INDEX IF NOT EXISTS ix_incident_scope_from ON incident(scope_from)",
            "CREATE INDEX IF NOT EXISTS ix_incident_scope_to ON incident(scope_to)",
            "CREATE INDEX IF NOT EXISTS ix_incident_active ON incident(removed_at)",
        )
    }

    fun addListener(l: (kind: String, incident: IncidentRow) -> Unit) { listeners += l }

    // ---------------------------------------------------------------- writes (async, single thread)

    /** Insert or update an incident; records a history row only when something actually changed. */
    fun upsert(inc: Incident, raw: String) = db.submit { conn ->
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
        insertEvent(conn, inc.id, kind, now, inc.updatedAt, inc.status, inc.severity, raw)
        log.info("incident {} {}: {}", inc.id, kind, inc.title)
        notify(conn, kind, inc.id)
    }

    /** Tombstone received (`kind = removed`) or incident absent from the retained set after reconnect (`missing`). */
    fun remove(id: String, kind: String = "removed") = db.submit { conn -> markRemoved(conn, id, kind) }

    /** After a reconnect: every incident active in the DB that the broker no longer retains was unmarked while we were away. */
    fun reconcile(stillRetained: Set<String>) = db.submit { conn ->
        val active = conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM incident WHERE removed_at IS NULL").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        (active - stillRetained).forEach { markRemoved(conn, it, "missing") }
    }

    private fun markRemoved(conn: Connection, id: String, kind: String) {
        val now = System.currentTimeMillis()
        val n = conn.prepareStatement("UPDATE incident SET removed_at=? WHERE id=? AND removed_at IS NULL").use { ps ->
            ps.setLong(1, now); ps.setString(2, id); ps.executeUpdate()
        }
        if (n > 0) {
            insertEvent(conn, id, kind, now, null, null, null, null)
            log.info("incident {} {}", id, kind)
            notify(conn, kind, id)
        }
    }

    private fun insertEvent(conn: Connection, id: String, kind: String, at: Long, updatedAt: Long?, status: String?, severity: String?, raw: String?) {
        conn.prepareStatement(
            "INSERT INTO incident_event(incident_id, kind, received_at, updated_at, status, severity, payload_json) VALUES (?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, kind); ps.setLong(3, at)
            ps.setObject(4, updatedAt); ps.setString(5, status); ps.setString(6, severity); ps.setString(7, raw)
            ps.executeUpdate()
        }
    }

    private fun notify(conn: Connection, kind: String, id: String) {
        if (listeners.isEmpty()) return
        val row = conn.prepareStatement("SELECT * FROM incident WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.toIncidentRow() else null }
        } ?: return
        listeners.forEach { l -> runCatching { l(kind, row) }.onFailure { log.warn("incident listener failed", it) } }
    }

    // ---------------------------------------------------------------- reads

    fun list(activeOnly: Boolean, withGeometry: Boolean = false): List<IncidentRow> = db.query(
        "SELECT * FROM incident" + (if (activeOnly) " WHERE removed_at IS NULL" else "") + " ORDER BY updated_at DESC",
    ) { it.toIncidentRow(withGeometry) }

    fun get(id: String): IncidentRow? = db.query("SELECT * FROM incident WHERE id=?", id) { it.toIncidentRow(withGeometry = true) }.firstOrNull()

    fun history(id: String): List<IncidentEventRow> = db.query(
        "SELECT seq, incident_id, kind, received_at, updated_at, status, severity FROM incident_event WHERE incident_id=? ORDER BY seq",
        id,
    ) {
        IncidentEventRow(
            it.getLong("seq"), it.getString("incident_id"), it.getString("kind"), it.getLong("received_at"),
            it.longOrNull("updated_at"), it.getString("status"), it.getString("severity"),
        )
    }

    /**
     * Latest raw MQTT payload of each incident (from its history), for fields the `incident` table does not keep
     * (e.g. `cause`, `effect`, `until`, `replacementBus`). Incident id -> payload JSON.
     */
    fun latestPayloads(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val marks = ids.joinToString(",") { "?" }
        val rows = db.query(
            "SELECT incident_id, payload_json FROM incident_event WHERE payload_json IS NOT NULL AND incident_id IN ($marks) ORDER BY seq",
            *ids.toTypedArray(),
        ) { it.getString(1) to it.getString(2) }
        return rows.toMap() // later rows (higher seq) win
    }

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
}

@Serializable
data class IncidentRow(
    val id: String, val title: String, val description: String, val severity: String?, val type: String?,
    val reasonCode: String?, val scope: IncidentScope?, val where: String?, val disruptionId: String?,
    val status: String, val createdAt: Long, val updatedAt: Long, val firstSeenAt: Long, val removedAt: Long?,
    /** GeoJSON; only filled by [IncidentRepository.get] (single-incident reads) to keep lists small. */
    val geometry: JsonElement? = null,
) {
    val active: Boolean get() = removedAt == null
}

@Serializable
data class IncidentEventRow(
    val seq: Long, val incidentId: String, val kind: String, val receivedAt: Long, val updatedAt: Long?,
    val status: String?, val severity: String?,
)
