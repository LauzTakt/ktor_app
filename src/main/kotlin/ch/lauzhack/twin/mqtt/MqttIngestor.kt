package ch.lauzhack.twin.mqtt

import ch.lauzhack.twin.db.IncidentRepository
import ch.lauzhack.twin.db.StationRepository
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.TrainInfo
import ch.lauzhack.twin.model.TrainPosition
import ch.lauzhack.twin.model.TwinStatus
import ch.lauzhack.twin.state.LiveState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class MqttConfig(
    val brokerUrl: String,
    val username: String?,
    val password: String?,
    val clientIdPrefix: String,
)

object Topics {
    const val ROOT = "sbb-twin/v1"
    const val STATUS = "$ROOT/status"
    const val FLEET = "$ROOT/fleet"
    const val TRAIN_INFO = "$ROOT/trains/+/info"
    const val INCIDENTS = "$ROOT/incidents/+"
    // Never subscribe to $ROOT/truth/... (evaluation only, see docs/DATA_SOURCE.md).

    /** Topic filter -> QoS. `fleet` replaces the 1 Hz per-train `position` topics (~750 msg/s) with 1 msg/s. */
    val SUBSCRIPTIONS = linkedMapOf(STATUS to 1, INCIDENTS to 1, TRAIN_INFO to 1, FLEET to 0)
}

/**
 * Subscribes to the sbb-twin feed and keeps [state] (memory) and [db] (incidents, stations) up to date.
 * Paho delivers all messages on a single callback thread, so handlers must stay fast: DB writes are queued.
 */
class MqttIngestor(
    private val cfg: MqttConfig,
    private val state: LiveState,
    private val incidents: IncidentRepository,
    private val stations: StationRepository,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(MqttIngestor::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = MqttClient(cfg.brokerUrl, "${cfg.clientIdPrefix}-${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-reconcile").apply { isDaemon = true } }

    /** Incident ids received as retained since the last (re)connect; used to detect unmarks we missed while offline. */
    private val retainedIncidents = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                log.info("MQTT {} to {}", if (reconnect) "reconnected" else "connected", serverURI)
                retainedIncidents.clear()
                val filters = Topics.SUBSCRIPTIONS.keys.toTypedArray()
                client.subscribe(filters, Topics.SUBSCRIPTIONS.values.toIntArray())
                // Retained messages arrive right after subscribing; give them time before reconciling.
                scheduler.schedule({ incidents.reconcile(retainedIncidents.toSet()) }, RECONCILE_DELAY_S, TimeUnit.SECONDS)
            }

            override fun connectionLost(cause: Throwable?) = log.warn("MQTT connection lost: {}", cause?.message)
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String, message: MqttMessage) {
                try {
                    dispatch(topic, message)
                } catch (e: Exception) {
                    log.warn("Bad message on {} ({} bytes): {}", topic, message.payload.size, e.message)
                }
            }
        })
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 30
            maxInflight = 1000
            cfg.username?.takeIf { it.isNotBlank() }?.let { userName = it }
            cfg.password?.takeIf { it.isNotBlank() }?.let { password = it.toCharArray() }
        }
        // Automatic reconnect only kicks in after a first successful connect, so retry the first one ourselves.
        scheduler.execute(object : Runnable {
            override fun run() {
                try {
                    client.connect(opts)
                } catch (e: Exception) {
                    log.warn("MQTT connect to {} failed ({}), retrying in 5 s", cfg.brokerUrl, e.message)
                    scheduler.schedule(this, 5, TimeUnit.SECONDS)
                }
            }
        })
    }

    private fun dispatch(topic: String, msg: MqttMessage) {
        val payload = msg.payload
        when {
            topic == Topics.FLEET -> onFleet(payload)
            topic.endsWith("/info") && topic.startsWith("${Topics.ROOT}/trains/") -> {
                val trainId = topic.removePrefix("${Topics.ROOT}/trains/").removeSuffix("/info")
                if (payload.isEmpty()) state.removeInfo(trainId) // tombstone: run ended
                else stations.save(state.upsertInfo(json.decodeFromString<TrainInfo>(payload.decodeToString())))
            }
            topic.startsWith("${Topics.ROOT}/incidents/") -> {
                val id = topic.removePrefix("${Topics.ROOT}/incidents/")
                if (payload.isEmpty()) {
                    if (!msg.isRetained) incidents.remove(id) // retained empty = nothing there, ignore
                } else {
                    val raw = payload.decodeToString()
                    val inc = json.decodeFromString<Incident>(raw)
                    if (msg.isRetained) retainedIncidents += inc.id
                    incidents.upsert(inc, raw)
                }
            }
            topic == Topics.STATUS -> if (payload.isNotEmpty()) state.updateStatus(json.decodeFromString<TwinStatus>(payload.decodeToString()))
        }
    }

    /** `fleet`: `{fields:[...], trains:[[...], ...]}` — map each row by field name so column order may change. */
    private fun onFleet(payload: ByteArray) {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val ts = root["ts"]!!.jsonPrimitive.long
        val streamId = root["streamId"]?.jsonPrimitive?.contentOrNull ?: ""
        val fields = root["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        val idx = fields.withIndex().associate { it.value to it.index }
        fun JsonArray.str(f: String) = idx[f]?.let { (getOrNull(it) as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.contentOrNull }
        fun JsonArray.dbl(f: String) = idx[f]?.let { (getOrNull(it) as? JsonPrimitive)?.doubleOrNull }
        fun JsonArray.int(f: String) = idx[f]?.let { (getOrNull(it) as? JsonPrimitive)?.intOrNull }

        val out = HashMap<String, TrainPosition>(root["trains"]!!.jsonArray.size * 2)
        for (el in root["trains"]!!.jsonArray) {
            val row = el as? JsonArray ?: continue
            val id = row.str("trainId") ?: continue
            out[id] = TrainPosition(
                trainId = id, lat = row.dbl("lat") ?: continue, lon = row.dbl("lon") ?: continue,
                bearing = row.dbl("bearing"), speedKmh = row.dbl("speedKmh"), motion = row.str("motion"),
                delaySec = row.int("delaySec"), line = row.str("line"), category = row.str("category"),
                atStopId = row.str("atStopId"), continuity = row.int("continuity") ?: 0, ts = ts, streamId = streamId,
            )
        }
        state.replacePositions(out, ts)
    }

    val isConnected: Boolean get() = client.isConnected

    override fun close() {
        scheduler.shutdownNow()
        runCatching { if (client.isConnected) client.disconnect(2000) }
        runCatching { client.close() }
    }

    private companion object {
        const val RECONCILE_DELAY_S = 15L
    }
}
