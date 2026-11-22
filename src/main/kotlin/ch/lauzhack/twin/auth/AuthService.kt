package ch.lauzhack.twin.auth

import ch.lauzhack.twin.common.ConflictException
import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.db.TokenRepository
import ch.lauzhack.twin.db.UserRepository
import ch.lauzhack.twin.db.UserRow
import io.ktor.server.application.ApplicationCall
import org.slf4j.LoggerFactory

/** Staff accounts and login tokens, over [UserRepository] and [TokenRepository]. */
class AuthService(
    private val users: UserRepository,
    private val tokens: TokenRepository,
    private val cfg: AuthConfig = AuthConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Creates `admin` when there is no user at all; warns while it still has the default password. */
    fun ensureDefaultAdmin() {
        if (users.count() == 0) {
            val now = clock()
            users.insert(UserRow(DEFAULT_ADMIN, Passwords.hash(cfg.adminPassword), ROLE_STAFF, now, now))
            log.warn("Created default staff user '{}'{}", DEFAULT_ADMIN, if (cfg.adminPassword == "admin") " with password 'admin': change it" else "")
        } else {
            users.get(DEFAULT_ADMIN)?.takeIf { Passwords.verify("admin", it.passwordHash) }
                ?.let { log.warn("Staff user 'admin' still has the default password 'admin': change it") }
        }
        tokens.deleteExpired(clock())
    }

    fun login(username: String, password: String): LoginResultDto? {
        val user = users.get(username)
        // Always verify (against a dummy hash for unknown users), so both failures take the same time.
        val passwordOk = Passwords.verify(password, user?.passwordHash ?: Passwords.dummyHash)
        if (user == null || !passwordOk) return null
        val token = newToken()
        val now = clock()
        val expiresAt = now + cfg.tokenTtlHours * 3_600_000L
        tokens.insert(sha256(token), user.username, now, expiresAt)
        log.info("staff login: {}", user.username)
        return LoginResultDto(token = token, expiresAt = expiresAt, user = user.toDto())
    }

    fun principal(token: String): StaffPrincipal? {
        val h = sha256(token)
        val username = tokens.userOf(h, clock()) ?: return null
        val u = users.get(username) ?: return null
        return StaffPrincipal(u.username, u.role, h)
    }

    fun logout(p: StaffPrincipal) = tokens.delete(p.tokenHash)

    /** Staff behind this call (Bearer header first, then the cookie), or null. Never throws: for page gating. */
    fun principalOf(call: ApplicationCall): StaffPrincipal? = staffToken(call)?.let { principal(it) }

    val tokenTtlSeconds: Long get() = cfg.tokenTtlHours * 3600

    fun users(): List<UserDto> = users.all().map { it.toDto() }

    fun user(username: String): UserDto = users.get(username)?.toDto() ?: throw userNotFound(username)

    fun createUser(req: CreateUserDto): UserDto {
        validateUsername(req.username)
        validatePassword(req.password)
        val now = clock()
        val row = UserRow(req.username, Passwords.hash(req.password), ROLE_STAFF, now, now)
        if (!users.insert(row)) throw ConflictException("user_exists", "user '${req.username}' already exists")
        log.info("staff user created: {}", req.username)
        return row.toDto()
    }

    /** Changing a password revokes the user's other sessions (the caller keeps its own when changing its own password). */
    fun setPassword(caller: StaffPrincipal, username: String, password: String) {
        validatePassword(password)
        if (!users.updatePassword(username, Passwords.hash(password), clock())) throw userNotFound(username)
        tokens.deleteAllOf(username, keepHash = caller.tokenHash.takeIf { caller.username == username })
        log.info("password of {} changed by {}", username, caller.username)
    }

    fun deleteUser(caller: StaffPrincipal, username: String) {
        if (caller.username == username) throw ConflictException("cannot_delete_self", "you cannot delete your own account")
        if (!users.delete(username)) throw userNotFound(username)
        log.info("staff user {} deleted by {}", username, caller.username)
    }

    private fun userNotFound(u: String) = NotFoundException("user_not_found", "user '$u' not found")

    private fun validateUsername(u: String) {
        if (!USERNAME.matches(u)) throw InvalidInputException("bad_username", "username must be 2-32 chars of a-z, A-Z, 0-9, . _ -")
    }

    private fun validatePassword(p: String) {
        if (p.length !in 4..200) throw InvalidInputException("bad_password", "password must be 4-200 characters")
    }

    private fun UserRow.toDto() = UserDto(username, role, createdAt, updatedAt)

    companion object {
        const val DEFAULT_ADMIN = "admin"
        const val ROLE_STAFF = "staff"
        private val USERNAME = Regex("^[A-Za-z0-9._-]{2,32}$")
    }
}
