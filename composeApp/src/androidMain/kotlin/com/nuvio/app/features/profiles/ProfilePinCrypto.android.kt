package com.nuvio.app.features.profiles

import java.security.MessageDigest

actual object ProfilePinCrypto {
    actual fun newSalt(): String = kotlin.random.Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    actual fun hash(profileIndex: Int, salt: String, pin: String): String = sha256Hex("profile:$profileIndex:$salt:$pin")
    actual fun verify(profileIndex: Int, salt: String, pin: String, digest: String): Boolean = hash(profileIndex, salt, pin) == digest
    actual fun needsUpgrade(digest: String): Boolean = false

    actual fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return digest.joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }
}