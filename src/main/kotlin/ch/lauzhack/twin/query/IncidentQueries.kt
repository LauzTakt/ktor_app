package ch.lauzhack.twin.query

import ch.lauzhack.twin.common.notFound
import ch.lauzhack.twin.db.IncidentRepository

/** Incident list, detail and history (SQLite), for `GET /api/v1/incidents...`. */
class IncidentQueries(private val incidents: IncidentRepository) {

    /** Active incidents (or every one with [all]), newest first, optionally filtered by severity / scope kind. */
    fun list(all: Boolean, severity: String?, scopeKind: String?, withGeometry: Boolean): List<IncidentDto> =
        incidents.list(activeOnly = !all, withGeometry = withGeometry)
            .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
            .filter { scopeKind == null || it.scope?.kind.equals(scopeKind, ignoreCase = true) }
            .map { it.toDto() }

    fun incident(id: String): IncidentDto = incidents.get(id)?.toDto() ?: throw notFound("incident", id)

    fun history(id: String): List<IncidentEventDto> {
        if (incidents.get(id) == null) throw notFound("incident", id)
        return incidents.history(id).map { it.toDto() }
    }

    /** Latest raw MQTT payload of each incident (fields the incident table does not keep). Incident id -> JSON. */
    fun latestPayloads(ids: Collection<String>): Map<String, String> = incidents.latestPayloads(ids)
}
