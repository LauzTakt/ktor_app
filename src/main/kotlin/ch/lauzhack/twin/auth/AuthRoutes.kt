package ch.lauzhack.twin.auth

import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.common.UnauthorizedException
import ch.lauzhack.twin.common.urlEncode
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.http.content.resolveResource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Application.installStaffAuth(auth: AuthService) {
    install(Authentication) {
        bearer(STAFF_AUTH) {
            realm = "train-twin staff"
            authHeader { call -> staffToken(call)?.let { HttpAuthHeader.Single("Bearer", it) } }
            authenticate { cred -> auth.principal(cred.token) }
        }
    }
}

/** Token from `Authorization: Bearer …`, else from the [STAFF_COOKIE] cookie. */
internal fun staffToken(call: ApplicationCall): String? {
    val h = runCatching { call.request.parseAuthorizationHeader() }.getOrNull()
    if (h is HttpAuthHeader.Single && h.authScheme.equals("Bearer", ignoreCase = true)) return h.blob
    return call.request.cookies[STAFF_COOKIE]?.takeIf { it.isNotBlank() }
}

private fun staffCookie(value: String, maxAge: Long, secure: Boolean) = Cookie(
    name = STAFF_COOKIE, value = value, maxAge = maxAge.toInt(), path = "/", httpOnly = true, secure = secure,
    extensions = mapOf("SameSite" to "Strict"),
)

/** Wrap staff-only routes: `staffOnly { get("/x") { val me = call.staff() } }`. Missing/invalid token -> 401 `unauthorized`. */
fun Route.staffOnly(build: Route.() -> Unit): Route = authenticate(STAFF_AUTH, build = build)

fun RoutingCall.staff(): StaffPrincipal = principal<StaffPrincipal>()!!

/** `/api/v1/auth/...` (login is public) and the first staff-only calls: `/api/v1/staff/users`. */
fun Route.authV1(auth: AuthService) = route("/api/v1") {
    route("/auth") {
        post("/login") {
            val req = call.receive<LoginDto>()
            val res = auth.login(req.username, req.password)
                ?: throw UnauthorizedException("invalid_credentials", "wrong username or password")
            // Browser session for the staff pages: same token in an HttpOnly cookie.
            call.response.cookies.append(staffCookie(res.token, auth.tokenTtlSeconds, call.request.local.scheme == "https"))
            call.respond(res)
        }
        staffOnly {
            get("/me") { call.respond(auth.user(call.staff().username)) }
            post("/logout") {
                auth.logout(call.staff())
                call.response.cookies.append(staffCookie("", 0, call.request.local.scheme == "https"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    staffOnly {
        route("/staff/users") {
            get { call.respond(auth.users()) }
            post { call.respond(HttpStatusCode.Created, auth.createUser(call.receive<CreateUserDto>())) }
            delete("/{username}") {
                auth.deleteUser(call.staff(), call.parameters["username"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            put("/{username}/password") {
                auth.setPassword(call.staff(), call.parameters["username"]!!, call.receive<SetPasswordDto>().password)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * Staff-only web pages: without a valid session they redirect to `/login.html?next=<page>`.
 * Must be registered before `staticResources("/", "static")` (explicit routes win over its tailcard).
 */
fun Route.staffPages(auth: AuthService, pages: Map<String, String>) {
    for ((path, resource) in pages) {
        get(path) {
            if (auth.principalOf(call) == null) {
                return@get call.respondRedirect("/login.html?next=" + urlEncode(path))
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(call.resolveResource(resource) ?: throw NotFoundException("not_found", "no page $path"))
        }
    }
}
