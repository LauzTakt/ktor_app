package ch.lauzhack.twin.api.v1

import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.db.IncidentRow
import ch.lauzhack.twin.query.BoardDto
import ch.lauzhack.twin.query.BoardType
import ch.lauzhack.twin.query.IncidentDto
import ch.lauzhack.twin.query.ScreenDto
import ch.lauzhack.twin.query.TrainLiveDto
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.query.toDto
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/*
 * WebSocket `/api/v1/ws` (contract: docs/API.md, "WebSocket").
 *
 * A client subscribes to trains and/or stations. Per subscription:
 *  - `train_update` / `station_update` are pushed every `frequencySec` (the client's choice);
 *  - `incident` notifications are pushed IMMEDIATELY when a related incident is created / updated / removed,
 *    independent of the frequency. On subscribe, the currently active related incidents are sent once (`event: active`).
 * "Related" = same matching as the REST API: for a train, incidents on its remaining route / line / the train itself;
 * for a station, incidents at the station itself plus incidents of every train that will still call at / pass it.
 *
 * Topic `screen` (small displays): one compact `screen_update` (ScreenDto: next departures + related incidents) every
 * `frequencySec`, and again immediately whenever an incident is created / updated / removed. No separate `incident`
 * messages, so a microcontroller only has to parse one small document.
 */

// ---------------------------------------------------------------- protocol

@Serializable
data class WsRequest(
    /** `subscribe` | `unsubscribe` | `ping`. */
    val op: String,
    /** `train` | `station` | `screen`. */
    val topic: String? = null,
    /** trainId or stopId. */
    val id: String? = null,
    /** Seconds between `*_update` messages (1..3600, default 5). Incident notifications ignore it. */
    val frequencySec: Int? = null,
    /** Station / screen: board window in minutes (1..1440, default 120). */
    val windowMin: Int? = null,
    /** Screen only: number of departures (1..20, default 6). */
    val limit: Int? = null,
)

@Serializable
data class WsSubscribed(
    val topic: String, val id: String, val frequencySec: Int, val windowMin: Int?, val limit: Int? = null,
    val type: String = "subscribed",
)

@Serializable
data class WsUnsubscribed(val topic: String, val id: String, val type: String = "unsubscribed")

@Serializable
data class WsTrainUpdate(val id: String, val ts: Long, val data: TrainLiveDto, val topic: String = "train", val type: String = "train_update")

@Serializable
data class WsStationUpdate(val id: String, val ts: Long, val data: BoardDto, val topic: String = "station", val type: String = "station_update")

@Serializable
data class WsScreenUpdate(val id: String, val ts: Long, val data: ScreenDto, val topic: String = "screen", val type: String = "screen_update")

/** The train left service (its run ended): the subscription is closed by the server. */
@Serializable
data class WsTrainEnded(val id: String, val ts: Long, val topic: String = "train", val type: String = "train_ended")

@Serializable
data class TrainMatchDto(val trainId: String, val match: String)

@Serializable
data class WsIncident(
    val topic: String,
    val id: String,
    /** `active` (snapshot on subscribe) | `created` | `updated` | `removed` | `missing`. */
    val event: String,
    val ts: Long,
    /** `incident.match` = relation to the subscribed train, or to the station itself (null if only via its trains). */
    val incident: IncidentDto,
    /** Affected trains with their relation: the train itself for a train subscription, upcoming trains for a station. */
    val trains: List<TrainMatchDto>,
    val type: String = "incident",
)

@Serializable
data class WsError(val code: String, val message: String, val topic: String? = null, val id: String? = null, val type: String = "error")

@Serializable
data class WsPong(val ts: Long, val type: String = "pong")

// ---------------------------------------------------------------- hub

/** Fan-out of incident changes from [Database] to every WebSocket session. One per application. */
class LiveUpdates(val queries: TwinQueries, db: Database, private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class IncidentChange(val kind: String, val incident: IncidentRow)

    // Incidents are rare (a few per minute at most); a large buffer + DROP_OLDEST never blocks the SQLite writer thread.
    private val _incidents = MutableSharedFlow<IncidentChange>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incidents: SharedFlow<IncidentChange> = _incidents

    init {
        db.addIncidentListener { kind, row -> _incidents.tryEmit(IncidentChange(kind, row)) }
    }

    fun now() = clock()

    companion object {
        const val DEFAULT_FREQUENCY_SEC = 5
        const val MAX_FREQUENCY_SEC = 3600
        const val DEFAULT_WINDOW_MIN = 120
        const val MAX_SUBSCRIPTIONS = 100
        const val BOARD_LIMIT = 500
        const val DEFAULT_SCREEN_LIMIT = 6
        const val MAX_SCREEN_LIMIT = 20
    }
}

