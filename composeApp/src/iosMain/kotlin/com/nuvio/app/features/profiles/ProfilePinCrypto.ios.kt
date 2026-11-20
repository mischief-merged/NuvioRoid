package com.nuvio.app.features.profiles

import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo

actual object ProfilePinCrypto {
    actual fun newSalt(): String = kotlin.random.Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    actual fun hash(profileIndex: Int, salt: String, pin: String): String = sha256Hex("profile:$profileIndex:$salt:$pin")
    actual fun verify(profileIndex: Int, salt: String, pin: String, digest: String): Boolean = hash(profileIndex, salt, pin) == digest
    actual fun needsUpgrade(digest: String): Boolean = false

    @OptIn(ExperimentalForeignApi::class)
    actual fun sha256Hex(value: String): String {
        val input = value.encodeToByteArray()
        val output = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        CC_SHA256(input.refTo(0), input.size.toUInt(), output.refTo(0))
        return output.joinToString(separator = "") { byte ->
            byte.toString(16).padStart(2, '0')
        }
    }
}