package ch.lauzhack.twin.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The resolved next step for one rider's selected plan. */
@Serializable
internal sealed interface TripWatchAction {
    @Serializable
    @SerialName("wait")
    data object WaitForService : TripWatchAction
    @Serializable
    @SerialName("check")
    data object CheckTrip : TripWatchAction

    /** Use a service-date-scoped ID and published departure in UTC epoch milliseconds. */
    @Serializable
    @SerialName("board")
    data class Board(
        val serviceId: String,
        val boardingStopId: String,
        val alightingStopId: String,
        val publishedDepartureAt: Long? = null,
        val boardingPointId: String? = null,
    ) : TripWatchAction

    @Serializable
    @SerialName("alight")
    data class Alight(val serviceId: String, val stopId: String) : TripWatchAction
}

/** Detail can include forecast times or an explanation that can change between instructions. */
@Serializable
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
