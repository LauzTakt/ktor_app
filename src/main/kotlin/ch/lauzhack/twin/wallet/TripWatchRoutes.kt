package ch.lauzhack.twin.wallet

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import ch.lauzhack.twin.common.urlEncode

class TripWatchConfig(
    val enabled: Boolean,
    val path: String = "data/wallet.sqlite",
    val site: String = "",
    private val env: (String) -> String? = System::getenv,
) {
    internal fun apple(): AppleWallet? {
        val p12 = env("WALLET_APPLE_P12")?.takeIf(String::isNotBlank) ?: return null
        validateSite()
        return AppleWallet.load(
            required("WALLET_APPLE_PASS_TYPE_ID"), required("WALLET_APPLE_TEAM_ID"), p12,
            env("WALLET_APPLE_P12_PASSWORD").orEmpty(), env("WALLET_APPLE_WWDR_CERT")?.takeIf(String::isNotBlank),
        )
    }

    internal fun google(): GoogleWallet? {
        val classId = env("WALLET_GOOGLE_CLASS_ID")?.takeIf(String::isNotBlank) ?: return null
        validateSite()
        return GoogleWallet.load(classId, required("GOOGLE_APPLICATION_CREDENTIALS"))
    }

    private fun required(name: String) = requireNotNull(env(name)?.takeIf(String::isNotBlank)) { "$name is required" }

    private fun validateSite() {
        val url = URI.create(site)
        require(url.scheme == "https" && url.host != null && url.userInfo == null &&
            url.rawQuery == null && url.rawFragment == null && (url.path.isEmpty() || url.path == "/")
        ) { "Set WALLET_PUBLIC_BASE_URL to the HTTPS origin serving the app" }
    }

    companion object {
        fun fromEnvironment(mainDatabase: String, env: (String) -> String? = System::getenv): TripWatchConfig {
            val enabled = env("TRIP_WATCH_ENABLED")?.takeIf(String::isNotBlank)
                ?: env("WALLET_ENABLED")?.takeIf(String::isNotBlank)
                ?: "true"
            return TripWatchConfig(
                enabled = enabled.equals("true", ignoreCase = true),
                path = env("WALLET_DB_PATH")?.takeIf(String::isNotBlank)
                    ?: if (mainDatabase == ":memory:") ":memory:" else "$mainDatabase.wallet.sqlite",
                site = env("WALLET_PUBLIC_BASE_URL").orEmpty().trimEnd('/'),
                env = env,
            )
        }
    }
}

@Serializable private data class WatchAvailability(val enabled: Boolean)
@Serializable private data class WatchError(val error: String)
@Serializable private data class WatchChange(val action: String)
@Serializable private data class WatchLink(val url: String)
@Serializable private data class ApplePushToken(val pushToken: String)
@Serializable private data class AppleChanged(val serialNumbers: List<String>, val lastUpdated: String)

@Serializable
private data class WatchView(
    val id: String,
    val origin: String,
    val destination: String,
    val instruction: String,
    val detail: String?,
    val kind: String,
    val expiresAt: Long,
    val leg: Int,
    val legCount: Int,
    val canManage: Boolean,
    val actions: List<String>,
    val apple: Boolean,
    val google: Boolean,
    val alternativesUrl: String,
    /** The pass face shown in Wallet, for the page's HTML preview. */
    val pass: TripWatchPassFace,
)

private fun TripWatch.view(canManage: Boolean, service: TripWatchService): WatchView {
    val kind = when {
        ended -> "ended"
        advice.action is TripWatchAction.Board -> "board"
        advice.action is TripWatchAction.Alight -> "alight"
        advice.action == TripWatchAction.WaitForService -> "wait"
        else -> "check"
    }
    val actions = when {
        !canManage || ended -> emptyList()
        onBoard -> listOf("alighted", "stop")
        kind == "board" -> listOf("boarded", "stop")
        kind == "check" -> listOf("wait", "stop")
        else -> listOf("stop")
    }
    val next = if (onBoard) leg.to.id else leg.from.id
    return WatchView(id, legs.first().from.name, destination.name, instruction(), advice.detail, kind, expiresAt,
        currentLeg + 1, legs.size, canManage, actions, canManage && !ended && service.apple != null,
        canManage && !ended && service.google != null,
        if (next == destination.id) "/" else
            "/?station=${urlEncode(next)}&to=${urlEncode(destination.id)}",
        passFace(service.site))
}

