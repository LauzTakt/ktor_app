package ch.lauzhack.twin.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 (JDK only), stored as `pbkdf2-sha256$<iterations>$<salt>$<hash>`. */
internal object Passwords {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        return "pbkdf2-sha256\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = b64d.decode(parts[3])
        return MessageDigest.isEqual(derive(password, b64d.decode(parts[2]), iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).encoded

    /** Same cost as a real check, so unknown usernames take as long as wrong passwords. */
    val dummyHash: String by lazy { hash("dummy-password") }
}

private val tokenRnd = SecureRandom()

/** A new random login token (256 bits, URL-safe base64). Only its [sha256] is stored. */
internal fun newToken(): String =
    ByteArray(32).also { tokenRnd.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

internal fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
