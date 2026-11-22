package ch.lauzhack.twin.wallet

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.HexFormat
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletProvidersTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val site = "https://trip.example"
    private val departure = Instant.parse("2030-01-10T08:00:00Z").toEpochMilli()
    private val leg = WatchLeg("rail", "IC 1", WatchStop("a", "Station A", 47.1, 8.1), WatchStop("b", "Station B", 47.2, 8.2),
        departure, departure + 60_000)
    private val watch = TripWatch("opaque-id", "owner", "random-pass-token", listOf(leg), departure + 120_000,
        leg.boardAdvice(), revision = 4, alertRevision = 4, alertText = "Board the confirmed IC 1")

    @Test
    fun applePassHasManifestSignatureAndBoardingLocation() {
        val name = X500Name("CN=Test Pass Signer")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(departure), name, keys.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)),
        )
        val provider = AppleWallet("pass.test.trip", "TESTTEAMID", keys.private, listOf(certificate), HttpClient.newHttpClient())
        val entries = ZipInputStream(ByteArrayInputStream(provider.pass(watch, site))).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
        val manifest = entries.getValue("manifest.json")
        val hashes = Json.parseToJsonElement(manifest.decodeToString()).jsonObject
        for ((path, hash) in hashes) {
            assertEquals(hash.jsonPrimitive.content,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(entries.getValue(path))))
        }
        val signed = CMSSignedData(CMSProcessableByteArray(manifest), entries.getValue("signature"))
        assertTrue(signed.signerInfos.signers.single().verify(JcaSimpleSignerInfoVerifierBuilder().build(certificate)))
        val pass = Json.parseToJsonElement(entries.getValue("pass.json").decodeToString()).jsonObject
        assertEquals(47.1, pass["locations"]!!.jsonArray.single().jsonObject["latitude"]!!.jsonPrimitive.content.toDouble())
        assertEquals("$site/wallet/apple", pass["webServiceURL"]!!.jsonPrimitive.content)

        // Laid out like a rail ticket in Wallet: a white train boarding pass with black values and labels.
        assertFalse(pass.containsKey("generic"))
        assertEquals("Train twin", pass.text("organizationName"))
        assertEquals("Train twin", pass.text("logoText"))
        assertEquals("rgb(255, 255, 255)", pass.text("backgroundColor"))
        assertEquals("rgb(0, 0, 0)", pass.text("foregroundColor"))
        assertEquals("rgb(0, 0, 0)", pass.text("labelColor"))
        val board = pass["boardingPass"]!!.jsonObject
        assertEquals("PKTransitTypeTrain", board.text("transitType"))
        assertEquals(listOf("Departure:" to "10.01.2030 09:00"), board.fields("headerFields"))
        assertEquals(listOf(null to "Station A", null to "Station B"), board.fields("primaryFields"))
        assertEquals(listOf(null to "IC 1", null to "Direct", null to "10.01.2030"), board.fields("secondaryFields"))
        assertEquals(listOf("Next step" to "Take IC 1 from Station A at 09:00"), board.fields("auxiliaryFields"))
        val back = board["backFields"]!!.jsonArray.map { it.jsonObject }
        assertEquals("%@", back.single { it.text("key") == "alert" }.text("changeMessage"))
        assertTrue(back.single { it.text("key") == "notice" }.text("value")!!.contains("not a ticket"))

        // The QR code opens the watch page and says it is not a ticket.
        val qr = pass["barcodes"]!!.jsonArray.single().jsonObject
        assertEquals("PKBarcodeFormatQR", qr.text("format"))
        assertEquals("$site/trip-watch.html?id=opaque-id", qr.text("message"))
        assertEquals("iso-8859-1", qr.text("messageEncoding"))
        assertEquals("Travel information · not a ticket", qr.text("altText"))

        // The logo is a plain red 3:1 bar at every scale, and it is signed with the rest of the pass.
        for ((name, scale) in listOf("logo.png" to 1, "logo@2x.png" to 2, "logo@3x.png" to 3)) {
            assertTrue(hashes.containsKey(name), name)
            val logo = ImageIO.read(ByteArrayInputStream(entries.getValue(name)))
            assertEquals(45 * scale to 15 * scale, logo.width to logo.height)
            assertEquals(0xEB0000, logo.getRGB(logo.width / 2, logo.height / 2) and 0xFFFFFF)
        }
    }

    @Test
    fun googleCredentialsLoadFromAFileOrTheJsonItself() {
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val json = Json.encodeToString(JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("client_email", kotlinx.serialization.json.JsonPrimitive("issuer@example.test"))
            put("private_key", kotlinx.serialization.json.JsonPrimitive(pem))
        })
        val file = java.nio.file.Files.writeString(java.nio.file.Files.createTempFile("wallet-sa", ".json"), json)
        try {
            for (value in listOf(file.toString(), json, Base64.getEncoder().encodeToString(json.toByteArray()))) {
                GoogleWallet.load("123.trip", value) // throws on a bad credential
            }
            kotlin.test.assertFailsWith<IllegalArgumentException> { GoogleWallet.load("123.trip", "not-a-file-nor-json") }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun googlePassShowsTheSameTicketFaceAndQrCode() {
        val pass = GoogleWallet("123.trip", "issuer@example.test", keys.private).pass(watch, site)
        assertEquals("#ffffff", pass.text("hexBackgroundColor"))
        assertEquals("Train twin", pass.localized("cardTitle"))
        assertEquals("Departure 10.01.2030 09:00", pass.localized("subheader"))
        assertEquals("Station A → Station B", pass.localized("header"))
        assertEquals("$site/wallet/logo.png", pass["logo"]!!.jsonObject["sourceUri"]!!.jsonObject.text("uri"))
        val qr = pass["barcode"]!!.jsonObject
        assertEquals("QR_CODE", qr.text("type"))
        assertEquals("$site/trip-watch.html?id=opaque-id", qr.text("value"))
        assertEquals("Travel information · not a ticket", qr.text("alternateText"))
        val modules = pass["textModulesData"]!!.jsonArray.associate { it.jsonObject.text("id") to it.jsonObject.text("body") }
        assertEquals("Take IC 1 from Station A at 09:00", modules["next"])
        assertEquals("IC 1 · Direct", modules["line"])
        assertTrue(modules.getValue("notice")!!.contains("not a ticket"))

        // Google crops logos to a circle: the red bar must sit well inside it on a white square.
        val logo = ImageIO.read(ByteArrayInputStream(passCircleLogo()))
        assertEquals(logo.width, logo.height)
        val red = buildList {
            for (y in 0 until logo.height) for (x in 0 until logo.width) {
                if (logo.getRGB(x, y) and 0xFFFFFF == 0xEB0000) add(x to y)
            }
        }
        val radius = logo.width / 2.0
        assertTrue(red.all { (x, y) -> hypot(x + 0.5 - radius, y + 0.5 - radius) < radius * 0.85 })
        assertEquals(0xFFFFFF, logo.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0xEB0000, logo.getRGB(logo.width / 2, logo.height / 2) and 0xFFFFFF)
    }

    @Test
    fun googleSaveJwtAndRetriesUseOneMessagePerAdviceRevision() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var exists = false
        var message: String? = null
        var adds = 0
        server.createContext("/") { request ->
            val path = request.requestURI.path
            val body = request.requestBody.bufferedReader().use { it.readText() }
            val (status, reply) = when {
                path == "/token" -> 200 to """{"access_token":"fixture","expires_in":3600}"""
                request.requestMethod == "GET" -> if (exists) 200 to "{}" else 404 to "{}"
                path == "/genericObject" -> {
                    exists = true
                    200 to "{}"
                }
                request.requestMethod == "PATCH" -> 200 to """{"messages":[${message?.let { """{"id":"$it"}""" }.orEmpty()}]}"""
                path.endsWith("/addMessage") -> {
                    val sent = Json.parseToJsonElement(body).jsonObject["message"]!!.jsonObject
                    message = if (sent["messageType"]!!.jsonPrimitive.content == "TEXT_AND_NOTIFY") sent["id"]!!.jsonPrimitive.content else null
                    adds++
                    200 to "{}"
                }
                else -> 400 to "{}"
            }
            val data = reply.toByteArray()
            request.sendResponseHeaders(status, data.size.toLong())
            request.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val local = "http://127.0.0.1:${server.address.port}"
            val provider = GoogleWallet("123.trip", "issuer@example.test", keys.private, api = local, tokenEndpoint = "$local/token")
            provider.ensurePass(watch, site)
            val jwt = provider.saveUrl(watch).substringAfterLast('/').split('.')
            assertEquals(3, jwt.size)
            val signed = Signature.getInstance("SHA256withRSA").apply {
                initVerify(keys.public); update("${jwt[0]}.${jwt[1]}".toByteArray())
            }
            assertTrue(signed.verify(Base64.getUrlDecoder().decode(jwt[2])))
            val pass = provider.pass(watch, site)
            assertEquals("47.1", pass["merchantLocations"]!!.jsonArray.single().jsonObject["latitude"]!!.jsonPrimitive.content)
            provider.update(watch, site, notify = true)
            provider.update(watch, site, notify = true)
            assertEquals("update-4", message)
            assertEquals(1, adds)
        } finally {
            server.stop(0)
        }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.localized(key: String) = this[key]!!.jsonObject["defaultValue"]!!.jsonObject.text("value")
    private fun JsonObject.fields(key: String) = this[key]!!.jsonArray.map { it.jsonObject.text("label") to it.jsonObject.text("value") }
}
