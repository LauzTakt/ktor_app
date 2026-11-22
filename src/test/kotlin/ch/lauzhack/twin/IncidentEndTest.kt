package ch.lauzhack.twin

import ch.lauzhack.twin.common.ZURICH
import ch.lauzhack.twin.query.IncidentEnds
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IncidentEndTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZURICH).toInstant().toEpochMilli()
    private val ref = at(2026, 11, 22, 11, 40)

    @Test
    fun atLeast() = assertEquals(
        at(2026, 11, 22, 12, 25) to "at_least",
        IncidentEnds.parse("Interrupted.\nThe restriction lasts at least until 12:25.\nLines IC1 are affected.", ref)!!.let { it.at to it.kind },
    )

    @Test
    fun approxPastMidnight() = assertEquals(
        at(2026, 11, 23, 3, 31) to "approx",
        IncidentEnds.parse("The restriction lasts until approx. 03:31.", at(2026, 11, 22, 23, 50))!!.let { it.at to it.kind },
    )

    @Test
    fun withDate() = assertEquals(at(2026, 11, 24, 5, 0), IncidentEnds.parse("The restriction lasts until 24.11.2026 05:00.", ref)?.at)

    @Test
    fun none() {
        assertNull(IncidentEnds.parse("RE8 to Thusis", ref))
        assertNull(IncidentEnds.parse("", ref))
    }
}
