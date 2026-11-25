package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AutoDownloadStorage {
    private const val preferencesName = "nuvio_auto_downloads"
    private const val payloadKey = "auto_downloads_payload"
    private const val unreadablePayloadKey = "auto_downloads_unreadable_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey, profileId), null)

    actual fun loadFreshPayload(profileId: Int): String? = loadPayload(profileId)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
            ?.apply()
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(unreadablePayloadKey, profileId), payload)
            ?.apply()
    }

    actual fun syncPayload(): Boolean = true
}