// ---------------------------------------------------------------- per connection

private val wsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
private val log = LoggerFactory.getLogger("ws")

fun Route.liveUpdatesWs(hub: LiveUpdates) = webSocket("/api/v1/ws") {
    WsConnection(this, hub).run()
}

private class WsConnection(private val session: DefaultWebSocketServerSession, private val hub: LiveUpdates) {
    private val q = hub.queries

    private sealed interface Sub {
        val topic: String
        val id: String
        val job: Job
    }
    private class TrainSub(override val id: String, override val job: Job) : Sub { override val topic = "train" }
    private class StationSub(override val id: String, override val job: Job) : Sub { override val topic = "station" }
    private class ScreenSub(override val id: String, override val job: Job, val limit: Int, val windowMin: Int) : Sub {
        override val topic = "screen"
    }

    /** "train:<id>" / "station:<id>" -> subscription. */
    private val subs = ConcurrentHashMap<String, Sub>()

    suspend fun run() {
        // Incident notifications: independent of every subscription's frequency.
        val incidentJob = session.launch { hub.incidents.collect { onIncident(it) } }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val req = try {
                    wsJson.decodeFromString(WsRequest.serializer(), frame.readText())
                } catch (e: Exception) {
                    send(WsError.serializer(), WsError("bad_request", "invalid JSON request: ${e.message?.take(200)}"))
                    continue
                }
                handle(req)
            }
        } finally {
            incidentJob.cancel()
            subs.values.forEach { it.job.cancel() }
        }
    }

    private suspend fun handle(r: WsRequest) {
        when (r.op) {
            "ping" -> send(WsPong.serializer(), WsPong(hub.now()))
            "subscribe" -> subscribe(r)
            "unsubscribe" -> {
                val (topic, id) = target(r) ?: return
                subs.remove("$topic:$id")?.job?.cancel()
                send(WsUnsubscribed.serializer(), WsUnsubscribed(topic, id))
            }
            else -> send(WsError.serializer(), WsError("bad_op", "op must be subscribe, unsubscribe or ping"))
        }
    }

    private suspend fun target(r: WsRequest): Pair<String, String>? {
        if (r.topic != "train" && r.topic != "station" && r.topic != "screen") {
            send(WsError.serializer(), WsError("bad_topic", "topic must be train, station or screen", r.topic, r.id)); return null
        }
        if (r.id.isNullOrBlank()) {
            send(WsError.serializer(), WsError("bad_id", "id (trainId or stopId) is required", r.topic, r.id)); return null
        }
        return r.topic to r.id
    }

    private suspend fun subscribe(r: WsRequest) {
        val (topic, id) = target(r) ?: return
        val freq = r.frequencySec ?: LiveUpdates.DEFAULT_FREQUENCY_SEC
        if (freq !in 1..LiveUpdates.MAX_FREQUENCY_SEC) {
            return send(WsError.serializer(), WsError("bad_frequencySec", "frequencySec must be between 1 and ${LiveUpdates.MAX_FREQUENCY_SEC}", topic, id))
        }
        val window = r.windowMin ?: LiveUpdates.DEFAULT_WINDOW_MIN
        if (window !in 1..24 * 60) {
            return send(WsError.serializer(), WsError("bad_windowMin", "windowMin must be between 1 and 1440", topic, id))
        }
        val limit = r.limit ?: LiveUpdates.DEFAULT_SCREEN_LIMIT
        if (topic == "screen" && limit !in 1..LiveUpdates.MAX_SCREEN_LIMIT) {
            return send(WsError.serializer(), WsError("bad_limit", "limit must be between 1 and ${LiveUpdates.MAX_SCREEN_LIMIT}", topic, id))
        }
        val key = "$topic:$id"
        if (!subs.containsKey(key) && subs.size >= LiveUpdates.MAX_SUBSCRIPTIONS) {
            return send(WsError.serializer(), WsError("too_many_subscriptions", "max ${LiveUpdates.MAX_SUBSCRIPTIONS} per connection", topic, id))
        }
        val known = if (topic == "train") q.trainLive(id) != null else runCatching { q.station(id) }.isSuccess
        val what = if (topic == "train") "train" else "station"   // a screen is a station's
        if (!known) return send(WsError.serializer(), WsError("${what}_not_found", "$what '$id' not found", topic, id))

        // Re-subscribing replaces the previous subscription (e.g. to change the frequency).
        subs.remove(key)?.job?.cancel()
        if (topic == "screen") {
            send(WsSubscribed.serializer(), WsSubscribed(topic, id, freq, window, limit))
            subs[key] = ScreenSub(id, session.launch { screenLoop(id, freq, limit, window) }, limit, window)
            return
        }
        send(WsSubscribed.serializer(), WsSubscribed(topic, id, freq, window.takeIf { topic == "station" }))
        incidentSnapshot(topic, id)
        val job = if (topic == "train") session.launch { trainLoop(key, id, freq) } else session.launch { stationLoop(id, freq, window) }
        subs[key] = if (topic == "train") TrainSub(id, job) else StationSub(id, job)
    }

    private suspend fun CoroutineScope.trainLoop(key: String, trainId: String, freqSec: Int) {
        while (isActive) {
            val live = q.trainLive(trainId)
            if (live == null) {
                send(WsTrainEnded.serializer(), WsTrainEnded(trainId, hub.now()))
                subs.remove(key)
                return
            }
            send(WsTrainUpdate.serializer(), WsTrainUpdate(trainId, hub.now(), live))
            delay(freqSec * 1000L)
        }
    }

    private suspend fun CoroutineScope.stationLoop(stopId: String, freqSec: Int, windowMin: Int) {
        while (isActive) {
            val board = q.board(stopId, BoardType.all, windowMin, LiveUpdates.BOARD_LIMIT, includeSkipped = true)
            send(WsStationUpdate.serializer(), WsStationUpdate(stopId, hub.now(), board))
            delay(freqSec * 1000L)
        }
    }

    private suspend fun CoroutineScope.screenLoop(stopId: String, freqSec: Int, limit: Int, windowMin: Int) {
        while (isActive) {
            sendScreen(stopId, limit, windowMin)
            delay(freqSec * 1000L)
        }
    }

    private suspend fun sendScreen(stopId: String, limit: Int, windowMin: Int) =
        send(WsScreenUpdate.serializer(), WsScreenUpdate(stopId, hub.now(), q.screen(stopId, limit, windowMin)))

    // ---------------------------------------------------------------- incidents

    private suspend fun incidentSnapshot(topic: String, id: String) {
        q.activeIncidentRows().forEach { row -> relate(topic, id, row)?.let { send(WsIncident.serializer(), it.copy(event = "active")) } }
    }

    private suspend fun onIncident(c: LiveUpdates.IncidentChange) {
        for (s in subs.values) {
            if (s is ScreenSub) {
                // Removed incidents no longer match anything: refresh every screen; otherwise only related ones.
                if (c.kind == "removed" || c.kind == "missing" || relate("station", s.id, c.incident) != null) {
                    sendScreen(s.id, s.limit, s.windowMin)
                }
                continue
            }
            relate(s.topic, s.id, c.incident)?.let { send(WsIncident.serializer(), it.copy(event = c.kind)) }
        }
    }

    /** The notification for subscription ([topic], [id]) if [row] concerns it, else null. */
    private fun relate(topic: String, id: String, row: IncidentRow): WsIncident? {
        return if (topic == "train") {
            val t = q.trainState(id) ?: return null
            val m = q.trainIncidentMatch(row, t) ?: return null
            WsIncident(topic, id, "", hub.now(), row.toDto(m), listOf(TrainMatchDto(id, m)))
        } else {
            val stationMatch = q.stationIncidentMatch(row, id)
            val trains = q.trainsUpcomingAt(id).mapNotNull { t -> q.trainIncidentMatch(row, t)?.let { TrainMatchDto(t.trainId, it) } }
                .sortedBy { it.trainId }
            if (stationMatch == null && trains.isEmpty()) null
            else WsIncident(topic, id, "", hub.now(), row.toDto(stationMatch), trains)
        }
    }

    private suspend fun <T> send(ser: KSerializer<T>, msg: T) {
        try {
            session.send(Frame.Text(wsJson.encodeToString(ser, msg)))
        } catch (e: Exception) {
            log.debug("ws send failed: {}", e.message) // connection closing; the read loop ends and cleans up
        }
    }
}
