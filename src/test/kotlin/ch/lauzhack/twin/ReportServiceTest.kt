package ch.lauzhack.twin

import ch.lauzhack.twin.common.ConflictException
import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.report.NewReport
import ch.lauzhack.twin.report.ReportService
import ch.lauzhack.twin.state.LiveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Staff reports without HTTP: validation, location from the live state, open -> resolved once. */
class ReportServiceTest {
    private val state = LiveState().apply { upsertInfo(info("t1", aheadFrom = 0)) }
    private val service = ReportService(testDb().reports, state, clock = { T0 }, newId = { "r-test" })

    @Test
    fun createsWithTheLocationFromLiveState() {
        val r = service.create(NewReport(title = " Signal fault ", scope = "section", stopId = "A", toStopId = "B", severity = "MAJOR"))
        assertEquals("Signal fault", r.title)
        assertEquals("major", r.severity)
        assertEquals("Station A – Station B", r.where)
        assertEquals(listOf("r-test"), service.open().map { it.id })
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<InvalidInputException> { service.create(NewReport(title = "x", scope = "station", stopId = "A", severity = "huge")) }
        assertFailsWith<InvalidInputException> { service.create(NewReport(title = " ", scope = "station", stopId = "A")) }
        assertFailsWith<NotFoundException> { service.create(NewReport(title = "x", scope = "station", stopId = "nowhere")) }
    }

    @Test
    fun resolvesOnlyOnce() {
        service.create(NewReport(title = "Door", scope = "train", trainId = "t1"))
        assertEquals("resolved", service.resolve("r-test", "  fixed  ").status)
        assertEquals("fixed", service.get("r-test").resolution)
        assertFailsWith<ConflictException> { service.resolve("r-test", null) }
        assertFailsWith<NotFoundException> { service.resolve("r-missing", null) }
    }
}
