package ch.lauzhack.twin.push

import ch.lauzhack.twin.api.v1.ApiException
import ch.lauzhack.twin.api.v1.TwinQueries
import ch.lauzhack.twin.db.IncidentRow
import ch.lauzhack.twin.state.LiveState
import ch.lauzhack.twin.state.TrainState
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Push configuration. VAPID keys: from env, else generated once and kept in SQLite (`push_setting`), so Web Push
 * works with no setup. FCM (Android app): the Firebase service account JSON in `FCM_SERVICE_ACCOUNT_JSON` (env only,
 * raw JSON or base64); without it native app registration is refused with 503.
 */
data class PushConfig(
    val vapidPublicKey: String? = null,
    val vapidPrivateKey: String? = null,
    val vapidSubject: String = "mailto:push@train-twin.invalid",
    val fcmServiceAccount: String? = null,
    /** Lead time of the "arriving soon" notification. */
    val arrivalLeadSec: Int = 120,
    val tickSec: Int = 5,
) {
    companion object {
        fun from(conf: ApplicationConfig) = PushConfig(
            vapidPublicKey = conf.propertyOrNull("push.vapidPublicKey")?.getString()?.takeIf { it.isNotBlank() },
            vapidPrivateKey = conf.propertyOrNull("push.vapidPrivateKey")?.getString()?.takeIf { it.isNotBlank() },
            vapidSubject = conf.propertyOrNull("push.vapidSubject")?.getString()?.takeIf { it.isNotBlank() } ?: "mailto:push@train-twin.invalid",
            fcmServiceAccount = System.getenv("FCM_SERVICE_ACCOUNT_JSON")?.takeIf { it.isNotBlank() },
            arrivalLeadSec = conf.propertyOrNull("push.arrivalLeadSec")?.getString()?.toIntOrNull() ?: 120,
        )
    }
}

/**
 * Push notifications for followed trains and lines (contract: docs/API.md, "Push notifications").
 *
 * - Train (with an optional station where the passenger gets off): "arriving in N min" once the expected arrival
 *   at that station is ≤ [PushConfig.arrivalLeadSec] away, then "arriving now" when the train stands there; plus
 *   every incident concerning the train (same matching as the REST API) and its resolution. The subscription is
 *   deleted once the train has left that station, or has left service.
 * - Line: only incidents (scope = the line, the network, or anything concerning a train in service on the line)
 *   and their resolution.
 *
 * Each subscription gets one notification when an incident starts concerning it and one when it is removed
 * (`push_notified`); incidents already active when subscribing are recorded silently, so their resolution is sent.
 */
