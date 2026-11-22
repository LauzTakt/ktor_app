package ch.lauzhack.twin.wallet

/** The resolved next step for one rider's selected plan. */
internal sealed interface TripWatchAction {
    data object WaitForService : TripWatchAction
    data object CheckTrip : TripWatchAction

    /** Use a service-date-scoped ID and published departure in UTC epoch milliseconds. */
    data class Board(
        val serviceId: String,
        val boardingStopId: String,
        val alightingStopId: String,
        val publishedDepartureAt: Long? = null,
        val boardingPointId: String? = null,
    ) : TripWatchAction

    data class Alight(val serviceId: String, val stopId: String) : TripWatchAction
}

/** Detail can include forecast times or an explanation that can change between instructions. */
internal data class TripWatchAdvice(val action: TripWatchAction, val detail: String? = null)

internal enum class TripWatchDelivery {
    SKIP,
    SYNC_SILENTLY,
    NOTIFY,
}

/** Compare advice for the same pass after the watch service resolves the rider's plan. */
internal fun classifyTripWatchUpdate(
    previous: TripWatchAdvice?,
    next: TripWatchAdvice,
): TripWatchDelivery = when {
    previous == next -> TripWatchDelivery.SKIP
    previous == null || previous.action == next.action -> TripWatchDelivery.SYNC_SILENTLY
    else -> TripWatchDelivery.NOTIFY
}
