package ch.lauzhack.twin.push

import ch.lauzhack.twin.api.v1.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- contract (docs/API.md, "Push notifications")

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

@Serializable
data class FollowRequest(
    /** Follow a train: its trainId, plus optionally the station where you get off (arrival alerts). */
    val trainId: String? = null,
    val stopId: String? = null,
    /** Or follow a whole line (incidents only), e.g. `IC 1`. */
    val line: String? = null,
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
)

@Serializable
data class TestResultDto(val result: String)

internal fun PushSubscription.toDto() = PushSubscriptionDto(
    id = id, kind = kind,
    trainId = target.takeIf { kind == "train" }, line = target.takeIf { kind == "line" },
    stopId = stopId, stopName = stopName, label = label ?: target,
    alerts = if (stopId != null) listOf("arrival", "incidents") else listOf("incidents"),
    createdAt = createdAt,
)

private fun PushDevice.toDto() = DeviceDto(id, kind, platform, createdAt)

// ---------------------------------------------------------------- routes

/**
 * `/api/v1/push/...`: public (passengers have no account). The random `deviceId` returned on registration is the
 * device's handle: only who knows it can list or change its subscriptions.
 */
fun Route.pushV1(push: PushService) = route("/api/v1/push") {
    get("/config") { call.respond(PushConfigDto(push.vapid.publicKey, push.fcmEnabled, push.config.arrivalLeadSec)) }

    post("/devices") {
        val r = call.receive<RegisterDeviceRequest>()
        val d = when (r.kind) {
            "web" -> {
                val endpoint = r.endpoint ?: bad("bad_endpoint", "endpoint is required for kind=web")
                val keys = r.keys ?: bad("bad_keys", "keys.p256dh and keys.auth are required for kind=web")
                push.registerWeb(endpoint, keys.p256dh, keys.auth, r.platform)
            }
            "fcm" -> push.registerFcm(r.token ?: bad("bad_token", "token is required for kind=fcm"), r.platform)
            else -> bad("bad_kind", "kind must be web or fcm")
        }
        call.respond(HttpStatusCode.Created, d.toDto())
    }

    route("/devices/{deviceId}") {
        get { call.respond(push.device(call.deviceId()).toDto()) }
        delete { push.deleteDevice(call.deviceId()); call.respond(HttpStatusCode.NoContent) }
        post("/test") { call.respond(TestResultDto(push.test(call.deviceId()).name.lowercase())) }

        route("/subscriptions") {
            get { call.respond(push.subscriptions(call.deviceId()).map { it.toDto() }) }
            post {
                val r = call.receive<FollowRequest>()
                val id = call.deviceId()
                val s = when {
                    r.trainId != null && r.line != null -> bad("bad_request", "give either trainId (+ stopId) or line, not both")
                    r.trainId != null -> push.followTrain(id, r.trainId, r.stopId?.takeIf { it.isNotBlank() })
                    !r.line.isNullOrBlank() -> push.followLine(id, r.line)
                    else -> bad("bad_request", "trainId or line is required")
                }
                call.respond(HttpStatusCode.Created, s.toDto())
            }
            delete("/{subscriptionId}") {
                push.unfollow(call.deviceId(), call.parameters["subscriptionId"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun RoutingCall.deviceId(): String = parameters["deviceId"]!!

private fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, msg)
