package com.nuvio.app.features.profiles

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal actual object ProfilePinCrypto {
    private const val iterations = 600_000
    private val random = SecureRandom()
    actual fun newSalt(): String = ByteArray(16).also(random::nextBytes).toHex()

    actual fun hash(profileIndex: Int, salt: String, pin: String): String =
        "pbkdf2-sha256\$$iterations\$${derive(profileIndex, salt, pin, iterations).toHex()}"

    actual fun verify(profileIndex: Int, salt: String, pin: String, digest: String): Boolean {
        if (!pin.matches(Regex("[0-9]{4}"))) return false
        if (!digest.startsWith("pbkdf2-sha256$")) {
            if (!digest.matches(Regex("[0-9a-f]{64}"))) return false
            return equal(sha256Hex("profile:$profileIndex:$salt:$pin"), digest)
        }
        val parts = digest.split('$')
        if (parts.size != 3 || !parts[2].matches(Regex("[0-9a-f]{64}"))) return false
        val rounds = parts[1].toIntOrNull()?.takeIf { it in 100_000..2_000_000 } ?: return false
        if (!salt.matches(Regex("[0-9a-f]{32}"))) return false
        return equal(derive(profileIndex, salt, pin, rounds).toHex(), parts[2])
    }

    actual fun needsUpgrade(digest: String): Boolean = !digest.startsWith("pbkdf2-sha256\$$iterations\$")
    actual fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun derive(profileIndex: Int, salt: String, pin: String, rounds: Int): ByteArray {
        val password = pin.toCharArray()
        val spec = PBEKeySpec(password, "profile:$profileIndex:$salt".toByteArray(Charsets.UTF_8), rounds, 256)
        password.fill('\u0000')
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun equal(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
