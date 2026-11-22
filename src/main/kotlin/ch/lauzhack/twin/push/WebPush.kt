package ch.lauzhack.twin.push

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Web Push with the JDK only (no extra dependency):
 *  - payload encryption: RFC 8291 (Message Encryption for Web Push), content coding `aes128gcm` (RFC 8188);
 *  - server identification: VAPID (RFC 8292), an ES256 JWT in `Authorization: vapid t=<jwt>, k=<public key>`.
 * Works with every push service (FCM for Chrome, Mozilla autopush, Apple for Safari / iOS home-screen apps).
 */

internal val b64url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
internal fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('=').replace('+', '-').replace('/', '_'))

internal object P256 {
    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()

    /** 65-byte uncompressed point `04 || X || Y`. */
    fun encode(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)

    fun decodePublic(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "expected an uncompressed P-256 point (65 bytes)" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    /** Raw 32-byte scalar (format of the `web-push` npm tool) or PKCS#8 DER. */
    fun decodePrivate(raw: ByteArray): ECPrivateKey = KeyFactory.getInstance("EC").generatePrivate(
        if (raw.size <= 32) ECPrivateKeySpec(BigInteger(1, raw), params) else PKCS8EncodedKeySpec(raw),
    ) as ECPrivateKey

    fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}

/** VAPID key pair; [publicKey] is what browsers need as `applicationServerKey` (base64url). */
class VapidKeys(val publicKeyRaw: ByteArray, val privateKey: ECPrivateKey) {
    val publicKey: String get() = b64url.encodeToString(publicKeyRaw)

    companion object {
        fun generate(): VapidKeys {
            val kp = P256.generate()
            return VapidKeys(P256.encode(kp.public as ECPublicKey), kp.private as ECPrivateKey)
        }

        /** [publicKey]: base64url uncompressed point; [privateKey]: base64url raw scalar or PKCS#8. */
        fun parse(publicKey: String, privateKey: String): VapidKeys {
            val pub = unb64url(publicKey)
            P256.decodePublic(pub) // validates
            return VapidKeys(pub, P256.decodePrivate(unb64url(privateKey)))
        }
    }

    /** Private key as base64url PKCS#8, for storage. */
    fun privateKeyEncoded(): String = b64url.encodeToString(privateKey.encoded)
}

object WebPushCrypto {
    private const val RECORD_SIZE = 4096
    private val rnd = SecureRandom()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /**
     * Encrypts [plaintext] for a browser subscription ([uaPublic] = `keys.p256dh`, [authSecret] = `keys.auth`).
     * Returns the full `aes128gcm` body (header + one record). [ephemeral] and [salt] are parameters for tests only.
     */
    fun encrypt(
        plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray,
        ephemeral: KeyPair = P256.generate(), salt: ByteArray = ByteArray(16).also(rnd::nextBytes),
    ): ByteArray {
        val asPublic = P256.encode(ephemeral.public as ECPublicKey)
        val ecdh = KeyAgreement.getInstance("ECDH").apply {
            init(ephemeral.private); doPhase(P256.decodePublic(uaPublic), true)
        }.generateSecret()
        val prkKey = hmac(authSecret, ecdh)
        val keyInfo = "WebPush: info".toByteArray() + 0 + uaPublic + asPublic
        val ikm = hmac(prkKey, keyInfo + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        require(plaintext.size + 1 + 16 <= RECORD_SIZE) { "payload too large for one record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }
        val record = cipher.doFinal(plaintext + 2) // 0x02 = padding delimiter of the last record
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + record.size)
            .put(salt).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(record).array()
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

    /** VAPID JWT for [endpoint]'s origin, valid 12 h. */
    fun vapidJwt(endpoint: String, keys: VapidKeys, subject: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val uri = URI(endpoint)
        val aud = "${uri.scheme}://${uri.host}" + (if (uri.port != -1) ":${uri.port}" else "")
        val header = b64url.encodeToString("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64url.encodeToString("""{"aud":"$aud","exp":${nowSec + 12 * 3600},"sub":"$subject"}""".toByteArray())
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(keys.privateKey); update("$header.$claims".toByteArray())
        }.sign()
        return "$header.$claims.${b64url.encodeToString(sig)}"
    }
}
