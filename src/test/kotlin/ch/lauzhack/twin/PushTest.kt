package ch.lauzhack.twin

import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.model.Incident
import ch.lauzhack.twin.model.IncidentScope
import ch.lauzhack.twin.push.FcmSender
import ch.lauzhack.twin.push.P256
import ch.lauzhack.twin.push.PushConfig
import ch.lauzhack.twin.push.PushDevice
import ch.lauzhack.twin.push.PushMessage
import ch.lauzhack.twin.push.PushSender
import ch.lauzhack.twin.push.PushService
import ch.lauzhack.twin.push.PushStore
import ch.lauzhack.twin.push.SendResult
import ch.lauzhack.twin.push.VapidKeys
import ch.lauzhack.twin.push.WebPushCrypto
import ch.lauzhack.twin.push.b64url
import ch.lauzhack.twin.push.unb64url
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushTest {

    // ---------------------------------------------------------------- crypto

    /** Receiver side of RFC 8291, written independently of the sender, to check the round trip. */
    private fun decrypt(body: ByteArray, uaPrivate: ECPrivateKey, uaPublic: ByteArray, auth: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buf.get(it) }
        buf.int // record size
        val asPublic = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val record = ByteArray(buf.remaining()).also { buf.get(it) }
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(d)
        val ecdh = KeyAgreement.getInstance("ECDH").apply { init(uaPrivate); doPhase(P256.decodePublic(asPublic), true) }.generateSecret()
        val ikm = hmac(hmac(auth, ecdh), "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(record)
        assertEquals(2, plain.last().toInt(), "last-record padding delimiter")
        return plain.copyOf(plain.size - 1)
    }

    @Test
    fun webPushPayloadDecryptsOnTheReceiverSide() {
        val ua = P256.generate()
        val uaPublic = P256.encode(ua.public as ECPublicKey)
        val auth = ByteArray(16) { it.toByte() }
        val body = WebPushCrypto.encrypt("""{"title":"Bern in 2 min"}""".toByteArray(), uaPublic, auth)
        assertEquals(4096, ByteBuffer.wrap(body, 16, 4).int)
        assertEquals("""{"title":"Bern in 2 min"}""", String(decrypt(body, ua.private as ECPrivateKey, uaPublic, auth)))
    }

    @Test
    fun vapidJwtIsSignedWithThePublishedKey() {
        val keys = VapidKeys.generate()
        val jwt = WebPushCrypto.vapidJwt("https://fcm.googleapis.com/fcm/send/abc", keys, "mailto:x@example.org", nowSec = 1000)
        val (h, c, s) = jwt.split(".")
        assertTrue(String(unb64url(c)).contains(""""aud":"https://fcm.googleapis.com""""))
        assertTrue(String(unb64url(c)).contains(""""exp":44200"""))
        val pub = P256.decodePublic(unb64url(keys.publicKey))
        val ok = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pub); update("$h.$c".toByteArray()) }.verify(unb64url(s))
        assertTrue(ok)
        // Stored form parses back to the same key pair.
        val again = VapidKeys.parse(keys.publicKey, keys.privateKeyEncoded())
        assertEquals(keys.publicKey, again.publicKey)
    }

    @Test
    fun fcmMessageCarriesTitleUrlAndTag() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val s = FcmSender(ch.lauzhack.twin.push.ServiceAccount("p", "sa@p.iam.gserviceaccount.com", pem))
        val m = s.body("tok", PushMessage("T", "B", "/?train=x", "inc-1"))["message"]!!.jsonObject
        assertEquals("tok", m["token"]!!.jsonPrimitive.content)
        assertEquals("T", m["notification"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("/?train=x", m["data"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("inc-1", m["android"]!!.jsonObject["notification"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- engine

    private class Fake : PushSender {
        val sent = CopyOnWriteArrayList<Pair<String, PushMessage>>()
        var result = SendResult.OK
        override fun send(device: PushDevice, msg: PushMessage): SendResult { sent += device.id to msg; return result }
        fun titles() = sent.map { it.second.title }
        fun await(n: Int) { repeat(100) { if (sent.size >= n) return; Thread.sleep(20) } }
    }

    private class Env {
        val st = LiveState()
        val db = Database(createTempFile("twin", ".sqlite").toString())
        val fake = Fake()
        val q = TwinQueries(st) { db.incidents(activeOnly = true) }
        val push = PushService(PushStore(db), q, st, PushConfig(arrivalLeadSec = 120), mapOf("web" to fake, "fcm" to fake))
        val device = push.registerWeb("https://push.example.org/e1", b64url.encodeToString(P256.encode(P256.generate().public as ECPublicKey)), b64url.encodeToString(ByteArray(16)), null)

        init {
            st.upsertInfo(info("t1", aheadFrom = 2, delayMs = 120_000))                     // between B and C, +2'
            st.upsertInfo(info("t2", aheadFrom = 0, stops = listOf("X", "Y"), line = "IC 1"))
            st.upsertInfo(info("t3", aheadFrom = 0, stops = listOf("Y", "Z"), line = "IC 1"))
            st.replacePositions(mapOf("t1" to pos("t1", null), "t2" to pos("t2", "X"), "t3" to pos("t3", "Y")), T0 + 1_100_000)
        }

        fun incident(id: String, scope: IncidentScope) =
            Incident(id = id, title = "Fault $id", severity = "major", createdAt = 1, updatedAt = 1, scope = scope)

        /** Stores [inc] and returns its row, as the Database listener would hand it over. */
        fun store(inc: Incident): ch.lauzhack.twin.db.IncidentRow {
            db.upsertIncident(inc, "{}")
            repeat(100) { db.incidents(true).firstOrNull { it.id == inc.id }?.let { return it }; Thread.sleep(10) }
            error("incident not stored")
        }
    }

    @Test
    fun arrivalAlertsGoOutOnceThenTheSubscriptionEnds() {
        val e = Env()
        val sub = e.push.followTrain(e.device.id, "t1", "C")
        assertEquals("Station C", sub.stopName)
        e.push.tick()                                                   // ETA C = T0+1.32M, now T0+1.1M: 3.7 min
        assertTrue(e.fake.sent.isEmpty())

        e.st.replacePositions(mapOf("t1" to pos("t1", null)), T0 + 1_220_000) // 100 s before
        e.push.tick(); e.push.tick()
        e.fake.await(1)
        assertEquals(listOf("Station C in 2 min"), e.fake.titles())
        assertTrue(e.fake.sent[0].second.body.contains("(+2')"))
        assertEquals("/?train=t1", e.fake.sent[0].second.url)

        e.st.replacePositions(mapOf("t1" to pos("t1", "C")), T0 + 1_330_000) // standing at C
        e.push.tick(); e.push.tick()
        e.fake.await(2)
        assertEquals("Now at Station C", e.fake.titles()[1])

        e.st.replacePositions(mapOf("t1" to pos("t1", null)), T0 + 1_400_000) // left C
        e.push.tick()
        assertTrue(e.push.subscriptions(e.device.id).isEmpty())
        assertEquals(2, e.fake.sent.size)
    }

    @Test
    fun trainGetsIncidentOnceAndItsResolution() {
        val e = Env()
        e.push.followTrain(e.device.id, "t1", null)
        val row = e.store(e.incident("i-1", IncidentScope(kind = "section", from = "C", to = "D")))
        e.push.onIncident("created", row)
        e.push.onIncident("updated", row)                               // same incident: no second alert
        e.push.onIncident("created", e.store(e.incident("i-far", IncidentScope(kind = "station", stopId = "Z"))))
        e.fake.await(1); Thread.sleep(100)
        assertEquals(listOf("Disruption: Fault i-1"), e.fake.titles())

        e.push.onIncident("removed", row)
        e.fake.await(2)
        assertEquals("Resolved: Fault i-1", e.fake.titles()[1])
    }

    @Test
    fun lineGetsIncidentsOfItsTrainsAndOfTheLine() {
        val e = Env()
        val sub = e.push.followLine(e.device.id, "ic 1")
        assertEquals("Line IC 1", sub.label)
        e.push.onIncident("created", e.store(e.incident("i-z", IncidentScope(kind = "station", stopId = "Z"))))   // t3 calls at Z
        e.push.onIncident("created", e.store(e.incident("i-line", IncidentScope(kind = "line", line = "IC 1"))))
        e.push.onIncident("created", e.store(e.incident("i-s1", IncidentScope(kind = "line", line = "S1"))))      // other line
        e.fake.await(2); Thread.sleep(100)
        assertEquals(setOf("Disruption: Fault i-z", "Disruption: Fault i-line"), e.fake.titles().toSet())
        assertTrue(e.fake.sent.all { it.second.url == "/?line=IC%201" })
    }

    @Test
    fun alreadyActiveIncidentIsNotRepeatedButItsResolutionIs() {
        val e = Env()
        val row = e.store(e.incident("i-old", IncidentScope(kind = "line", line = "IC 1")))
        e.push.followLine(e.device.id, "IC 1")
        e.push.onIncident("updated", row)
        Thread.sleep(100)
        assertTrue(e.fake.sent.isEmpty())
        e.push.onIncident("removed", row)
        e.fake.await(1)
        assertEquals(listOf("Resolved: Fault i-old"), e.fake.titles())
    }

    @Test
    fun goneDeviceIsDeleted() {
        val e = Env()
        e.fake.result = SendResult.GONE
        assertEquals(SendResult.GONE, e.push.test(e.device.id))
        assertTrue(runCatching { e.push.device(e.device.id) }.isFailure)
    }

    // ---------------------------------------------------------------- REST

    @Test
    fun restFlow() = testApplication {
        val st = LiveState()
        st.upsertInfo(info("t1", aheadFrom = 2))
        st.upsertInfo(info("t2", aheadFrom = 0, stops = listOf("X", "Y"), line = "IC 1"))
        st.replacePositions(mapOf("t1" to pos("t1", null), "t2" to pos("t2", "X")), T0 + 1_100_000)
        val db = Database(createTempFile("twin", ".sqlite").toString())
        val fake = Fake()
        application { configureHttp(st, db, pushSenders = mapOf("web" to fake)) { true } }
        val json = Json

        val cfg = json.parseToJsonElement(client.get("/api/v1/push/config").bodyAsText()).jsonObject
        assertEquals(65, unb64url(cfg["webPushPublicKey"]!!.jsonPrimitive.content).size)
        assertEquals("false", cfg["fcm"]!!.jsonPrimitive.content)

        val lines = json.parseToJsonElement(client.get("/api/v1/lines").bodyAsText()).jsonArray
        assertEquals(listOf("S1", "IC 1"), lines.map { it.jsonObject["line"]!!.jsonPrimitive.content })

        val key = b64url.encodeToString(P256.encode(P256.generate().public as ECPublicKey))
        val reg = client.post("/api/v1/push/devices") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"web","endpoint":"https://push.example.org/x","keys":{"p256dh":"$key","auth":"AAAAAAAAAAAAAAAAAAAAAA"}}""")
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val id = json.parseToJsonElement(reg.bodyAsText()).jsonObject["deviceId"]!!.jsonPrimitive.content

        val fcm = client.post("/api/v1/push/devices") { contentType(ContentType.Application.Json); setBody("""{"kind":"fcm","token":"${"x".repeat(40)}"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, fcm.status)

        val bad = client.post("/api/v1/push/devices/$id/subscriptions") { contentType(ContentType.Application.Json); setBody("""{"trainId":"t1","stopId":"A"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status) // A already passed

        val s1 = client.post("/api/v1/push/devices/$id/subscriptions") { contentType(ContentType.Application.Json); setBody("""{"trainId":"t1","stopId":"D"}""") }
        assertEquals(HttpStatusCode.Created, s1.status)
        val s1o = json.parseToJsonElement(s1.bodyAsText()).jsonObject
        assertEquals("S1 to D", s1o["label"]!!.jsonPrimitive.content)
        assertEquals(2, s1o["alerts"]!!.jsonArray.size)
        val s2 = client.post("/api/v1/push/devices/$id/subscriptions") { contentType(ContentType.Application.Json); setBody("""{"line":"IC 1"}""") }
        assertEquals(HttpStatusCode.Created, s2.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/api/v1/push/devices/$id/subscriptions") { contentType(ContentType.Application.Json); setBody("""{"line":"RE 99"}""") }.status,
        )

        val list = json.parseToJsonElement(client.get("/api/v1/push/devices/$id/subscriptions").bodyAsText()).jsonArray
        assertEquals(listOf("train", "line"), list.map { it.jsonObject["kind"]!!.jsonPrimitive.content })

        assertEquals("ok", json.parseToJsonElement(client.post("/api/v1/push/devices/$id/test").bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals(1, fake.sent.size)

        val sid = s1o["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/push/devices/$id/subscriptions/$sid").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/push/devices/$id/subscriptions/$sid").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/push/devices/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/push/devices/$id/subscriptions").status)
    }
}
