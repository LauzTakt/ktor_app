package ch.lauzhack.twin.auth

import kotlinx.serialization.Serializable

/*
 * Staff authentication (contract: docs/API.md, "Staff authentication").
 *
 * Read API, WebSockets and Swagger are PUBLIC. Staff-only calls are wrapped in [staffOnly] and need
 * `Authorization: Bearer <token>` (token from `POST /api/v1/auth/login`) or the `twin_staff` cookie that login also
 * sets for the browser (HttpOnly, SameSite=Strict). The staff web pages are gated by [staffPages].
 * Every account is `staff`. When the user table is empty, `admin` / [AuthConfig.adminPassword] (default `admin`) is created.
 *
 * Files: AuthModels (config + DTOs), Passwords (hashing + tokens), AuthService (rules), AuthRoutes (Ktor wiring).
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
