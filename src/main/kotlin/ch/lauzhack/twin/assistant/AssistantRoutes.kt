package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.InvalidInputException
import ch.lauzhack.twin.common.UnavailableException
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

/** Lenient on input: the passenger app may send extra fields or explicit nulls. */
private val requestJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

/**
 * `POST /api/v1/assistant/ask` (contract: ASSISTANT_CONTRACT.md section 2, docs/API.md "Passenger assistant").
 * [agent] is null when no Claude API key is configured: the endpoint then answers 503 `assistant_unavailable`.
 */
fun Route.assistantV1(agent: AssistantAgent?) = route("/api/v1/assistant") {
    post("/ask") {
        val req = try {
            requestJson.decodeFromString<AskRequest>(call.receiveText())
        } catch (e: Exception) {
            throw InvalidInputException("bad_request", "body must be JSON: {question, context?, history?, sessionId?}")
        }
        val question = req.question.trim()
        if (question.isEmpty()) throw InvalidInputException("bad_question", "question is required")
        if (question.length > 2000) throw InvalidInputException("bad_question", "question must be at most 2000 characters")
        val a = agent ?: throw UnavailableException(
            "assistant_unavailable", "the assistant is not configured (ANTHROPIC_API_KEY is not set)",
        )
        call.respond(a.ask(req.copy(question = question)))
    }
}
