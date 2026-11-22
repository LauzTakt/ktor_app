package ch.lauzhack.twin.push

import ch.lauzhack.twin.common.invalid
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

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
                    r.trainId != null -> push.followTrain(id, r.trainId, r.stopId?.takeIf { it.isNotBlank() }, r.then)
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

private fun bad(code: String, msg: String): Nothing = invalid(code, msg)
