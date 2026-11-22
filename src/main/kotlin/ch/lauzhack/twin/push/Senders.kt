package ch.lauzhack.twin.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

/** What a notification says. [url] opens when it is tapped; [tag] replaces an earlier notification with the same tag. */
@Serializable
data class PushMessage(val title: String, val body: String, val url: String = "/", val tag: String? = null)

enum class SendResult {
    OK,
    /** The endpoint / token is no longer valid (unsubscribed, app uninstalled): delete the device. */
    GONE,
    FAILED,
}

fun interface PushSender {
    fun send(device: PushDevice, msg: PushMessage): SendResult
}

private val log = LoggerFactory.getLogger("push")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/** Web Push (browsers, iOS home-screen web app). */
class WebPushSender(private val keys: VapidKeys, private val subject: String) : PushSender {
    override fun send(device: PushDevice, msg: PushMessage): SendResult {
        val p256dh = device.p256dh ?: return SendResult.GONE
        val auth = device.auth ?: return SendResult.GONE
        return try {
            val body = WebPushCrypto.encrypt(json.encodeToString(PushMessage.serializer(), msg).toByteArray(), unb64url(p256dh), unb64url(auth))
            val req = HttpRequest.newBuilder(URI(device.address))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "3600")
                .header("Urgency", "high")
                .header("Authorization", "vapid t=${WebPushCrypto.vapidJwt(device.address, keys, subject)}, k=${keys.publicKey}")
                .apply { msg.tag?.let { header("Topic", it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(32)) } }
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            when (res.statusCode()) {
                in 200..299 -> SendResult.OK
                404, 410 -> SendResult.GONE
                else -> { log.warn("web push {} -> HTTP {}: {}", device.id, res.statusCode(), res.body().take(300)); SendResult.FAILED }
            }
        } catch (e: Exception) {
            log.warn("web push {} failed: {}", device.id, e.message)
            SendResult.FAILED
        }
    }
}

/** Google service account (the JSON downloaded from Firebase console -> Project settings -> Service accounts). */
@Serializable
data class ServiceAccount(
    val project_id: String,
    val client_email: String,
    val private_key: String,
    val token_uri: String = "https://oauth2.googleapis.com/token",
) {
    companion object {
        /** Accepts the JSON itself or its base64 (easier to paste into an env var). */
        fun parse(raw: String): ServiceAccount {
            val text = raw.trim().let { if (it.startsWith("{")) it else String(Base64.getMimeDecoder().decode(it)) }
            return json.decodeFromString(serializer(), text)
        }
    }
}

/** Firebase Cloud Messaging HTTP v1: Android app (and iOS once APNs is configured in Firebase). */
class FcmSender(private val account: ServiceAccount) : PushSender {
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                account.private_key.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\n", "\n"),
            ),
        ),
    )
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt = 0L

    val projectId: String get() = account.project_id

    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpiresAt - 60_000) return it }
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val iat = now / 1000
        val claims = enc.encodeToString(
            buildJsonObject {
                put("iss", account.client_email)
                put("scope", "https://www.googleapis.com/auth/firebase.messaging")
                put("aud", account.token_uri)
                put("iat", iat); put("exp", iat + 3600)
            }.toString().toByteArray(),
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update("$header.$claims".toByteArray()) }.sign()
        val assertion = "$header.$claims.${enc.encodeToString(sig)}"
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, Charsets.UTF_8)
        val res = http.send(
            HttpRequest.newBuilder(URI(account.token_uri)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "FCM OAuth token: HTTP ${res.statusCode()} ${res.body().take(300)}" }
        val o = json.parseToJsonElement(res.body()).jsonObject
        val t = o["access_token"]!!.jsonPrimitive.content
        token = t
        tokenExpiresAt = now + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) * 1000
        return t
    }

    internal fun body(deviceToken: String, msg: PushMessage): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", deviceToken)
            putJsonObject("notification") { put("title", msg.title); put("body", msg.body) }
            putJsonObject("data") { put("url", msg.url); msg.tag?.let { put("tag", it) } }
            putJsonObject("android") {
                put("priority", "HIGH")
                putJsonObject("notification") { msg.tag?.let { put("tag", it) }; put("default_sound", true) }
            }
            putJsonObject("apns") {
                putJsonObject("payload") { putJsonObject("aps") { put("sound", "default") } }
                msg.tag?.let { putJsonObject("headers") { put("apns-collapse-id", it.take(64)) } }
            }
        }
    }

    override fun send(device: PushDevice, msg: PushMessage): SendResult = try {
        val res = http.send(
            HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer ${accessToken()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(device.address, msg).toString())).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val b = res.body()
        when {
            res.statusCode() in 200..299 -> SendResult.OK
            res.statusCode() == 404 || "UNREGISTERED" in b || ("INVALID_ARGUMENT" in b && "token" in b.lowercase()) -> SendResult.GONE
            else -> { log.warn("fcm {} -> HTTP {}: {}", device.id, res.statusCode(), b.take(300)); SendResult.FAILED }
        }
    } catch (e: Exception) {
        log.warn("fcm {} failed: {}", device.id, e.message)
        SendResult.FAILED
    }
}