internal fun Route.tripWatchRoutes(service: TripWatchService?) {
    route("/api/v1/trip-watches") {
        get("/availability") { call.respond(WatchAvailability(service != null)) }
        if (service == null) return@route
        get {
            call.privateResponse()
            val owner = call.owner()
            call.respond(if (owner == null) emptyList() else service.store.activeFor(owner).map { it.view(true, service) })
        }
        post {
            call.privateResponse()
            val input = call.receive<TripWatchSelection>()
            if (!input.valid()) return@post call.respond(HttpStatusCode.BadRequest, WatchError("Choose a connection to watch."))
            val token = call.session() ?: watchToken()
            val watch = service.start(watchOwner(token), input)
                ?: return@post call.respond(HttpStatusCode.Conflict, WatchError("This connection has changed. Select it again to start watching."))
            call.response.cookies.append(Cookie(
                name = SESSION, value = token, path = "/", httpOnly = true, secure = service.site.startsWith("https://"),
                maxAge = 30 * 24 * 60 * 60, extensions = mapOf("SameSite" to "Lax"),
            ))
            call.respond(HttpStatusCode.Created, watch.view(true, service))
        }
        get("/{id}") {
            call.privateResponse()
            val watch = call.watch(service) ?: return@get call.notFound()
            call.respond(watch.view(watch.owner == call.owner(), service))
        }
        patch("/{id}") {
            call.privateResponse()
            val watch = call.owned(service) ?: return@patch call.notFound()
            val action = call.receive<WatchChange>().action
            if (action !in setOf("boarded", "alighted", "wait")) {
                return@patch call.respond(HttpStatusCode.BadRequest, WatchError("Choose a valid trip action."))
            }
            val updated = service.change(watch.id, watch.owner, action) ?: return@patch call.notFound()
            call.respond(updated.view(true, service))
        }
        delete("/{id}") {
            call.privateResponse()
            val watch = call.owned(service) ?: return@delete call.notFound()
            val ended = service.change(watch.id, watch.owner, "stop") ?: return@delete call.notFound()
            call.respond(ended.view(true, service))
        }
        get("/{id}/apple.pkpass") {
            call.privateResponse()
            val watch = call.owned(service)?.takeIf { !it.ended && it.expiresAt > System.currentTimeMillis() }
                ?: return@get call.notFound()
            val apple = service.apple ?: return@get call.notFound()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=trip-watch.pkpass")
            call.respondBytes(apple.pass(watch, service.site), pkpass)
        }
        post("/{id}/google") {
            call.privateResponse()
            val watch = call.owned(service) ?: return@post call.notFound()
            val url = try {
                service.googleSave(watch.id, watch.owner)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadGateway, WatchError("Google Wallet is unavailable. Try again shortly."))
            } ?: return@post call.notFound()
            call.respond(WatchLink(url))
        }
    }
    if (service == null) return
    if (service.google != null) {
        get("/wallet/icon.png") { call.respondBytes(walletIcon96, ContentType.Image.PNG) }
        get("/wallet/logo.png") { call.respondBytes(googleLogo, ContentType.Image.PNG) }
    }
    service.apple?.let { appleWalletRoutes(service, it) }
}

private fun Route.appleWalletRoutes(service: TripWatchService, apple: AppleWallet) = route("/wallet/apple/v1") {
    route("/devices/{device}/registrations/{type}") {
        get {
            call.privateResponse()
            val device = call.device() ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (call.parameters["type"] != apple.passType) return@get call.respond(HttpStatusCode.NoContent)
            val after = call.request.queryParameters["passesUpdatedSince"]?.toLongOrNull() ?: 0
            val updates = service.store.updated(device, after)
            if (updates.isEmpty()) call.respond(HttpStatusCode.NoContent)
            else call.respond(AppleChanged(updates.map(TripWatch::id), updates.last().revision.toString()))
        }
        post("/{id}") {
            call.privateResponse()
            val watch = call.appleWatch(service, apple) ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val device = call.device() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val token = call.receive<ApplePushToken>().pushToken
            if (!Regex("[A-Za-z0-9_-]{1,256}").matches(token)) return@post call.respond(HttpStatusCode.BadRequest)
            val created = service.store.register(watch.id, device, token)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK)
        }
        delete("/{id}") {
            call.privateResponse()
            val watch = call.appleWatch(service, apple) ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val device = call.device() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            service.store.unregister(watch.id, device)
            call.respond(HttpStatusCode.OK)
        }
    }
    get("/passes/{type}/{id}") {
        call.privateResponse()
        val watch = call.appleWatch(service, apple) ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val date = Instant.ofEpochMilli(watch.updatedAt).atZone(ZoneOffset.UTC)
        call.response.header(HttpHeaders.LastModified, DateTimeFormatter.RFC_1123_DATE_TIME.format(date))
        val since = call.request.headers[HttpHeaders.IfModifiedSince]?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
        }
        // Wallet's date header has second-level precision. Return the pass if two revisions could share the same second.
        if (since != null && date.isBefore(since)) return@get call.respond(HttpStatusCode.NotModified)
        call.respondBytes(apple.pass(watch, service.site), pkpass)
    }
    post("/log") { call.respond(HttpStatusCode.OK) }
}

private const val SESSION = "twin_trip_watch"
private val googleLogo by lazy { passCircleLogo() }
private val pkpass = ContentType.parse("application/vnd.apple.pkpass")
private val opaque = Regex("[A-Za-z0-9_-]{32}")
private fun TripWatchSelection.valid(): Boolean {
    fun safe(id: String?) = id != null && id.isNotBlank() && id.length <= 200
    return if (journeyId != null) safe(journeyId) && trainId == null
    else safe(trainId) && safe(fromStopId) && safe(toStopId) && fromStopId != toStopId
}
private fun ApplicationCall.session() = request.cookies[SESSION]?.takeIf(opaque::matches)
private fun ApplicationCall.owner() = session()?.let(::watchOwner)
private fun ApplicationCall.device() = parameters["device"]?.takeIf { it.length in 1..256 }
private fun ApplicationCall.watch(service: TripWatchService) = parameters["id"]?.takeIf(opaque::matches)?.let(service.store::find)
private fun ApplicationCall.owned(service: TripWatchService) = watch(service)?.takeIf { it.owner == owner() }
private fun ApplicationCall.appleWatch(service: TripWatchService, apple: AppleWallet): TripWatch? {
    if (parameters["type"] != apple.passType) return null
    val watch = watch(service) ?: return null
    val auth = request.headers[HttpHeaders.Authorization] ?: return null
    return watch.takeIf { MessageDigest.isEqual(auth.toByteArray(), "ApplePass ${it.appleToken}".toByteArray()) }
}
private suspend fun ApplicationCall.notFound() = respond(HttpStatusCode.NotFound, WatchError("Trip watch unavailable."))
private fun ApplicationCall.privateResponse() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
}
