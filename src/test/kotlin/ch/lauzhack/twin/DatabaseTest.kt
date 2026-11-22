package ch.lauzhack.twin

import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DatabaseTest {
    private fun inc(updatedAt: Long, severity: String = "major") = Incident(
        id = "i-1", title = "Fault", severity = severity, updatedAt = updatedAt, createdAt = 1,
        scope = IncidentScope(kind = "section", from = "8503000", to = "8516218"),
    )

    @Test
    fun incidentLifecycleIsRecordedOncePerChange() {
        val db = testDb()
        db.upsertIncident(inc(10), "{}")
        db.upsertIncident(inc(10), "{}") // retained re-delivery: ignored
        db.upsertIncident(inc(20, "critical"), "{}")
        db.removeIncident("i-1")
        db.removeIncident("i-1") // duplicate tombstone: ignored

        val history = eventually({ db.incidentHistory("i-1") }) { it.size >= 3 }
        assertEquals(listOf("created", "updated", "removed"), history.map { it.kind })
        assertEquals(0, db.incidents(activeOnly = true).size)
        assertNotNull(db.incidents(activeOnly = false).single().removedAt)
        assertEquals("8516218", db.incidents(activeOnly = false).single().scope?.to)

        db.upsertIncident(inc(30), "{}") // re-marked
        val active = eventually({ db.incidents(activeOnly = true) }) { it.isNotEmpty() }
        assertNull(active.single().removedAt)
        db.close()
    }

    @Test
    fun reconcileMarksMissingIncidents() {
        val db = testDb()
        db.upsertIncident(inc(10), "{}")
        db.reconcileActiveIncidents(emptySet())
        val h = eventually({ db.incidentHistory("i-1") }) { it.size >= 2 }
        assertEquals("missing", h.last().kind)
        db.close()
    }
}
