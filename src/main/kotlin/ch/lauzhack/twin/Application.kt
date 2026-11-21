package ch.lauzhack.twin

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class Health(val status: String)

/** Entry point referenced from application.yaml (ktor.application.modules). */
fun Application.module() {
    install(CallLogging)
    install(ContentNegotiation) { json() }

    routing {
        get("/health") { call.respond(Health("ok")) }
    }
}
