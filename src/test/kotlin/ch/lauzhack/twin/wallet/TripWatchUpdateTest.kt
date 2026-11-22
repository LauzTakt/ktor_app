package ch.lauzhack.twin.wallet

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TripWatchUpdateTest {
    private val waiting = TripWatchAdvice(TripWatchAction.WaitForTrain)
    private val tram = TripWatchAdvice(
        TripWatchAction.Board("20260925_tram7_stettbach", "bahnhofstrasse-hb"),
    )
    private val train = TripWatchAction.Board(
        serviceId = "20260925_s9_stettbach",
        boardingStopId = "8503000",
        publishedDepartureAt = Instant.parse("2026-09-25T17:58:00Z").toEpochMilli(),
    )

    @Test
    fun firstAdviceSyncsSilently() {
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(null, tram))
    }

    @Test
    fun reopeningEstimateOnlyUpdatesTheWaitingDetail() {
        val estimate = waiting.copy(detail = "Stops are expected to resume from 20:00.")
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(waiting, estimate))
    }

    @Test
    fun namedTrainChangesTheWaitingRidersAction() {
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(waiting, TripWatchAdvice(train)))
    }

    @Test
    fun repeatedTramAdviceIsSkipped() {
        assertEquals(TripWatchDelivery.SKIP, classifyTripWatchUpdate(tram, tram.copy()))
    }

    @Test
    fun forecastChangesSyncSilently() {
        val before = TripWatchAdvice(train, detail = "Expected departure 19:59.")
        val after = before.copy(detail = "Expected departure 20:00.")
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(before, after))
    }

    @Test
    fun changedBoardingPointRequestsAnAlert() {
        val before = TripWatchAdvice(train.copy(boardingPointId = "platform-41"))
        val after = TripWatchAdvice(train.copy(boardingPointId = "platform-42"))
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(before, after))
    }

    @Test
    fun revisedPublishedDepartureRequestsAnAlert() {
        val rescheduled = train.copy(publishedDepartureAt = Instant.parse("2026-09-25T18:12:00Z").toEpochMilli())
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(TripWatchAdvice(train), TripWatchAdvice(rescheduled)))
    }

    @Test
    fun withdrawnBoardingAdviceRequestsACorrection() {
        val correction = TripWatchAdvice(TripWatchAction.CheckTrip, detail = "The S9 will skip Stettbach.")
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(TripWatchAdvice(train), correction))
    }
}
