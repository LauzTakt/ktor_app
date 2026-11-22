package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.common.EMPTY_ARRAY
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

internal class GoogleWallet(
    private val classId: String,
    private val email: String,
    private val key: PrivateKey,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build(),
    private val api: String = "https://walletobjects.googleapis.com/walletobjects/v1",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) {
    private val mutex = Mutex()
    private var access: Pair<String, Long>? = null

    private fun objectId(watch: TripWatch) = "${classId.substringBefore('.')}.trip-${watch.id}"

    suspend fun ensurePass(watch: TripWatch, site: String) {
        val result = request("GET", "/genericObject/${objectId(watch)}")
        if (result.statusCode() == 404) {
            val created = request("POST", "/genericObject", pass(watch, site))
            if (created.statusCode() != 409) created.checkOk()
        } else result.checkOk()
    }

    fun saveUrl(watch: TripWatch): String {
        val claims = buildJsonObject {
            put("iss", email); put("aud", "google"); put("typ", "savetowallet")
            put("iat", Instant.now().epochSecond)
            put("origins", EMPTY_ARRAY)
            put("payload", buildJsonObject {
                put("genericObjects", buildJsonArray {
                    add(buildJsonObject { put("id", objectId(watch)); put("classId", classId) })
                })
            })
        }
        return "https://pay.google.com/gp/v/save/${signed(claims)}"
    }

    suspend fun update(watch: TripWatch, site: String, notify: Boolean) {
        val objectPath = "/genericObject/${objectId(watch)}"
        val result = request("PATCH", objectPath, pass(watch, site))
        // An object can be revoked or removed by the issuer. Recreating it would not resubscribe the passenger.
        if (result.statusCode() == 404) return
        result.checkOk()
        if (!notify) return
        val msg = "update-${watch.alertRevision}"
        val body = Json.parseToJsonElement(result.body()) as? JsonObject ?: return
        if ((body["messages"] as? JsonArray).orEmpty().any { entry ->
                ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == msg
            }) return
        request("POST", "$objectPath/addMessage", buildJsonObject {
            put("message", buildJsonObject {
                put("id", msg); put("header", "Trip Watch")
                put("body", watch.alertText ?: watch.instruction()); put("messageType", "TEXT_AND_NOTIFY")
            })
        }).checkOk()
    }

    internal fun pass(watch: TripWatch, site: String): JsonObject {
        val station = watch.relevantLeg()?.from
        val face = watch.passFace(site)
        return buildJsonObject {
            put("id", objectId(watch)); put("classId", classId)
            put("state", if (watch.ended) "EXPIRED" else "ACTIVE")
            // Same face as the Apple pass: white card, red bar logo, departure, route and a QR code to the watch page.
            put("hexBackgroundColor", "#ffffff")
            put("cardTitle", localized(PASS_BRAND))
            put("subheader", localized("Departure ${face.departure}"))
            put("header", localized("${face.origin} → ${face.destination}"))
            put("logo", buildJsonObject {
                put("sourceUri", buildJsonObject { put("uri", "$site/wallet/logo.png") })
                put("contentDescription", localized(PASS_BRAND))
            })
            put("barcode", buildJsonObject {
                put("type", "QR_CODE"); put("value", face.url); put("alternateText", PASS_NOTICE)
            })
            put("merchantLocations", buildJsonArray {
                if (station?.lat != null && station.lon != null) add(buildJsonObject {
                    put("latitude", station.lat); put("longitude", station.lon)
                })
            })
            put("validTimeInterval", buildJsonObject {
                val end = if (watch.ended) minOf(watch.expiresAt, watch.updatedAt) else watch.expiresAt
                put("end", buildJsonObject { put("date", Instant.ofEpochMilli(end).toString()) })
            })
            put("textModulesData", buildJsonArray {
                add(textModule("next", "Next step", face.nextStep))
                add(textModule("line", "Line", "${face.line} · ${face.changes}"))
                watch.advice.detail?.let { add(textModule("detail", "Trip update", it)) }
                add(textModule("notice", "About this pass", PASS_ABOUT))
            })
            put("linksModuleData", buildJsonObject {
                put("uris", buildJsonArray { add(buildJsonObject {
                    put("id", "trip"); put("uri", face.url); put("description", "Open Trip Watch")
                }) })
            })
        }
    }

    private fun localized(value: String) = buildJsonObject {
        put("defaultValue", buildJsonObject { put("language", "en"); put("value", value) })
    }

    private fun textModule(id: String, header: String, body: String) = buildJsonObject {
        put("id", id); put("header", header); put("body", body)
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("$api$path")).timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer ${token()}")
            .header("Content-Type", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
    }

    private suspend fun token(): String = mutex.withLock {
        val at = Instant.now().epochSecond
        access?.takeIf { it.second > at + 60 }?.let { return@withLock it.first }
        val claim = buildJsonObject {
            put("iss", email); put("scope", "https://www.googleapis.com/auth/wallet_object.issuer")
            put("aud", tokenEndpoint); put("iat", at); put("exp", at + 3600)
        }
        val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${URLEncoder.encode(signed(claim), Charsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI.create(tokenEndpoint)).timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        response.checkOk()
        val body = Json.parseToJsonElement(response.body()) as JsonObject
        val token = (body["access_token"] as JsonPrimitive).content
        access = token to (at + ((body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600))
        token
    }

    private fun signed(claim: JsonObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload = encoder.encodeToString(claim.toString().toByteArray())
        val data = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key); update(data.toByteArray())
        }.sign()
        return "$data.${encoder.encodeToString(signature)}"
    }

    private fun HttpResponse<String>.checkOk() {
        check(statusCode() in 200..299) { "Google Wallet returned HTTP ${statusCode()}" }
    }

    companion object {
        internal fun serviceAccountJson(value: String): String {
            val v = value.trim()
            if (v.startsWith("{")) return v
            val file = runCatching { Path.of(v) }.getOrNull()
            if (file != null && Files.isRegularFile(file)) return Files.readString(file)
            val decoded = runCatching { String(Base64.getMimeDecoder().decode(v)).trim() }.getOrNull()
            require(decoded?.startsWith("{") == true) {
                "GOOGLE_APPLICATION_CREDENTIALS must be a JSON file path, or the service account JSON (raw or base64)"
            }
            return decoded!!
        }

        /** [credentials]: path to the service account JSON file, or the JSON itself (raw or base64, for Coolify). */
        fun load(classId: String, credentials: String): GoogleWallet {
            require(Regex("[0-9]+\\.[a-zA-Z0-9_.-]+").matches(classId)) { "Invalid Google Wallet class ID" }
            val credential = Json.parseToJsonElement(serviceAccountJson(credentials)) as JsonObject
            val email = (credential["client_email"] as JsonPrimitive).content
            val pem = (credential["private_key"] as JsonPrimitive).content
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            return GoogleWallet(classId, email, key)
        }
    }
}
