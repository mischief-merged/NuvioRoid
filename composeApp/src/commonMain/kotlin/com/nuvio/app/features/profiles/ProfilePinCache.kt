package com.nuvio.app.features.profiles

import kotlinx.serialization.Serializable

@Serializable
internal data class CachedProfilePinPayload(
    val salt: String,
    val digest: String,
    val profileUpdatedAt: String = "",
)

internal fun generateProfilePinSalt(): String = ProfilePinCrypto.newSalt()

internal fun hashProfilePin(profileIndex: Int, salt: String, pin: String): String =
    ProfilePinCrypto.hash(profileIndex, salt, pin)