class PushService(
    private val store: PushStore,
    private val queries: TwinQueries,
    private val state: LiveState,
    val config: PushConfig = PushConfig(),
    /** Test hook: replaces the real senders (kind `web` / `fcm` -> sender). */
    senders: Map<String, PushSender>? = null,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PushService::class.java)

    val vapid: VapidKeys = loadVapid()
    private val fcm: FcmSender? = config.fcmServiceAccount?.let {
        runCatching { FcmSender(ServiceAccount.parse(it)) }
            .onSuccess { s -> log.info("Push: FCM enabled (project {})", s.projectId) }
            .onFailure { e -> log.error("FCM_SERVICE_ACCOUNT_JSON is invalid, native push disabled: {}", e.message) }
            .getOrNull()
    }
    private val senders: Map<String, PushSender> = senders
        ?: buildMap { put("web", WebPushSender(vapid, config.vapidSubject)); fcm?.let { put("fcm", it) } }

    val fcmEnabled: Boolean get() = "fcm" in senders

    /** Engine thread (incident reactions + arrival checks) and network senders. */
    private val engine = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "push-engine").apply { isDaemon = true } }
    private val sendPool = Executors.newFixedThreadPool(4) { r -> Thread(r, "push-send").apply { isDaemon = true } }

    /** subscriptionId -> when its train was first found missing (it may only be the state rebuilding after a restart). */
    private val missingSince = ConcurrentHashMap<String, Long>()

    private fun loadVapid(): VapidKeys {
        if (config.vapidPublicKey != null && config.vapidPrivateKey != null) return VapidKeys.parse(config.vapidPublicKey, config.vapidPrivateKey)
        val pub = store.setting("vapid.public"); val priv = store.setting("vapid.private")
        if (pub != null && priv != null) runCatching { return VapidKeys.parse(pub, priv) }
        val k = VapidKeys.generate()
        store.putSetting("vapid.public", k.publicKey); store.putSetting("vapid.private", k.privateKeyEncoded())
        log.info("Push: generated a VAPID key pair (kept in SQLite)")
        return k
    }

    /** Hooks into incident changes and starts the arrival checks. */
    fun start(addIncidentListener: ((String, IncidentRow) -> Unit) -> Unit) {
        addIncidentListener { kind, row -> engine.execute { runCatching { onIncident(kind, row) }.onFailure { log.warn("push incident handling failed", it) } } }
        engine.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { log.warn("push tick failed", it) } }, config.tickSec.toLong(), config.tickSec.toLong(), TimeUnit.SECONDS)
    }

    override fun close() {
        engine.shutdownNow(); sendPool.shutdown()
    }

    // ---------------------------------------------------------------- API operations

    fun registerWeb(endpoint: String, p256dh: String, auth: String, platform: String?): PushDevice {
        if (!endpoint.startsWith("https://")) bad("bad_endpoint", "endpoint must be an https URL")
        runCatching { P256.decodePublic(unb64url(p256dh)) }.onFailure { bad("bad_keys", "keys.p256dh must be a base64url P-256 public key") }
        runCatching { require(unb64url(auth).size >= 16) }.onFailure { bad("bad_keys", "keys.auth must be a base64url 16-byte secret") }
        return store.upsertDevice("web", endpoint, p256dh, auth, platform ?: "web", now())
    }

    fun registerFcm(token: String, platform: String?): PushDevice {
        if (!fcmEnabled) throw ApiException(HttpStatusCode.ServiceUnavailable, "fcm_disabled", "native push is not configured on this server (FCM_SERVICE_ACCOUNT_JSON)")
        if (token.length !in 20..4096) bad("bad_token", "token must be an FCM registration token")
        return store.upsertDevice("fcm", token, null, null, platform, now())
    }

    fun device(deviceId: String): PushDevice =
        store.device(deviceId) ?: throw ApiException(HttpStatusCode.NotFound, "device_not_found", "device '$deviceId' not found")

    fun deleteDevice(deviceId: String) {
        if (!store.deleteDevice(deviceId)) throw ApiException(HttpStatusCode.NotFound, "device_not_found", "device '$deviceId' not found")
    }

    fun subscriptions(deviceId: String): List<PushSubscription> { device(deviceId); return store.subscriptions(deviceId) }

    fun followTrain(deviceId: String, trainId: String, stopId: String?): PushSubscription {
        device(deviceId)
        val t = queries.trainState(trainId) ?: throw ApiException(HttpStatusCode.NotFound, "train_not_found", "train '$trainId' not found")
        val stopName = stopId?.let { id ->
            val i = queries.upcomingIndexAt(t, id) ?: bad("bad_stopId", "train '$trainId' does not call at '$id' any more")
            t.info!!.calls[i].name
        }
        val sub = store.upsertSubscription(deviceId, "train", trainId, stopId, trainLabel(t), stopName, now())
        queries.activeIncidentRows().filter { queries.trainIncidentMatch(it, t) != null }.forEach { store.markNotified(sub.id, it.id) }
        return sub
    }

    fun followLine(deviceId: String, line: String): PushSubscription {
        device(deviceId)
        val trains = queries.trainsOnLine(line.trim())
        if (trains.isEmpty()) throw ApiException(HttpStatusCode.NotFound, "line_not_found", "no train in service on line '$line'")
        val name = trains.first().info?.line ?: trains.first().position?.line ?: line.trim()
        val sub = store.upsertSubscription(deviceId, "line", name, null, "Line $name", null, now())
        queries.activeIncidentRows().filter { queries.lineIncidentMatch(it, name, trains) != null }.forEach { store.markNotified(sub.id, it.id) }
        return sub
    }

    fun unfollow(deviceId: String, subscriptionId: String) {
        if (!store.deleteSubscription(deviceId, subscriptionId)) {
            throw ApiException(HttpStatusCode.NotFound, "subscription_not_found", "subscription '$subscriptionId' not found")
        }
    }

    /** Sends a test notification right away (synchronously), so the client can check the whole chain. */
    fun test(deviceId: String): SendResult {
        val d = device(deviceId)
        val r = deliver(d, PushMessage("Notifications are on", "You will get alerts for the trains and lines you follow.", "/", "test"))
        if (r == SendResult.GONE) store.deleteDevice(d.id)
        return r
    }

    // ---------------------------------------------------------------- engine

    internal fun onIncident(kind: String, row: IncidentRow) {
        if (kind == "removed" || kind == "missing") {
            store.takeNotified(row.id).forEach { s ->
                send(s, PushMessage("Resolved: ${row.title}", "${s.label ?: s.target}: this incident is over.", urlOf(s), "inc-${row.id}"))
            }
            return
        }
        val lineTrains = HashMap<String, List<TrainState>>()
        for (s in store.allSubscriptions()) {
            val match = when (s.kind) {
                "train" -> queries.trainState(s.target)?.let { queries.trainIncidentMatch(row, it) }
                "line" -> queries.lineIncidentMatch(row, s.target, lineTrains.getOrPut(s.target.uppercase()) { queries.trainsOnLine(s.target) })
                else -> null
            } ?: continue
            if (!store.markNotified(s.id, row.id)) continue // already told (an update of the same incident)
            val where = row.where?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            send(s, PushMessage("${severityWord(row.severity)}: ${row.title}", "${s.label ?: s.target}$where", urlOf(s), "inc-${row.id}"))
        }
    }

    internal fun tick() {
        val now = state.now()
        val stateFresh = state.trainCount() > 0 && System.currentTimeMillis() - state.lastFleetTs < 30_000
        for (s in store.allSubscriptions()) {
            if (s.kind != "train") continue
            val t = queries.trainState(s.target)
            if (t?.info == null) {
                // Gone for 2 min while the feed is live = left service. (Right after a restart the state is still empty.)
                if (stateFresh) {
                    val since = missingSince.getOrPut(s.id) { now }
                    if (now - since > 120_000) { store.deleteSubscription(s.id); missingSince.remove(s.id) }
                }
                continue
            }
            missingSince.remove(s.id)
            val stopId = s.stopId ?: continue
            val idx = queries.upcomingIndexAt(t, stopId)
            if (idx == null) { store.deleteSubscription(s.id); continue } // left the station: the trip is over
            val call = t.info.calls[idx]
            val stopName = s.stopName ?: call.name
            val standing = idx == t.nextCallIndex && t.position?.atStopId == stopId
            if (standing) {
                if (!s.arrivedSent) {
                    send(s, PushMessage("Now at $stopName", "${s.label ?: "Your train"} has arrived at $stopName.", urlOf(s), "arr-${s.id}"))
                    store.markArrivalSent(s.id, approaching = true, arrived = true)
                }
                continue
            }
            val eta = call.expectedArr ?: call.plannedArr ?: continue
            if (!s.approachingSent && eta - now <= config.arrivalLeadSec * 1000L) {
                val min = ((eta - now + 30_000) / 60_000).coerceAtLeast(0)
                val title = if (min <= 0) "Arriving at $stopName" else "$stopName in $min min"
                val delay = call.plannedArr?.let { p -> ((eta - p) / 60_000).takeIf { it > 0 }?.let { " (+$it')" } } ?: ""
                send(s, PushMessage(title, "${s.label ?: "Your train"} · arrival ${hhmm(eta)}$delay. Get ready to get off.", urlOf(s), "arr-${s.id}"))
                store.markArrivalSent(s.id, approaching = true, arrived = false)
            }
        }
    }

    // ---------------------------------------------------------------- delivery

    private fun send(s: PushSubscription, msg: PushMessage) {
        val d = store.device(s.deviceId) ?: return
        sendPool.execute {
            if (deliver(d, msg) == SendResult.GONE) {
                log.info("Push: device {} ({}) is gone, deleting it", d.id, d.kind)
                store.deleteDevice(d.id)
            }
        }
    }

    private fun deliver(d: PushDevice, msg: PushMessage): SendResult {
        val sender = senders[d.kind] ?: return SendResult.FAILED
        return sender.send(d, msg).also { log.debug("push {} -> {}: {}", d.id, it, msg.title) }
    }

    private fun now() = System.currentTimeMillis()

    private fun trainLabel(t: TrainState): String {
        val line = t.info?.line ?: t.position?.line ?: t.info?.category ?: "Train"
        val to = t.info?.headsign ?: t.info?.destination?.name
        return if (to != null) "$line to $to" else line
    }

    private fun urlOf(s: PushSubscription) =
        if (s.kind == "train") "/?train=${enc(s.target)}" else "/?line=${enc(s.target)}"

    private fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")

    private fun severityWord(s: String?) = when (s?.lowercase()) {
        "critical" -> "Severe disruption"
        "major" -> "Disruption"
        "minor" -> "Incident"
        else -> "Information"
    }

    private fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, msg)

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Zurich"))
        fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms))
    }
}
