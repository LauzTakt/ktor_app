package ch.lauzhack.twin.common

/**
 * Errors raised below the HTTP layer (queries, services). They carry a stable machine-readable [code] and a message,
 * never an HTTP status: `api/v1/Routes.kt` (`installApiErrors`) maps each kind to its status and the
 * `{"error":{code,message}}` body.
 */
sealed class AppException(val code: String, override val message: String) : RuntimeException(message)

/** A requested entity does not exist (404). */
class NotFoundException(code: String, message: String) : AppException(code, message)

/** Invalid input (400). */
class InvalidInputException(code: String, message: String) : AppException(code, message)

/** The request clashes with the current state (409). */
class ConflictException(code: String, message: String) : AppException(code, message)

/** Missing or wrong credentials (401). */
class UnauthorizedException(code: String, message: String) : AppException(code, message)

/** A feature is not configured or a dependency is down (503). */
class UnavailableException(code: String, message: String) : AppException(code, message)

fun notFound(what: String, id: String) = NotFoundException("${what}_not_found", "$what '$id' not found")

fun invalid(code: String, message: String): Nothing = throw InvalidInputException(code, message)
