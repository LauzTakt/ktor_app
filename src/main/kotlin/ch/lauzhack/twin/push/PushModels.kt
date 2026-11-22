package ch.lauzhack.twin.push

import kotlinx.serialization.Serializable

/* Request / response types of `/api/v1/push/...` (contract: docs/API.md, "Push notifications"). */

@Serializable
data class PushConfigDto(
    /** VAPID public key (base64url) for `PushManager.subscribe({ applicationServerKey })`. */
    val webPushPublicKey: String,
    /** True when native app tokens (FCM) are accepted. */
    val fcm: Boolean,
    /** "Arriving soon" is sent this many seconds before the expected arrival. */
    val arrivalLeadSec: Int,
)

@Serializable
data class WebPushKeys(val p256dh: String, val auth: String)

@Serializable
data class RegisterDeviceRequest(
    /** `web` (browser PushSubscription) | `fcm` (native app token). */
    val kind: String,
    /** web: `PushSubscription.endpoint` + `keys` (as in `subscription.toJSON()`). */
    val endpoint: String? = null,
    val keys: WebPushKeys? = null,
    /** fcm: registration token from the app. */
    val token: String? = null,
    /** Free text: `web`, `android`, `ios`. */
    val platform: String? = null,
)

@Serializable
data class DeviceDto(val deviceId: String, val kind: String, val platform: String?, val createdAt: Long)

/** A later train of a trip with changes: board it at [fromStopId], get off at [toStopId]. */
@Serializable
data class TripLeg(
    val trainId: String,
    val line: String? = null,
    val headsign: String? = null,
    val fromStopId: String,
    val fromName: String? = null,
    val toStopId: String,
    val toName: String? = null,
    /** Planned / expected departure from [fromStopId] (epoch ms), for the "change" text and how long to wait for it. */
    val departure: Long? = null,
)

@Serializable
data class FollowRequest(
    /** Follow a train: its trainId, plus optionally the station where you get off (arrival alerts). */
    val trainId: String? = null,
    val stopId: String? = null,
    /** Or follow a whole line (incidents only), e.g. `IC 1`. */
    val line: String? = null,
    /**
     * Trip with changes: the next trains, in order. [stopId] is then the first change station: a "change at X" alert
     * once the train has left the stop before it, then the subscription moves on to the next train by itself.
     */
    val then: List<TripLeg> = emptyList(),
)

@Serializable
data class PushSubscriptionDto(
    val id: String,
    /** `train` | `line`. */
    val kind: String,
    val trainId: String?,
    val line: String?,
    val stopId: String?,
    val stopName: String?,
    /** e.g. `IC 1 to Genève-Aéroport` or `Line IC 1`. */
    val label: String,
    /** Which alerts it gets: `arrival` (train with a station), `incidents`. */
    val alerts: List<String>,
    val createdAt: Long,
    /** The trains still to come on a trip with changes (empty otherwise). */
    val then: List<TripLeg> = emptyList(),
)

@Serializable
data class TestResultDto(val result: String)

internal fun PushSubscription.toDto() = PushSubscriptionDto(
    id = id, kind = kind,
    trainId = target.takeIf { kind == "train" }, line = target.takeIf { kind == "line" },
    stopId = stopId, stopName = stopName, label = label ?: target,
    alerts = if (stopId != null) listOf("arrival", "incidents") else listOf("incidents"),
    createdAt = createdAt,
    then = then,
)

internal fun PushDevice.toDto() = DeviceDto(id, kind, platform, createdAt)
