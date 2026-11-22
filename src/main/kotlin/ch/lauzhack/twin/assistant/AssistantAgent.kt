package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.common.int
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.twinapi.SbbTwinClient
import ch.lauzhack.twin.twinapi.JourneyCache
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import io.ktor.server.config.ApplicationConfig
import java.time.Duration
import java.util.Optional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import ch.lauzhack.twin.common.dayTime
import ch.lauzhack.twin.common.toJsonElement
import ch.lauzhack.twin.query.Severity

data class AssistantConfig(
    /** sbb-twin base URL (`twin.url` / `SBB_TWIN_URL`, shared with the passenger app). */
    val twinUrl: String = "http://127.0.0.1:8787",
    val model: String = "claude-sonnet-5-5",
    /** Fast mode (`speed: "fast"`): same model, faster output, premium price. Only Opus 5.5 / Fable 5.2 support it. */
    val fastMode: Boolean = false,
    /** `low | medium | high | xhigh | max`: how much the model thinks (latency). */
    val effort: String = "low",
    /** Model calls per question, tool turns included. */
    val maxModelCalls: Int = 6,
    val maxTokens: Long = 16_000,
    /** Per model call; the SDK retries 429/5xx twice within it. */
    val modelTimeoutMs: Long = 30_000,
    /** Whole question: past it, the passenger gets a holding answer. */
    val deadlineMs: Long = 45_000,
    /** After this, the next model call is told it is the last one. */
    val softDeadlineMs: Long = 16_000,
    val twinTimeoutMs: Long = 4_000,
) {
    companion object {
        fun from(conf: ApplicationConfig): AssistantConfig {
            fun s(key: String) = conf.propertyOrNull("assistant.$key")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            val d = AssistantConfig()
            return AssistantConfig(
                twinUrl = conf.propertyOrNull("twin.url")?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: d.twinUrl,
                model = s("model") ?: d.model,
                fastMode = s("fastMode")?.toBooleanStrictOrNull() ?: d.fastMode,
                effort = s("effort")?.lowercase() ?: d.effort,
                maxModelCalls = s("maxModelCalls")?.toIntOrNull()?.coerceIn(1, 12) ?: d.maxModelCalls,
            )
        }
    }
}

/** One Messages API call. Faked in tests. */
fun interface LlmClient {
    suspend fun create(params: MessageCreateParams): Message
}

/** The real Claude API client (Java SDK, async). Fast mode rate-limited -> one retry at standard speed. */
class AnthropicLlm(private val client: AnthropicClient) : LlmClient {
    private val log = LoggerFactory.getLogger(AnthropicLlm::class.java)

    override suspend fun create(params: MessageCreateParams): Message = try {
        client.async().messages().create(params).await()
    } catch (e: RateLimitException) {
        if (params.speed().isEmpty) throw e
        log.warn("fast mode rate-limited; retrying at standard speed")
        client.async().messages().create(params.toBuilder().speed(Optional.empty<MessageCreateParams.Speed>()).build()).await()
    }

    /**
     * Opens the HTTPS connection to the API ahead of the first question (and keeps it open while the app runs), so a
     * passenger never waits for a TLS handshake. Free: it only reads the model's metadata.
     */
    fun keepWarm(model: String, everyMs: Long = 240_000L) {
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching { client.models().retrieve(model) }.onFailure { log.debug("warm-up failed: {}", it.toString()) }
                try { Thread.sleep(everyMs) } catch (e: InterruptedException) { return@Thread }
            }
        }, "claude-keepalive")
        t.isDaemon = true
        t.start()
    }

    companion object {
        /** null when no API key is configured (`ANTHROPIC_API_KEY`): the endpoint then answers 503. */
        fun fromEnv(cfg: AssistantConfig): AnthropicLlm? {
            if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank() && System.getenv("ANTHROPIC_AUTH_TOKEN").isNullOrBlank()) return null
            val client = AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofMillis(cfg.modelTimeoutMs))
                .maxRetries(2)
                .build()
            return AnthropicLlm(client)
        }
    }
}

