package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ProfilePinCacheStorage {
    private val store = DesktopStorage.store("nuvio_profile_pin_cache")

    actual fun loadAttempts(profileIndex: Int): String? = store.getFreshString("pin_attempts_$profileIndex")
    actual fun saveAttempts(profileIndex: Int, payload: String) {
        store.putString("pin_attempts_$profileIndex", payload)
        check(store.sync()) { "Could not save PIN attempts" }
    }

    actual fun loadPayload(profileIndex: Int): String? = store.exclusive {
        store.getFreshString(key(profileIndex))?.also { payload ->
            if (payload.contains("pbkdf2-sha256")) runCatching {
                check(store.sync())
                store.replacePreviousValue(key(profileIndex), payload)
            }.onFailure { co.touchlab.kermit.Logger.withTag("ProfilePin").w { "Could not update the previous PIN verifier copy." } }
        }
    }

    actual fun savePayload(profileIndex: Int, payload: String) {
        store.putString(key(profileIndex), payload)
        check(store.sync()) { "Could not save PIN verifier" }
        store.replacePreviousValue(key(profileIndex), payload)
    }

    actual fun removePayload(profileIndex: Int) {
        store.remove(key(profileIndex))
    }

    private fun key(profileIndex: Int): String = "profile_pin_$profileIndex"
}
