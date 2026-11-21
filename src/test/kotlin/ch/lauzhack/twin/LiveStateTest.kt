package ch.lauzhack.twin

import ch.lauzhack.twin.state.LiveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveStateTest {
    @Test
    fun nextCallIndexUsesExpectedSuffix() {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 2))
        assertEquals(2, st.train("t1")!!.nextCallIndex)
    }

    @Test
    fun standingAtStationWinsOverLaggingInfo() {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 2)) // info already says B is behind...
        st.replacePositions(mapOf("t1" to pos("t1", "B")), T0) // ...but the train is still at B
        assertEquals(1, st.train("t1")!!.nextCallIndex)
    }

    @Test
    fun departureSeenInFleetCorrectsLaggingInfo() {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 1)) // info still expects B
        st.replacePositions(mapOf("t1" to pos("t1", "B")), T0)
        st.replacePositions(mapOf("t1" to pos("t1", null)), T0 + 1000) // left B
        assertEquals(2, st.train("t1")!!.nextCallIndex)
    }

    @Test
    fun stationIndexFollowsInfoUpdatesAndTombstones() {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 0, stops = listOf("A", "B")))
        st.upsertInfo(info("t1", aheadFrom = 0, stops = listOf("A", "C"))) // re-routed
        assertTrue(st.trainsCallingAt("B").isEmpty())
        assertEquals(1, st.trainsCallingAt("C").size)
        st.removeInfo("t1")
        assertTrue(st.trainsCallingAt("C").isEmpty())
    }

    @Test
    fun newStationsReportedOnce() {
        val st = LiveState()
        assertEquals(4, st.upsertInfo(info("t1", 0)).size)
        assertEquals(0, st.upsertInfo(info("t2", 0)).size)
    }
}
