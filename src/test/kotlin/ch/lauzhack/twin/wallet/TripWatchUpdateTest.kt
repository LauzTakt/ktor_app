package ch.lauzhack.twin.wallet

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TripWatchUpdateTest {
    private val waiting = TripWatchAdvice(TripWatchAction.WaitForService)
    private val rail = TripWatchAction.Board(
        serviceId = "rail:2030-05-06:regional-12",
        boardingStopId = "station-west",
        alightingStopId = "station-central",
        publishedDepartureAt = Instant.parse("2030-05-06T07:10:00Z").toEpochMilli(),
    )
    private val replacementBus = TripWatchAction.Board(
        serviceId = "bus:2030-05-06:replacement-45",
        boardingStopId = "station-west-bus-stop",
        alightingStopId = "station-central",
    )

    @Test
    fun firstAdviceSyncsSilently() {
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(null, TripWatchAdvice(rail)))
    }

    @Test
    fun reopeningEstimateOnlyUpdatesTheWaitingDetail() {
        val estimate = waiting.copy(detail = "Services are expected to resume at 10:30.")
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(waiting, estimate))
    }

    @Test
    fun confirmedServiceChangesTheWaitingInstruction() {
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(waiting, TripWatchAdvice(replacementBus)))
    }

    @Test
    fun repeatedReplacementInstructionsAreSkipped() {
        val advice = TripWatchAdvice(replacementBus)
        assertEquals(TripWatchDelivery.SKIP, classifyTripWatchUpdate(advice, advice.copy()))
    }

    @Test
    fun forecastChangesSyncSilently() {
        val before = TripWatchAdvice(rail, detail = "Expected departure 09:12.")
        val after = before.copy(detail = "Expected departure 09:13.")
        assertEquals(TripWatchDelivery.SYNC_SILENTLY, classifyTripWatchUpdate(before, after))
    }

    @Test
    fun changedBoardingPointRequestsAnAlert() {
        val before = TripWatchAdvice(rail.copy(boardingPointId = "platform-1"))
        val after = TripWatchAdvice(rail.copy(boardingPointId = "platform-2"))
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(before, after))
    }

    @Test
    fun revisedPublishedDepartureRequestsAnAlert() {
        val rescheduled = rail.copy(publishedDepartureAt = Instant.parse("2030-05-06T07:30:00Z").toEpochMilli())
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(TripWatchAdvice(rail), TripWatchAdvice(rescheduled)))
    }

    @Test
    fun withdrawnBoardingAdviceRequestsACorrection() {
        val correction = TripWatchAdvice(TripWatchAction.CheckTrip, detail = "This service skips your stop.")
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(TripWatchAdvice(rail), correction))
    }

    @Test
    fun boardingReplacementServiceRequestsAnAlert() {
        assertEquals(
            TripWatchDelivery.NOTIFY,
            classifyTripWatchUpdate(TripWatchAdvice(rail), TripWatchAdvice(replacementBus)),
        )
    }

    @Test
    fun relocatedReplacementStopRequestsAnAlert() {
        val relocated = replacementBus.copy(boardingStopId = "bus-stop-park")
        assertEquals(
            TripWatchDelivery.NOTIFY,
            classifyTripWatchUpdate(TripWatchAdvice(replacementBus), TripWatchAdvice(relocated)),
        )
    }

    @Test
    fun changedTransferStopOnTheSameServiceRequestsAnAlert() {
        val rerouted = rail.copy(alightingStopId = "station-north")
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(TripWatchAdvice(rail), TripWatchAdvice(rerouted)))
    }

    @Test
    fun changedAlightingStopWhileOnBoardRequestsAnAlert() {
        val before = TripWatchAdvice(TripWatchAction.Alight(rail.serviceId, "station-central"))
        val after = TripWatchAdvice(TripWatchAction.Alight(rail.serviceId, "station-north"))
        assertEquals(TripWatchDelivery.NOTIFY, classifyTripWatchUpdate(before, after))
    }
}
