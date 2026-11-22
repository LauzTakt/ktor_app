package ch.lauzhack.twin.query

import ch.lauzhack.twin.common.notFound
import ch.lauzhack.twin.db.Database

/** Incident list, detail and history (SQLite), for `GET /api/v1/incidents...`. */
class IncidentQueries(private val db: Database) {

    /** Active incidents (or every one with [all]), newest first, optionally filtered by severity / scope kind. */
    fun list(all: Boolean, severity: String?, scopeKind: String?, withGeometry: Boolean): List<IncidentDto> =
        db.incidents(activeOnly = !all, withGeometry = withGeometry)
            .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
            .filter { scopeKind == null || it.scope?.kind.equals(scopeKind, ignoreCase = true) }
            .map { it.toDto() }

    fun incident(id: String): IncidentDto = db.incident(id)?.toDto() ?: throw notFound("incident", id)

    fun history(id: String): List<IncidentEventDto> {
        if (db.incident(id) == null) throw notFound("incident", id)
        return db.incidentHistory(id).map { it.toDto() }
    }
}