/** Builds the assistant over ktor_app's own data (live state, SQLite) and sbb-twin. */
fun buildAssistant(
    cfg: AssistantConfig,
    llm: LlmClient,
    queries: ch.lauzhack.twin.query.TwinQueries,
    state: ch.lauzhack.twin.state.LiveState,
    incidents: ch.lauzhack.twin.query.IncidentQueries,
    reports: ch.lauzhack.twin.report.ReportService,
    journeys: JourneyCache = JourneyCache(),
): AssistantAgent {
    val stations = StationResolver(state)
    val tools = AssistantTools(queries, incidents, reports, stations, SbbTwinClient(cfg.twinUrl, cfg.twinTimeoutMs), journeys)
    return AssistantAgent(cfg, llm, tools)
}

/**
 * The passenger assistant's back office: an agent loop on Claude (Sonnet 5.5 by default) over [AssistantTools].
 * Prefetches what the context makes predictable, runs the tool calls of one turn in parallel, caps the loop at
 * [AssistantConfig.maxModelCalls] calls, and turns the `respond` tool + drawing tools into an [AskResponse].
 */
class AssistantAgent(
    private val cfg: AssistantConfig,
    private val llm: LlmClient,
    private val tools: AssistantTools,
    private val systemPrompt: String = loadSystemPrompt(),
) {
    private val log = LoggerFactory.getLogger(AssistantAgent::class.java)

    /** Loads the station catalog and the code paths of a first question, so the first passenger is not slower. */
    fun warmUp() {
        runCatching {
            tools.stations.all()
            tools.guessTrip("from Zürich HB to Bern")
            tools.guessLine("IC 8")
            baseParams().addUserMessage("warm-up").build()
        }.onFailure { log.debug("assistant warm-up: {}", it.toString()) }
    }

    suspend fun ask(request: AskRequest): AskResponse {
        val started = System.currentTimeMillis()
        // Only about something there is no data for (platforms, fares, ...): a fixed answer, no model call.
        Scope.onlyOutOfScope(request.question)?.let { topics ->
            val answer = Scope.answer(request.question, topics)
            log.info("assistant: out of scope {}, no model call, q=\"{}\"", topics, request.question.take(120))
            return AskResponse(answer, null, emptyList(), emptyList(), emptyList(), null, System.currentTimeMillis() - started, topics)
        }
        val turn = TurnState(request, started)
        val visuals = ArrayList<VisualCommand>()
        val stats = Stats()
        var fallback: String? = null
        try {
            withTimeout(cfg.deadlineMs) { loop(turn, visuals, stats) { fallback = it } }
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            log.warn("assistant: deadline of {} ms reached after {} model calls", cfg.deadlineMs, stats.calls)
        } catch (e: Exception) {
            log.error("assistant failed after {} model calls", stats.calls, e)
        }
        val response = buildResponse(turn, visuals, fallback, started)
        val elapsed = response.elapsedMs
        log.info(
            "assistant: {} ms, {} model calls {} (in {} / cache read {} / out {} tokens), tools [{}], respond={} q=\"{}\"",
            elapsed, stats.calls, stats.perCall, stats.input, stats.cacheRead, stats.output, turn.toolsCalled.joinToString(","),
            turn.respond != null, request.question.take(120),
        )
        return response
    }

    private suspend fun loop(turn: TurnState, visuals: MutableList<VisualCommand>, stats: Stats, setFallback: (String?) -> Unit) {
        val prefetched = tools.prefetch(turn)
        val params = baseParams().addUserMessage(firstMessage(turn, prefetched))
        while (stats.calls < cfg.maxModelCalls) {
            val elapsed = System.currentTimeMillis() - turn.now
            val lastCall = stats.calls == cfg.maxModelCalls - 1 || elapsed > cfg.softDeadlineMs
            if (lastCall && stats.calls > 0) params.addSystemMessage(LAST_CALL)
            val t0 = System.currentTimeMillis()
            val msg = llm.create(params.build())
            stats.add(msg, System.currentTimeMillis() - t0)
            params.addMessage(msg)

            msg.content().mapNotNull { b -> b.text().orElse(null)?.text()?.trim()?.takeIf { it.isNotEmpty() } }
                .joinToString(" ").takeIf { it.isNotEmpty() }?.let(setFallback)
            val uses: List<ToolUseBlock> = msg.content().mapNotNull { it.toolUse().orElse(null) }
            if (log.isInfoEnabled) {
                log.info("assistant call {}: {}", stats.calls, uses.joinToString("; ") { "${it.name()} ${inputOf(it).toString().take(400)}" })
            }
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) {
                setFallback(REFUSED)
                return
            }
            if (uses.isEmpty()) return

            // Independent tool calls of one turn run in parallel; their results go back in one user message.
            val outcomes = coroutineScope { uses.map { u -> async { tools.execute(u.name(), inputOf(u), turn) } }.awaitAll() }
            outcomes.forEach { visuals += it.visuals }
            if (turn.respond != null || lastCall) return
            params.addUserMessageOfContentBlockParams(
                uses.zip(outcomes).map { (u, o) ->
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(u.id()).content(o.content).apply { if (o.isError) isError(true) }.build(),
                    )
                },
            )
        }
    }

    private fun baseParams(): MessageCreateParams.Builder {
        val b = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(cfg.maxTokens)
            // Tools + system prompt are identical for every passenger: one cache entry (1 h) shared by all questions.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder().text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                        .build(),
                ),
            )
            // ... and the growing conversation is cached between the calls of one question.
            .cacheControl(CacheControlEphemeral.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort)).build())
        tools.definitions.forEach { b.addTool(it) }
        if (cfg.fastMode) b.speed(MessageCreateParams.Speed.FAST)
        return b
    }

    private fun firstMessage(turn: TurnState, prefetched: List<Triple<String, JsonObject, ToolOutcome>>): String = buildString {
        val req = turn.request
        appendLine("Now: ${dayTime(turn.now)} (Swiss time).")
        appendLine()
        appendLine("Passenger question: ${req.question.trim()}")
        val context = tools.describeContext(req.context)
        if (context.isNotEmpty()) {
            appendLine()
            appendLine("Context from the passenger app:")
            context.forEach { appendLine("- $it") }
        }
        val history = req.history.filter { it.text.isNotBlank() }.takeLast(8)
        if (history.isNotEmpty()) {
            appendLine()
            appendLine("Conversation so far (oldest first):")
            history.forEach { appendLine("- ${if (it.role.startsWith("assist")) "assistant" else "passenger"}: ${it.text.trim().take(600)}") }
        }
        if (prefetched.isNotEmpty()) {
            appendLine()
            appendLine("Already looked up for you (live, just now); call more tools only for what is missing:")
            prefetched.forEach { (name, args, out) ->
                appendLine()
                appendLine("$name $args")
                appendLine(out.content)
            }
        }
    }

    private fun inputOf(u: ToolUseBlock): JsonObject =
        runCatching { u._input().convert(Map::class.java).toJsonElement() as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private suspend fun buildResponse(turn: TurnState, drawn: List<VisualCommand>, fallback: String?, started: Long): AskResponse {
        val r = turn.respond
        val answer = r?.answer?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() } ?: HOLDING_ANSWER
        // One recommended option and at most one alternative (the planner ranks, the model explains).
        val given = r?.options.orEmpty().filter { it.label.isNotBlank() || it.journeyId != null }
        val chosen = (given.firstOrNull { it.recommended } ?: given.firstOrNull())
            ?.let { best -> listOf(best) + given.filter { it !== best }.take(1) }.orEmpty()

        // The chosen journeys are drawn even when the model did not draw them itself.
        val routes = drawn.filterIsInstance<RouteVisual>()
        val autoRoutes = try {
            coroutineScope {
                chosen.mapIndexedNotNull { i, o ->
                    val id = o.journeyId ?: return@mapIndexedNotNull null
                    if (routes.any { it.id == turn.currentJourneyId(id) }) return@mapIndexedNotNull null
                    async { runCatching { tools.drawJourney(id, if (i == 0) "recommended" else "alternative", null, turn) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }

        val options = chosen.mapIndexed { i, o ->
            val id = o.journeyId?.let { turn.currentJourneyId(it) }
            val j = id?.let { tools.journeys.get(it) }
            AskOption(
                id = ('A' + i).toString(),
                label = o.label.ifBlank { "Option ${'A' + i}" },
                departure = j?.long("departure"),
                arrival = j?.long("arrival"),
                transfers = j?.int("transfers"),
                recommended = i == 0,
                why = o.why,
                journeyId = id,
            )
        }

        val situation = r?.situationIds?.mapNotNull { turn.situationItem(it) }?.distinctBy { it.id }
            ?: turn.situationItems().sortedBy { Severity.parse(it.severity).rank }.take(5)

        val unique = (drawn + autoRoutes).distinct()
        // The recommended route brings its origin, transfer and destination markers (unless the model marked them).
        val recommendedRoute = unique.filterIsInstance<RouteVisual>().let { rs ->
            rs.firstOrNull { it.id == options.firstOrNull()?.journeyId } ?: rs.firstOrNull { it.style == "recommended" }
        }
        val markers = recommendedRoute?.let { tools.routeMarkers(it) }.orEmpty()
            .filter { m -> unique.none { it is StationVisual && it.stopId == m.stopId } }
        val visuals = if (unique.isEmpty()) emptyList() else buildList {
            if (r?.append != true) add(ClearVisual)
            // routes first (the recommended one on top of the list), then sections, then the rest as drawn
            recommendedRoute?.let { add(it) }
            addAll(unique.filter { it is RouteVisual && it !== recommendedRoute })
            addAll(unique.filterIsInstance<SectionVisual>())
            addAll(unique.filter { it !is RouteVisual && it !is SectionVisual && it !is FocusVisual && it !is FitVisual })
            addAll(markers)
            // the explicit focus wins; otherwise fit everything drawn
            (unique.lastOrNull { it is FocusVisual || it is FitVisual } ?: tools.autoFocus(unique + markers))?.let { add(it) }
        }
        val unavailable = (r?.unavailable.orEmpty() + Scope.topicsOf(turn.request.question)).toSet().let { u -> Scope.TOPICS.filter { it in u } }
        return AskResponse(answer, r?.summary, situation, options, visuals, r?.followUp, System.currentTimeMillis() - started, unavailable, r?.append?.takeIf { it })
    }

    private class Stats {
        var calls = 0; var input = 0L; var cacheRead = 0L; var output = 0L
        val perCall = ArrayList<String>()
        fun add(m: Message, ms: Long) {
            calls++
            val u = m.usage()
            input += u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L)
            cacheRead += u.cacheReadInputTokens().orElse(0L)
            output += u.outputTokens()
            val thinking = u.outputTokensDetails().map { it.thinkingTokens() }.orElse(0L)
            perCall += "${ms}ms/${u.outputTokens()}out(${thinking}think)/${u.speed().map { it.asString() }.orElse("?")}"
        }
    }

    companion object {
        private const val LAST_CALL =
            "This is your last turn: call respond now with the best answer you can give from what you have " +
                "(drawing tools allowed in the same turn)."
        private const val HOLDING_ANSWER =
            "I'm sorry, I couldn't check the live timetable just now. Please try again in a moment, or check the SBB app."
        private const val REFUSED = "I'm sorry, I can't help with that. I can help with your train journey."

        /** The instructions followed by the knowledge pack (general railway background), one cached text block. */
        fun loadSystemPrompt(): String {
            fun res(name: String) = AssistantAgent::class.java.getResource("/assistant/$name")?.readText()?.trim()
            val instructions = res("system-prompt.md") ?: error("missing resource assistant/system-prompt.md")
            val knowledge = res("knowledge.md") ?: return instructions
            return "$instructions\n\n---\n\n$knowledge"
        }
    }
}
