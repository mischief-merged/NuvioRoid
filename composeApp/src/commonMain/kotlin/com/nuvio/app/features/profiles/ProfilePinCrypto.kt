package com.nuvio.app.features.profiles

internal expect object ProfilePinCrypto {
    fun newSalt(): String
    fun hash(profileIndex: Int, salt: String, pin: String): String
    fun verify(profileIndex: Int, salt: String, pin: String, digest: String): Boolean
    fun needsUpgrade(digest: String): Boolean
    fun sha256Hex(value: String): String
}