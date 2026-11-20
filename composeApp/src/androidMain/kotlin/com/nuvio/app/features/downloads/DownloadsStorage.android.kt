package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"
    private const val unreadablePayloadKey = "downloads_unreadable_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey, profileId), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
            ?.apply()
    }

    actual fun loadPreviousPayload(): String? = null

    actual fun loadPreviousPayload(profileId: Int): String? = null

    actual fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
        preferences
            ?.edit()
            ?.putString("${ProfileScopedKey.of(unreadablePayloadKey)}_$atEpochMs", payload)
            ?.apply()
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) {
        preferences
            ?.edit()
            ?.putString("${ProfileScopedKey.of(unreadablePayloadKey, profileId)}_$atEpochMs", payload)
            ?.apply()
    }

    // One copy of the app per device here: nothing to re-read and no lock across processes.
    actual val rereadsPayload: Boolean = false

    actual fun loadFreshPayload(): String? = null

    actual fun loadFreshPayload(profileId: Int): String? = null

    actual fun syncPayload(): Boolean = true

    actual fun <T> withListLock(block: () -> T): T = block()
}
