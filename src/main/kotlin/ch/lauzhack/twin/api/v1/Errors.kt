package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.common.AppException
import ch.lauzhack.twin.common.ConflictException
import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.NotFoundException
import ch.lauzhack.twin.common.UnauthorizedException
import ch.lauzhack.twin.common.UnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorDto(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

/** HTTP status of each domain error: the only place where layers below the routes meet HTTP. */
internal fun AppException.httpStatus(): HttpStatusCode = when (this) {
    is NotFoundException -> HttpStatusCode.NotFound
    is InvalidInputException -> HttpStatusCode.BadRequest
    is ConflictException -> HttpStatusCode.Conflict
    is UnauthorizedException -> HttpStatusCode.Unauthorized
    is UnavailableException -> HttpStatusCode.ServiceUnavailable
}

/** Uniform `{"error":{"code","message"}}` bodies for every API error. */
fun Application.installApiErrors() {
    val log = LoggerFactory.getLogger("api")
    install(StatusPages) {
        exception<AppException> { call, e -> call.respond(e.httpStatus(), ErrorDto(ErrorBody(e.code, e.message))) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorBody("bad_request", e.message ?: "bad request")))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(ErrorBody("internal", "internal error")))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorDto(ErrorBody("unauthorized", "staff login required: Authorization: Bearer <token> from POST /api/v1/auth/login")))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorDto(ErrorBody("not_found", "no such endpoint: ${call.request.local.uri}")))
        }
    }
}
