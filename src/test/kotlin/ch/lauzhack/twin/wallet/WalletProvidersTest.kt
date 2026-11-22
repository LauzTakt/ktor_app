package ch.lauzhack.twin.wallet

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
        assertFalse(pass.containsKey("barcodes"))
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
}
