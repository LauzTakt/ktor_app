package ch.lauzhack.twin.wallet

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal class AppleWallet(
    val passType: String,
    private val team: String,
    private val key: PrivateKey,
    private val certificates: List<X509Certificate>,
    private val http: HttpClient,
) {
    fun pass(watch: TripWatch, site: String): ByteArray {
        val relevant = watch.relevantLeg()
        val face = watch.passFace(site)
        val fields = buildJsonObject {
            put("formatVersion", 1)
            put("description", "Trip Watch travel updates")
            put("organizationName", PASS_BRAND)
            put("logoText", PASS_BRAND)
            put("passTypeIdentifier", passType)
            put("teamIdentifier", team)
            put("serialNumber", watch.id)
            put("authenticationToken", watch.appleToken)
            put("webServiceURL", "$site/wallet/apple")
            // A rail ticket in Wallet: white card, black values and black (bold) labels.
            put("foregroundColor", "rgb(0, 0, 0)")
            put("backgroundColor", "rgb(255, 255, 255)")
            put("labelColor", "rgb(0, 0, 0)")
            put("expirationDate", Instant.ofEpochMilli(if (watch.ended) minOf(watch.expiresAt, watch.updatedAt) else watch.expiresAt).toString())
            if (watch.ended) put("voided", true)
            if (relevant != null && relevant.from.lat != null && relevant.from.lon != null) {
                put("relevantDate", Instant.ofEpochMilli(relevant.expectedDeparture).toString())
                put("locations", buildJsonArray {
                    add(buildJsonObject {
                        put("latitude", relevant.from.lat)
                        put("longitude", relevant.from.lon)
                        put("relevantText", "Trip from ${relevant.from.name}")
                    })
                })
            }
            // The QR code opens the Trip Watch page. It never carries ticket data.
            put("barcodes", buildJsonArray {
                add(buildJsonObject {
                    put("format", "PKBarcodeFormatQR"); put("message", face.url)
                    put("messageEncoding", "iso-8859-1"); put("altText", PASS_NOTICE)
                })
            })
            put("boardingPass", buildJsonObject {
                put("transitType", "PKTransitTypeTrain")
                put("headerFields", buildJsonArray { add(field("departure", "Departure:", face.departure)) })
                put("primaryFields", buildJsonArray {
                    add(value("origin", face.origin)); add(value("destination", face.destination))
                })
                put("secondaryFields", buildJsonArray {
                    add(value("line", face.line)); add(value("changes", face.changes)); add(value("date", face.date))
                })
                // Lock-screen alerts stay on the back "alert" field, which changes only for actionable advice. A
                // change message here would also fire on the passenger's own boarded/alighted taps (silent syncs).
                put("auxiliaryFields", buildJsonArray { add(field("next", "Next step", face.nextStep)) })
                put("backFields", buildJsonArray {
                    watch.advice.detail?.let { add(field("detail", "Trip update", it)) }
                    if (!watch.ended) add(buildJsonObject {
                        put("key", "alert"); put("label", "Latest instruction")
                        put("value", watch.alertText.orEmpty()); put("changeMessage", "%@")
                    })
                    add(buildJsonObject {
                        put("key", "open"); put("label", "Journey"); put("value", "Open Trip Watch")
                        put("attributedValue", "<a href=\"${face.url}\">Open Trip Watch</a>")
                    })
                    add(field("notice", "About this pass", PASS_ABOUT))
                })
            })
        }
        val assets = linkedMapOf(
            "pass.json" to fields.toString().toByteArray(),
            "icon.png" to walletIcon32,
            "icon@2x.png" to walletIcon64,
            "icon@3x.png" to walletIcon96,
            "logo.png" to walletLogo1x,
            "logo@2x.png" to walletLogo2x,
            "logo@3x.png" to walletLogo3x,
        )
        val manifest = buildJsonObject {
            for ((name, bytes) in assets) put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)))
        }.toString().toByteArray()
        val signature = CMSSignedDataGenerator().apply {
            addCertificates(JcaCertStore(certificates))
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(key)
            val digest = JcaDigestCalculatorProviderBuilder().build()
            addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digest).build(signer, certificates.first()))
        }.generate(CMSProcessableByteArray(manifest), false).encoded
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for ((name, content) in assets + mapOf("manifest.json" to manifest, "signature" to signature)) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    /** Returns false for a permanently invalid registration. */
    suspend fun wake(pushToken: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create("https://api.push.apple.com/3/device/$pushToken"))
            .timeout(Duration.ofSeconds(8))
            .header("apns-topic", passType)
            .header("apns-priority", "5")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return when {
            response.statusCode() == 200 -> true
            response.statusCode() == 410 || response.body().contains("\"BadDeviceToken\"") -> false
            else -> error("APNs returned HTTP ${response.statusCode()}")
        }
    }

    private fun field(key: String, label: String, value: String) = buildJsonObject {
        put("key", key); put("label", label); put("value", value)
    }

    /** A plain value without a label, like the station names and ticket details on a rail ticket. */
    private fun value(key: String, value: String) = buildJsonObject { put("key", key); put("value", value) }

    companion object {
        fun load(passType: String, team: String, path: String, password: String, intermediate: String?): AppleWallet {
            val secret = password.toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(Path.of(path)).use { store.load(it, secret) }
            val alias = store.aliases().toList().single { store.isKeyEntry(it) }
            val key = store.getKey(alias, secret) as PrivateKey
            val chain = store.getCertificateChain(alias).map { it as X509Certificate }.toMutableList()
            intermediate?.let { file ->
                val cert = Files.newInputStream(Path.of(file)).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                if (cert !in chain) chain.add(cert as X509Certificate)
            }
            require(chain.size >= 2) { "Supply the Apple WWDR intermediate certificate" }
            chain.first().checkValidity()
            chain.first().verify(chain[1].publicKey)
            store.setKeyEntry(alias, key, secret, chain.toTypedArray())
            val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, secret) }
            val tls = SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
            val client = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(4)).build()
            return AppleWallet(passType, team, key, chain, client)
        }
    }
}

internal fun TripWatch.relevantLeg(): WatchLeg? =
    if (ended) null else legs.getOrNull(currentLeg + if (onBoard) 1 else 0)

private val walletIcon32 by lazy { walletIcon(32) }
private val walletIcon64 by lazy { walletIcon(64) }
internal val walletIcon96 by lazy { walletIcon(96) }
private val walletLogo1x by lazy { passLogo(1) }
private val walletLogo2x by lazy { passLogo(2) }
private val walletLogo3x by lazy { passLogo(3) }

private fun walletIcon(size: Int): ByteArray {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val draw = image.createGraphics()
    try {
        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        draw.scale(size / 32.0, size / 32.0)
        draw.color = Color(235, 0, 0)
        draw.fillRoundRect(0, 0, 32, 32, 8, 8)
        draw.color = Color.WHITE
        draw.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        draw.drawRoundRect(9, 6, 14, 17, 5, 5)
        draw.drawLine(10, 15, 22, 15)
        draw.drawLine(12, 23, 9, 27)
        draw.drawLine(20, 23, 23, 27)
        draw.fillOval(12, 18, 2, 2)
        draw.fillOval(18, 18, 2, 2)
    } finally {
        draw.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
