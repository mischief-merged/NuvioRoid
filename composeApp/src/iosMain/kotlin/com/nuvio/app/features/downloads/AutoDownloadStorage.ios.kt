package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object AutoDownloadStorage {
    private const val payloadKey = "auto_downloads_payload"
    private const val unreadablePayloadKey = "auto_downloads_unreadable_payload"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey, profileId))

    actual fun loadFreshPayload(profileId: Int): String? = loadPayload(profileId)

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey, profileId))
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(unreadablePayloadKey, profileId))
    }

    actual fun syncPayload(): Boolean = true
}
