package ch.lauzhack.twin.auth

import ch.lauzhack.twin.common.ConflictException
import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.common.UnauthorizedException
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.db.UserRow
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.http.content.resolveResource
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/*
 * Staff authentication (contract: docs/API.md, "Staff authentication").
 *
 * Read API, WebSockets and Swagger are PUBLIC. Staff-only calls are wrapped in [staffOnly] and need
 * `Authorization: Bearer <token>` (token from `POST /api/v1/auth/login`) or the `twin_staff` cookie that login also
 * sets for the browser (HttpOnly, SameSite=Strict). The staff web pages are gated by [staffPages].
 * Every account is `staff`. When the user table is empty, `admin` / [AuthConfig.adminPassword] (default `admin`) is created.
 */

const val STAFF_AUTH = "staff"
/** Cookie holding the same token as the Bearer header, for the staff web pages. */
const val STAFF_COOKIE = "twin_staff"

data class AuthConfig(
    /** Password of the `admin` account created when there are no users yet (env `ADMIN_PASSWORD`). */
    val adminPassword: String = "admin",
    /** Lifetime of a login token (env `AUTH_TOKEN_TTL_HOURS`). */
    val tokenTtlHours: Long = 12,
)

/** The authenticated caller of a staff-only route: `call.principal<StaffPrincipal>()`. */
data class StaffPrincipal(val username: String, val role: String, internal val tokenHash: String)

// ---------------------------------------------------------------- DTOs

@Serializable
data class LoginDto(val username: String, val password: String)

@Serializable
data class UserDto(val username: String, val role: String, val createdAt: Long, val updatedAt: Long)

@Serializable
data class LoginResultDto(val token: String, val tokenType: String = "Bearer", val expiresAt: Long, val user: UserDto)

@Serializable
data class CreateUserDto(val username: String, val password: String)

@Serializable
data class SetPasswordDto(val password: String)

private fun UserRow.toDto() = UserDto(username, role, createdAt, updatedAt)

// ---------------------------------------------------------------- passwords + tokens

/** PBKDF2-HMAC-SHA256 (JDK only), stored as `pbkdf2-sha256$<iterations>$<salt>$<hash>`. */
internal object Passwords {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        return "pbkdf2-sha256\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = b64d.decode(parts[3])
        return MessageDigest.isEqual(derive(password, b64d.decode(parts[2]), iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).encoded

    /** Same cost as a real check, so unknown usernames take as long as wrong passwords. */
    val dummyHash: String by lazy { hash("dummy-password") }
}

private val tokenRnd = SecureRandom()
private fun newToken(): String = ByteArray(32).also { tokenRnd.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
internal fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

// ---------------------------------------------------------------- service

class AuthService(private val db: Database, private val cfg: AuthConfig = AuthConfig(), private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Creates `admin` when there is no user at all; warns while it still has the default password. */
    fun ensureDefaultAdmin() {
        if (db.userCount() == 0) {
            val now = clock()
            db.insertUser(UserRow(DEFAULT_ADMIN, Passwords.hash(cfg.adminPassword), ROLE_STAFF, now, now))
            log.warn("Created default staff user '{}'{}", DEFAULT_ADMIN, if (cfg.adminPassword == "admin") " with password 'admin': change it" else "")
        } else {
            db.user(DEFAULT_ADMIN)?.takeIf { Passwords.verify("admin", it.passwordHash) }
                ?.let { log.warn("Staff user 'admin' still has the default password 'admin': change it") }
        }
        db.deleteExpiredTokens(clock())
    }

    fun login(username: String, password: String): LoginResultDto? {
        val u = db.user(username)
        val ok = Passwords.verify(password, u?.passwordHash ?: Passwords.dummyHash) && u != null
        if (!ok) return null
        val token = newToken()
        val now = clock()
        val expiresAt = now + cfg.tokenTtlHours * 3_600_000L
        db.insertToken(sha256(token), u!!.username, now, expiresAt)
        log.info("staff login: {}", u.username)
        return LoginResultDto(token = token, expiresAt = expiresAt, user = u.toDto())
    }

    fun principal(token: String): StaffPrincipal? {
        val h = sha256(token)
        val username = db.tokenUser(h, clock()) ?: return null
        val u = db.user(username) ?: return null
        return StaffPrincipal(u.username, u.role, h)
    }

    fun logout(p: StaffPrincipal) = db.deleteToken(p.tokenHash)

    /** Staff behind this call (Bearer header first, then the cookie), or null. Never throws: for page gating. */
    fun principalOf(call: ApplicationCall): StaffPrincipal? = staffToken(call)?.let { principal(it) }

    val tokenTtlSeconds: Long get() = cfg.tokenTtlHours * 3600

    fun users(): List<UserDto> = db.users().map { it.toDto() }

    fun user(username: String): UserDto = db.user(username)?.toDto() ?: throw userNotFound(username)

    fun createUser(req: CreateUserDto): UserDto {
        validateUsername(req.username)
        validatePassword(req.password)
        val now = clock()
        val row = UserRow(req.username, Passwords.hash(req.password), ROLE_STAFF, now, now)
        if (!db.insertUser(row)) throw ConflictException("user_exists", "user '${req.username}' already exists")
        log.info("staff user created: {}", req.username)
        return row.toDto()
    }

    /** Changing a password revokes the user's other sessions (the caller keeps its own when changing its own password). */
    fun setPassword(caller: StaffPrincipal, username: String, password: String) {
        validatePassword(password)
        if (!db.updatePassword(username, Passwords.hash(password), clock())) throw userNotFound(username)
        db.deleteTokensOf(username, keepHash = caller.tokenHash.takeIf { caller.username == username })
        log.info("password of {} changed by {}", username, caller.username)
    }

    fun deleteUser(caller: StaffPrincipal, username: String) {
        if (caller.username == username) throw ConflictException("cannot_delete_self", "you cannot delete your own account")
        if (!db.deleteUser(username)) throw userNotFound(username)
        log.info("staff user {} deleted by {}", username, caller.username)
    }

    private fun userNotFound(u: String) = NotFoundException("user_not_found", "user '$u' not found")

    private fun validateUsername(u: String) {
        if (!USERNAME.matches(u)) throw InvalidInputException("bad_username", "username must be 2-32 chars of a-z, A-Z, 0-9, . _ -")
    }

    private fun validatePassword(p: String) {
        if (p.length !in 4..200) throw InvalidInputException("bad_password", "password must be 4-200 characters")
    }

    companion object {
        const val DEFAULT_ADMIN = "admin"
        const val ROLE_STAFF = "staff"
        private val USERNAME = Regex("^[A-Za-z0-9._-]{2,32}$")
    }
}

// ---------------------------------------------------------------- Ktor wiring

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
private fun staffToken(call: ApplicationCall): String? {
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
                return@get call.respondRedirect("/login.html?next=" + java.net.URLEncoder.encode(path, Charsets.UTF_8))
            }
            call.response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
            call.respond(call.resolveResource(resource) ?: throw NotFoundException("not_found", "no page $path"))
        }
    }
}
